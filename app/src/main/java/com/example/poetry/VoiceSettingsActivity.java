package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.poetry.ui.Skin;
import com.example.poetry.adapter.VoiceAdapter;
import com.example.poetry.data.Callback;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Member;
import com.example.poetry.data.model.UserProfile;
import com.example.poetry.data.model.Voice;
import com.example.poetry.databinding.ActivityVoiceSettingsBinding;
import com.example.poetry.media.Speaker;
import androidx.appcompat.app.AlertDialog;
import com.example.poetry.util.Sliders;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 语音设置页：发音人、语速、音调、音量，外加引擎本身的状态。
 * <p>
 * 合成不依赖任何外部程序——语音模型随 APK 分发，装在应用里（见 {@code media.SherpaTts}）。
 * 所以这一页没有「去装个引擎」「去导入语音包」这类出口，只需要把「引擎起来了没有」
 * 讲清楚：模型加载是异步的，用户可能在本页刚打开时就看到状态。
 * <p>
 * 所有改动立即写入 {@link UserStore}（单份 JSON 快照），下次启动自动恢复；
 * 同时立即下发给引擎，便于当场试听。
 */
public class VoiceSettingsActivity extends AppCompatActivity {

    private ActivityVoiceSettingsBinding binding;
    private UserStore store;
    private TtsConfig config;
    private VoiceAdapter adapter;
    private final List<Voice> voices = new ArrayList<>();
    private boolean testing;
    /** 云端音色被临时顶替的说明每个页面实例只弹一次（见 {@link #noteCloudSuspended}）。 */
    private boolean cloudSuspendedNotified;

    public static void open(@NonNull Context context) {
        context.startActivity(new Intent(context, VoiceSettingsActivity.class));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityVoiceSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        store = UserStore.get(this);
        config = store.getTtsConfig();

        binding.backButton.setOnClickListener(v -> finish());
        setupVoices();
        setupSliders();
        setupActions();
        setupMember();

        Speaker.get().setListener(new Speaker.Listener() {
            @Override
            public void onStart() {
                // 合成中：保持「停止」按钮可用
            }

            @Override
            public void onDone() {
                setTesting(false);
            }

            @Override
            public void onError(String message) {
                setTesting(false);
                Toast.makeText(VoiceSettingsActivity.this, message, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNotice(String message) {
                // 试听还在继续，只是换了声音：按钮留在「停止」上，别把它复位成「试听」
                Toast.makeText(VoiceSettingsActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });

        // 云端音色表在网络上，画完这一屏它多半还没到。等它落定再重画一次，
        // 这一步顺带消掉「目录还没来就说你存的云端音色用不上了」的误报（见 reloadVoices）。
        Speaker.get().setVoiceListListener(this::onVoiceListChanged);
        Speaker.get().prepare(this, this::onEngineReady);
        refreshEngineState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 引擎可能是离开这段时间里加载完的（首启时加载要好几秒），回来再对一次状态
        Speaker.get().prepare(this, this::onEngineReady);
        // 登录态/会员状态可能是在离开这段时间里变的（在别的页面登录、服务端给会员续了期），
        // 而音色的锁定态是服务端按请求里的 token 现算的——本机缓存翻不翻新它不知道，
        // 所以每次回来都重取一次目录。接口侧自带节流（见 CloudTts#refreshVoices），
        // 反复进出这一页不会把熔断器敲开。
        Speaker.get().refreshVoices();
        // 这一页的「会员有效期至 X」读的是本机缓存，离开这段时间里也可能变了
        // （在别处登录、服务端续期或收回）。目录那一次重取救不了它——锁定态由服务端
        // 按 token 现算，跟缓存无关。所以这里单独再问一次资料，回来的是哪一份就显示哪一份。
        // 没登录就不问了：那一支的文案是固定的，而没票的请求只会白白落一次空账号。
        if (store.isLoggedIn()) {
            refreshMember();
        }
        refreshEngineState();
    }

    /** 问一次服务端资料。落库由仓库层负责（见 {@code PoetryRepository#fetchProfile}）。 */
    private void refreshMember() {
        PoetryRepository.get(this).refreshProfile(new Callback<UserProfile>() {
            @Override
            public void onData(@NonNull UserProfile data) {
                onMemberRefreshed();
            }

            @Override
            public void onError(@Nullable Throwable error) {
                // 拉不到就已经按未开通落库了，界面对着落库结果重画即可——
                // 失败原因不必弹窗，那不是用户能处理的事
                onMemberRefreshed();
            }
        });
    }

    /** 资料回来之后重画会员那一行。回调可能在页面销毁之后才到（见 {@link #onVoiceListChanged}）。 */
    private void onMemberRefreshed() {
        if (binding == null) {
            return;
        }
        updateMember();
    }

    // ------------------------------------------------------------------ 音色

    private void setupVoices() {
        adapter = new VoiceAdapter(new VoiceAdapter.Listener() {
            @Override
            public void onVoiceClick(@NonNull Voice voice) {
                selectVoice(voice);
            }

            @Override
            public void onTryClick(@NonNull Voice voice) {
                selectVoice(voice);
                startPreview();
            }
        });
        binding.voiceList.setLayoutManager(new LinearLayoutManager(this));
        binding.voiceList.setAdapter(adapter);
        reloadVoices();
    }

    private void reloadVoices() {
        voices.clear();
        voices.addAll(Speaker.get().listVoices());
        // 引擎是音色的唯一权威，currentVoiceId() 问的就是它（每次都问，不缓存），所以下面打勾的
        // 那个一定就是实际会发声的那个。存着的老 id（例如只有小雅那个年代的 sherpa-xiao-ya）、
        // 换过语音包之后不存在的音色、以及当下用不上的云端音色，都会被引擎落到内置音色上，
        // 勾跟着走。
        // 但配置要不要跟着改，得分两种情况：老 id 是真没了，该改；云端音色只是「暂时用不上」
        // （会员没开、或云端没配好），不能改——见下面那个 if。
        String stored = config.getVoiceId();
        String current = Speaker.get().currentVoiceId();
        if (current == null || current.isEmpty()) {
            current = stored;
        }
        boolean matched = false;
        for (Voice voice : voices) {
            boolean selected = voice.getId().equals(current);
            voice.setSelected(selected);
            if (selected) {
                matched = true;
            }
        }
        if (!matched && !voices.isEmpty()) {
            // 音色表整个对不上（例如换了语音包）：落到第一个，用户不用自己去理解这个落差
            voices.get(0).setSelected(true);
            current = voices.get(0).getId();
        }
        if (current != null && !current.isEmpty() && !current.equals(stored)) {
            if (Voice.isCloudId(stored)) {
                // 存的是云端音色、当下又用不上（会员没开，或云端暂时没配好）：引擎已经临时
                // 改用内置音色了。但这个替换**不能写回配置**——云端音色是用户自己挑的，
                // 会员开了 / 云端回来了就该自动切回去；写回去等于把他的选择永久抹掉，
                // 而且是在他毫不知情的情况下。这里只说明「这次换了、你的选择还在」。
                //
                // 但目录还没落定的时候先别说：这一刻表里压根没有云端音色，
                // currentVoiceId() 必然为空，说出来的原因（需会员 / 暂时不可用）都是猜的，
                // 而这两句话指的下一步动作完全不同。目录到了会再跑一次（见 onVoiceListChanged）。
                if (Speaker.get().isVoiceListSettled()) {
                    noteCloudSuspended(stored);
                }
            } else {
                config.setVoiceId(current);
                persist();
            }
        }
        adapter.submit(voices);
        // 「可用」只数没上锁的：会员没开时云端音色照样列在表里（灰着 + 锁标），
        // 全算进去会写成「21 种可用」，而真正点得动的只有 9 个，标题和列表对不上。
        int usable = 0;
        for (Voice voice : voices) {
            if (!voice.isLocked()) {
                usable++;
            }
        }
        int locked = voices.size() - usable;
        binding.voiceMeta.setText(locked > 0
                ? getString(R.string.tts_voice_meta_locked, usable, locked)
                : getString(R.string.tts_voice_meta, usable));
    }

    /**
     * 云端音色被内置音色临时顶替时，说明这一次替换。
     * <p>
     * {@link #reloadVoices()} 在引擎就绪和每次 {@code onResume} 都会跑，不能每次都弹，
     * 所以每个页面实例只提示一次。会员没开和云端暂时不可用是两回事，措辞分开，
     * 不然用户会照着「开通会员」去折腾一个其实已经开通了的问题。
     */
    private void noteCloudSuspended(@NonNull String cloudId) {
        if (cloudSuspendedNotified) {
            return;
        }
        cloudSuspendedNotified = true;
        Toast.makeText(this, getString(store.isMember()
                        ? R.string.voice_cloud_unavailable
                        : R.string.voice_member_suspended,
                voiceName(cloudId)), Toast.LENGTH_LONG).show();
    }

    /** 当前存的是云端音色就说明一次；不是（离线音色）则什么都不用说。 */
    private void noteCloudSuspendedIfNeeded() {
        String stored = config.getVoiceId();
        if (Voice.isCloudId(stored)) {
            noteCloudSuspended(stored);
        }
    }

    /**
     * 音色 id 的显示名。
     * <p>
     * 云端音色不一定在 {@link #voices} 里——云端没配好时 {@code EngineRouter} 根本不列它们，
     * 而「被顶替的正好是云端音色」正是那种时候会发生的。音色名由服务端下发，本地没有第二张表
     * 可以反查，所以认不出来时对云端 id 用一个通称，其余原样显示——总好过留个空名字，
     * 也好过把 {@code cloud-101001} 这种内部 id 念给用户听。
     */
    @NonNull
    private String voiceName(@NonNull String id) {
        for (Voice voice : voices) {
            if (id.equals(voice.getId())) {
                return voice.getName();
            }
        }
        return Voice.isCloudId(id) ? getString(R.string.voice_cloud_name) : id;
    }

    private void selectVoice(@NonNull Voice voice) {
        if (voice.isLocked()) {
            showMemberRequired();
            return;
        }
        for (Voice item : voices) {
            item.setSelected(false);
        }
        voice.setSelected(true);
        config.setVoiceId(voice.getId());
        persist();
        adapter.submit(voices);
        Speaker.get().setVoice(voice.getId());
    }

    // ------------------------------------------------------------------ 引擎状态

    private void onEngineReady() {
        refreshEngineState();
        reloadVoices();
    }

    /**
     * 云端音色表变了：拉回来了，或者确认这一次拉不到（见 {@code SpeechEngine#setVoiceListListener}）。
     * <p>
     * 两者都要重画：拉回来了是多出几行（顺带把「这次为什么用不上云端音色」的说明补上，
     * 见 {@code reloadVoices}），拉不到则是把状态从「正在获取」改成「暂不可用」——
     * 用户看一眼就知道该修网络还是该去开通会员。
     * <p>
     * 回调在主线程，但可能在页面销毁之后才到（目录在飞的时候用户退出了），所以先看 binding。
     */
    private void onVoiceListChanged() {
        if (binding == null) {
            return;
        }
        refreshEngineState();
        reloadVoices();
    }

    private void refreshEngineState() {
        String status = Speaker.get().statusText(this);
        binding.engineStatus.setText(getString(R.string.tts_engine_status, status));
    }

    // ------------------------------------------------------------------ 参数滑杆

    private void setupSliders() {
        applyConfigToSliders();

        binding.rateSlider.addOnChangeListener((slider, value, fromUser) -> {
            config.setRate(Sliders.snapAndPushBack(slider, value));
            updateValueLabels();
            persistAndApply();
        });
        binding.pitchSlider.addOnChangeListener((slider, value, fromUser) -> {
            config.setPitch(Sliders.snapAndPushBack(slider, value));
            updateValueLabels();
            persistAndApply();
        });
        binding.volumeSlider.addOnChangeListener((slider, value, fromUser) -> {
            config.setVolume(Sliders.snapAndPushBack(slider, value));
            updateValueLabels();
            persistAndApply();
        });
    }

    /**
     * 把 config 里的三个值搬进滑杆。
     * <p>
     * 值先按滑杆自己的步进栅格收敛再 {@code setValue()}——存下来的值可能不在栅格上
     * （例如拖拽留下的 0.92727274，见 {@link Sliders}），那会让 setValue 在首次布局时抛
     * {@code IllegalStateException}，而那个崩点跟这行代码隔着好几层布局回调。
     * 同时写回 config：界面上显示不出来的值不该继续留在配置里，否则标签、滑杆、
     * 引擎三边对不上（{@code persist()} 只在真的修过东西时才写盘）。
     */
    private void applyConfigToSliders() {
        float rate = Sliders.snap(binding.rateSlider, config.getRate());
        float pitch = Sliders.snap(binding.pitchSlider, config.getPitch());
        float volume = Sliders.snap(binding.volumeSlider, config.getVolume());
        boolean healed = rate != config.getRate()
                || pitch != config.getPitch()
                || volume != config.getVolume();

        config.setRate(rate);
        config.setPitch(pitch);
        config.setVolume(volume);
        binding.rateSlider.setValue(config.getRate());
        binding.pitchSlider.setValue(config.getPitch());
        binding.volumeSlider.setValue(config.getVolume());
        updateValueLabels();

        if (healed) {
            persist();
        }
    }

    private void updateValueLabels() {
        binding.rateValue.setText(String.format(Locale.CHINA,
                getString(R.string.tts_ratio_value), config.getRate()));
        binding.pitchValue.setText(String.format(Locale.CHINA,
                getString(R.string.tts_ratio_value), config.getPitch()));
        binding.volumeValue.setText(String.format(Locale.CHINA,
                getString(R.string.tts_percent_value), Math.round(config.getVolume() * 100)));
    }

    // ------------------------------------------------------------------ 操作

    private void setupActions() {
        binding.testButton.setOnClickListener(v -> {
            if (testing) {
                Speaker.get().stop();
                setTesting(false);
            } else {
                startPreview();
            }
        });

        binding.resetButton.setOnClickListener(v -> {
            config = TtsConfig.defaults();
            persist();
            Speaker.get().apply(config);
            applyConfigToUi();
            Toast.makeText(this, R.string.tts_reset_done, Toast.LENGTH_SHORT).show();
        });
    }

    private void applyConfigToUi() {
        applyConfigToSliders();
        refreshEngineState();
        reloadVoices();
    }

    private void startPreview() {
        Speaker.get().apply(config);
        setTesting(true);
        Speaker.get().speak(getString(R.string.tts_preview_text));
    }

    private void setTesting(boolean value) {
        testing = value;
        binding.testButton.setText(value ? R.string.tts_stop : R.string.tts_test);
    }

    private void persistAndApply() {
        persist();
        Speaker.get().apply(config);
    }

    private void persist() {
        store.setTtsConfig(config);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Speaker.get().stop();
        Speaker.get().setListener(null);
        // 也得撤掉：目录是在飞的回调，留着它就会往一个已经销毁的页面上画
        // （Speaker 是单例，它比任何一个页面活得久）
        Speaker.get().setVoiceListListener(null);
        binding = null;
    }
    // ------------------------------------------------------------------ 会员音色权限

    /**
     * 会员这一行只<b>显示</b>，不给开关。
     * <p>
     * 权益由服务端给（{@code user.level + vip_expire_at}，见 {@code GET /v1/user/profile}），
     * 本机改一个布尔值换不来一次成功的合成——真点下去照样被 403 顶回来，只是把失败推迟到
     * 用户按下朗读的那一刻。所以这里只做两件事：说清楚现在有没有、以及没有的时候该去哪。
     * <p>
     * 从前的本地测试开关已经去掉（见 {@link Member} 的类注释）：它写在客户端，与
     * 「会员是付费能力」这件事直接矛盾，留着迟早会被当成真的权益路径。
     */
    private void setupMember() {
        updateMember();
        binding.memberAction.setOnClickListener(v -> onMemberAction());
    }

    /** 这一行整体重画一次（状态文字 + 右侧动作），两个更新点永远成对调用。 */
    private void updateMember() {
        updateMemberStatus();
        updateMemberAction();
    }

    /**
     * 会员状态的三种写法。判据一律是 {@link UserStore#isMember()}（已折算了到期时间），
     * 未登录单独一支——「没登录」和「登录了但没开通」指向的下一步动作完全不同：
     * 前者去登录，后者只能去服务端配权益。
     */
    private void updateMemberStatus() {
        if (!store.isLoggedIn()) {
            binding.memberStatus.setText(R.string.tts_member_signed_out);
            return;
        }
        Member member = store.getMember();
        if (!member.isActive()) {
            binding.memberStatus.setText(R.string.tts_member_off);
            return;
        }
        binding.memberStatus.setText(member.getExpireAt() > 0L
                ? getString(R.string.tts_member_expire, formatDate(member.getExpireAt()))
                : getString(R.string.tts_member_lifetime));
    }

    /**
     * 右侧那一个动作。已经是会员时整块隐藏——没有「续费」可点（还没有付费渠道），
     * 留一个按钮在那儿只会让人以为点得动。
     */
    private void updateMemberAction() {
        if (!store.isLoggedIn()) {
            binding.memberAction.setText(R.string.tts_member_login);
            binding.memberAction.setVisibility(View.VISIBLE);
        } else if (!store.isMember()) {
            binding.memberAction.setText(R.string.tts_member_learn);
            binding.memberAction.setVisibility(View.VISIBLE);
        } else {
            binding.memberAction.setVisibility(View.GONE);
        }
    }

    private void onMemberAction() {
        if (!store.isLoggedIn()) {
            // 登录页返回后 onResume 会重取资料与音色目录，这一行自己就更新了
            startActivity(new Intent(this, LoginActivity.class));
            return;
        }
        showMemberRequired();
    }

    /** 到期时间按本地时区显示到日；用户只关心「还能用到哪天」 */
    @NonNull
    private static String formatDate(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(millis));
    }

    /** 云端音色要会员：说明权益从哪来，不真正切过去（锁着的那一行也点不进来，兜底用）。 */
    private void showMemberRequired() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.voice_member_title)
                .setMessage(R.string.voice_member_msg)
                .setPositiveButton(R.string.voice_member_positive, null)
                .show();
    }

}

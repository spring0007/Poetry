package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.poetry.ui.Skin;
import com.example.poetry.adapter.VoiceAdapter;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Member;
import com.example.poetry.data.model.Voice;
import com.example.poetry.databinding.ActivityVoiceSettingsBinding;
import com.example.poetry.media.QCloudRoles;
import com.example.poetry.media.Speaker;
import androidx.appcompat.app.AlertDialog;
import com.example.poetry.util.Sliders;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
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

        Speaker.get().prepare(this, this::onEngineReady);
        refreshEngineState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 引擎可能是离开这段时间里加载完的（首启时加载要好几秒），回来再对一次状态
        Speaker.get().prepare(this, this::onEngineReady);
        refreshEngineState();
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
            if (QCloudRoles.isCloudId(stored)) {
                // 存的是云端音色、当下又用不上（会员没开，或云端暂时没配好）：引擎已经临时
                // 改用内置音色了。但这个替换**不能写回配置**——云端音色是用户自己挑的，
                // 会员开了 / 云端回来了就该自动切回去；写回去等于把他的选择永久抹掉，
                // 而且是在他毫不知情的情况下。这里只说明「这次换了、你的选择还在」。
                noteCloudSuspended(stored);
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
        if (QCloudRoles.isCloudId(stored)) {
            noteCloudSuspended(stored);
        }
    }

    /**
     * 音色 id 的显示名。
     * <p>
     * 云端音色不一定在 {@link #voices} 里——云端没配好时 {@code EngineRouter} 根本不列它们，
     * 而「被顶替的正好是云端音色」正是那种时候会发生的，所以还要按 {@link QCloudRoles} 反查。
     * 都查不到就把 id 原样显示，总好过留一个空名字。
     */
    @NonNull
    private String voiceName(@NonNull String id) {
        for (Voice voice : voices) {
            if (id.equals(voice.getId())) {
                return voice.getName();
            }
        }
        Integer voiceType = QCloudRoles.voiceTypeOf(id);
        QCloudRoles.Role role = voiceType == null ? null : QCloudRoles.forVoiceType(voiceType);
        return role == null ? id : getString(role.nameRes);
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
        binding = null;
    }
    // ------------------------------------------------------------------ 会员音色权限（测试期）

    private void setupMember() {
        SwitchMaterial sw = binding.memberSwitch;
        sw.setChecked(store.isMember());
        updateMemberStatus();
        sw.setOnCheckedChangeListener((button, checked) -> {
            // 测试期开关：开 = 体验会员（不过期），关 = 回到未开通，云端音色随即锁定
            store.setMember(checked
                    ? Member.vip(0L, Member.SOURCE_TEST)
                    : Member.free());
            updateMemberStatus();
            // 会员状态变了，「这次为什么用不上云端音色」的说明要能重新出现一次
            cloudSuspendedNotified = false;
            reloadVoices();
            refreshEngineState();
            if (!checked) {
                // 上面的 reloadVoices() 已经能认出替换了（它直接问引擎），这里补一下是因为
                // 引擎整个没起来时（语音包缺失）谁都报不出「改用哪个音色了」，勾和说明会一起落空。
                // 同一条说明每个页面只弹一次，多调一次是安全的（见 noteCloudSuspended）。
                noteCloudSuspendedIfNeeded();
            }
        });
    }

    private void updateMemberStatus() {
        binding.memberStatus.setText(store.isMember()
                ? R.string.tts_member_on : R.string.tts_member_off);
    }

    /** 选了被锁的云端音色：说明会员限制，不真正切过去。 */
    private void showMemberRequired() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.voice_member_title)
                .setMessage(R.string.voice_member_msg)
                .setPositiveButton(R.string.voice_member_positive, null)
                .show();
    }

}

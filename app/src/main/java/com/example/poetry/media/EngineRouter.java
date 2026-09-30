package com.example.poetry.media;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.R;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.Member;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Voice;

import java.util.ArrayList;
import java.util.List;

/**
 * 朗读引擎的路由器：把内置离线引擎 {@link SherpaTts} 和腾讯云在线引擎 {@link QCloudTts}
 * 归一成 {@link SpeechEngine} 暴露给上层。UI 只认 {@link SpeechEngine}，不关心一句诗
 * 到底是本机合成还是云端合成的。
 * <p>
 * 两个职责：
 * <ul>
 *   <li><b>合并音色列表</b>：离线音色在前，云端音色在后；云端音色只有在<i>配好密钥</i>
 *   且<i>当前是会员</i>时才放进列表里供选择，否则标 {@code locked}（界面上灰掉 + 锁标）。</li>
 *   <li><b>按音色归属分发</b>：{@code speak} 看当前选中的 id 是不是 {@code qcloud-*} 决定走哪条路，
 *   没会员却选了云端音色（理论上界面已经拦住）就退回离线引擎，绝不会把付费能力放出去。
 *   这一条判据（{@link #useCloud}）同时也是 {@link #currentVoiceId} 的判据——界面报出来的
 *   音色和真正发声的音色由此永远一致。</li>
 * </ul>
 */
public final class EngineRouter implements SpeechEngine {

    private static final EngineRouter INSTANCE = new EngineRouter();

    public static EngineRouter get() {
        return INSTANCE;
    }

    private final SherpaTts offline = SherpaTts.get();
    private final QCloudTts cloud = QCloudTts.get();

    @Nullable
    private Context appContext;

    /** 选中音色的意图（UI 选择状态的来源之一）。 */
    private volatile String selectedVoiceId = "";

    @Nullable
    private Listener downstream;

    /** 把子引擎的回调收口到上层设进来的 listener。 */
    private final Listener forward = new Listener() {
        @Override
        public void onStart() {
            if (downstream != null) {
                downstream.onStart();
            }
        }

        @Override
        public void onDone() {
            if (downstream != null) {
                downstream.onDone();
            }
        }

        @Override
        public void onError(String message) {
            if (downstream != null) {
                downstream.onError(message);
            }
        }

        @Override
        public void onNotice(String message) {
            if (downstream != null) {
                downstream.onNotice(message);
            }
        }
    };

    private EngineRouter() {
    }

    /** 测试期默认按「已开通会员」处理；没拿到 Context 的窗口也按这个默认，避免音色闪没。 */
    private boolean memberActive() {
        if (appContext == null) {
            return Member.GRANT_BY_DEFAULT_FOR_TEST;
        }
        return UserStore.get(appContext).isMember();
    }

    /**
     * 当前选中的是不是「配好密钥 + 会员」都齐的云端音色。
     * <p>
     * 这是「这声音归谁发」的唯一判据：{@link #speak}、{@link #usingCloud}、
     * {@link #currentVoiceId} 三处都用它。少任何一处同口径，界面说的和实际响的就会分家。
     */
    private boolean useCloud() {
        return QCloudRoles.isCloudId(selectedVoiceId)
                && cloud.isConfigured()
                && memberActive();
    }

    @Override
    public void prepare(@NonNull Context context, @Nullable Runnable onReady) {
        appContext = context.getApplicationContext();
        offline.setListener(forward);
        cloud.setListener(forward);
        // 云端一个字都没念出来就失败时，由内置引擎接手把这段读完（见 QCloudTts#fallbackToOffline）
        cloud.setFallback(offline);
        // 离线引擎先就绪；云端初始化很快，跟在后面，最后统一回调上层。
        offline.prepare(context, () -> cloud.prepare(context, onReady));
    }

    @Override
    public boolean isReady() {
        return offline.isReady() || cloud.isReady();
    }

    @NonNull
    @Override
    public String statusText(@NonNull Context context) {
        String base = offline.statusText(context);
        if (!cloud.isConfigured()) {
            return base;
        }
        if (!memberActive()) {
            return context.getString(R.string.tts_engine_join, base,
                    context.getString(R.string.tts_engine_online_need_member));
        }
        return context.getString(R.string.tts_engine_join, base, cloud.statusText(context));
    }

    @Override
    public void setListener(@Nullable Listener listener) {
        this.downstream = listener;
    }

    @NonNull
    @Override
    public List<Voice> listVoices() {
        List<Voice> result = new ArrayList<>(offline.listVoices());
        Context ctx = appContext;
        if (ctx != null && cloud.isConfigured()) {
            boolean member = memberActive();
            String currentId = currentVoiceId();
            for (QCloudRoles.Role role : QCloudRoles.ALL) {
                Voice voice = new Voice();
                voice.setId(QCloudRoles.voiceId(role.voiceType));
                voice.setName(ctx.getString(role.nameRes));
                voice.setDesc(ctx.getString(role.descRes));
                voice.setLocale("zh-CN");
                voice.setTag1(ctx.getString(R.string.voice_tag_online));
                voice.setTag2(role.male
                        ? ctx.getString(R.string.tts_tag_male)
                        : ctx.getString(R.string.tts_tag_female));
                voice.setCloud(true);
                voice.setLocked(!member);
                voice.setSelected(voice.getId().equals(currentId));
                result.add(voice);
            }
        }
        return result;
    }

    @Override
    public boolean setVoice(@NonNull String voiceId) {
        if (QCloudRoles.isCloudId(voiceId)) {
            // 界面上的云端音色已经按会员状态灰掉了，但这里是最后一道闸：
            // 没配密钥或者不是会员，就别把云端音色记成「当前选中」。
            // 记下来的话 currentVoiceId() 会把它当成有效选择兜一圈再退回离线，
            // 试听还会白跑一次网络请求才报错。
            if (!cloud.isConfigured() || !memberActive()) {
                return false;
            }
            selectedVoiceId = voiceId;
            return cloud.setVoice(voiceId);
        }
        selectedVoiceId = voiceId;
        return offline.setVoice(voiceId);
    }

    /**
     * 真正会发声的音色 id——界面上的打勾、发音人的名字都以它为准。
     * <p>
     * 判据必须和 {@link #useCloud()} 完全一样：{@code speak()} 按那个判据决定这句交给谁，
     * 这里若按另一套判据报名字，就会出现「界面写着云端音色、耳朵里是内置引擎」。差一点点
     * 都会出事——只按会员状态判断的话，密钥根本没配好（打包时没注入、或初始化失败）时
     * {@code selectedVoiceId} 里存着的云端 id 一辈子用不上，却会被一直报成当前音色。
     */
    @Nullable
    @Override
    public String currentVoiceId() {
        return useCloud() ? selectedVoiceId : offline.currentVoiceId();
    }

    @Override
    public void apply(@NonNull TtsConfig config) {
        String id = config.getVoiceId();
        if (id != null && !id.isEmpty()) {
            selectedVoiceId = id;
        }
        offline.apply(config);
        cloud.apply(config);
    }

    @Override
    public void speak(@NonNull String text) {
        if (useCloud()) {
            cloud.speak(text);
        } else {
            offline.speak(text);
        }
    }

    @Override
    public boolean usingCloud() {
        return useCloud();
    }

    @Override
    public void stop() {
        offline.stop();
        cloud.stop();
    }

    @Override
    public boolean isSpeaking() {
        return offline.isSpeaking() || cloud.isSpeaking();
    }

    @Override
    public void shutdown() {
        offline.shutdown();
        cloud.shutdown();
    }
}

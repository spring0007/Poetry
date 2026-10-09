package com.example.poetry.media;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Voice;

import java.util.ArrayList;
import java.util.List;

/**
 * 朗读引擎的路由器：把若干个子引擎归一成一个，交给上层。
 *
 * <p>眼下只有一个子引擎 {@link SherpaTts}——sherpa-onnx + 随 APK 分发的中文 VITS
 * 语音包，整条链路跑在本机，不联网、也不依赖任何第三方 App。这一层留着是为了保住那道缝：
 * {@code Speaker} 与所有界面只认 {@link SpeechEngine}，将来要接「服务端云端音色」时，
 * 在这里挂一个子引擎、把 {@link #listVoices()} 与 {@link #speak} 按音色归属分发即可，
 * UI 一行都不用改。
 *
 * <h3>为什么这里没有腾讯云直连</h3>
 *
 * <p>早先这一层是「离线 {@code SherpaTts} + 腾讯云直连」的双引擎。直连必须把主账号的
 * {@code SecretId} / {@code SecretKey} 编译进 APK，而 {@code BuildConfig} 的字符串常量
 * 就是明文躺在 {@code classes.dex} 里的——实测反编译能直接搜出密钥原文，等于把账号交出去。
 * 所以直连、{@code QCloudTts} 与 {@code libqcloudtts} 一并撤掉了。
 *
 * <p>云端音色将来由<b>服务端代理</b>提供（{@code POST /v1/tts/synthesize}，凭证留在服务端）。
 * 那条路是按「作品引用」合成整首，和现在的「逐句文本」朗读不是同一个形态，
 * 所以届时这里是多挂一个子引擎，而不是把老代码接回来。
 *
 * <p>{@link #usingCloud()} 因此在可预见的将来恒为 false —— 它是
 * {@link SpeechEngine} 的默认实现，等真的接上服务端时再覆写。
 */
public final class EngineRouter implements SpeechEngine {

    private static final EngineRouter INSTANCE = new EngineRouter();

    public static EngineRouter get() {
        return INSTANCE;
    }

    private final SherpaTts offline = SherpaTts.get();

    @Nullable
    private Listener downstream;

    /** 把子引擎的回调收口到上层设进来的那一个 listener。 */
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

    @Override
    public void prepare(@NonNull Context context, @Nullable Runnable onReady) {
        offline.setListener(forward);
        offline.prepare(context, onReady);
    }

    @Override
    public boolean isReady() {
        return offline.isReady();
    }

    @NonNull
    @Override
    public String statusText(@NonNull Context context) {
        return offline.statusText(context);
    }

    @Override
    public void setListener(@Nullable Listener listener) {
        this.downstream = listener;
    }

    @NonNull
    @Override
    public List<Voice> listVoices() {
        return new ArrayList<>(offline.listVoices());
    }

    @Override
    public boolean setVoice(@NonNull String voiceId) {
        return offline.setVoice(voiceId);
    }

    @Nullable
    @Override
    public String currentVoiceId() {
        return offline.currentVoiceId();
    }

    @Override
    public void apply(@NonNull TtsConfig config) {
        offline.apply(config);
    }

    @Override
    public void speak(@NonNull String text) {
        offline.speak(text);
    }

    @Override
    public void stop() {
        offline.stop();
    }

    @Override
    public boolean isSpeaking() {
        return offline.isSpeaking();
    }

    @Override
    public void shutdown() {
        offline.shutdown();
    }
}

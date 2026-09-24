package com.example.poetry.media;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Voice;

import java.util.List;

/**
 * 语音合成引擎的统一抽象。
 * <p>
 * 眼下只有一个实现：{@link SherpaTts}，sherpa-onnx + 随 APK 分发的中文 VITS 模型，
 * 整条链路跑在本机，不联网、不依赖任何第三方 App。保留这层接口是因为它在跑过七个
 * 后端之后（见 {@code TTS.md} 的演进表）证明是值得的——换引擎不必动 UI。
 * <p>
 * 一个引擎可以带**多个语音包**，每个语音包里有多个发音人；{@link #listVoices()} 把
 * 它们摊平成一张音色表交给界面，界面不需要知道音色属于哪个包。
 * 上层（详情页朗读条、音色弹层、语音设置页）只依赖本接口，
 * 状态文案由 {@link #statusText} 自己如实描述。
 */
public interface SpeechEngine {

    /** 播放状态回调 */
    interface Listener {
        void onStart();

        void onDone();

        void onError(String message);
    }

    /** 引擎是否可用（模型已加载且至少有一个音色） */
    boolean isReady();

    /** 不可用时给用户的说明 */
    @NonNull
    String statusText(@NonNull Context context);

    void setListener(@Nullable Listener listener);

    /** 可选音色列表；引擎不可用时返回空列表，由 UI 决定怎么引导用户 */
    @NonNull
    List<Voice> listVoices();

    /** 切换发音人 */
    boolean setVoice(@NonNull String voiceId);

    @Nullable
    String currentVoiceId();

    /** 应用完整配置（发音人 / 语速 / 音调 / 音量） */
    void apply(@NonNull TtsConfig config);

    void speak(@NonNull String text);

    void stop();

    boolean isSpeaking();

    void shutdown();
}

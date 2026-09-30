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

        /**
         * 一句提醒：这次朗读没按预想的方式发生，但它<b>还活着</b>——例如云端音色顶不上，
         * 已经自动换成本机音色接着读了。
         * <p>
         * 和 {@link #onError} 的分工是：「这次朗读结束了没有」。{@code onError} 之后
         * 上层要收尾（复位播放按钮、停进度条），{@code onNotice} 之后什么都不用做，
         * 把话转给用户就行——错当成错误收尾会把正在读的这一段掐掉。
         * <p>
         * 默认空实现：只有可能「换了种方式继续」的引擎才需要用到它。
         */
        default void onNotice(String message) {
        }
    }


    /**
     * 加载/初始化引擎；onReady 在状态确定后回主线程调用（可能已经在加载途中，幂等）。
     * 子类自己决定要不要异步——{@link com.example.poetry.media.SherpaTts} 会异步加载模型，
     * 腾讯云引擎几乎是即时的（只做一次鉴权配置）。
     */
    void prepare(@NonNull Context context, @Nullable Runnable onReady);
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

    /**
     * 下一句会不会走云端（也就是「要联网、有一次往返延迟」）。
     * <p>
     * 给上层估时长用的：云端合成的时间开销和本机不是一回事，进度条得按各自的
     * 节奏走，详见 {@link Speaker#estimateDurationMs(String, float)}。
     * 默认 false——绝大多数引擎是本机的。
     */
    default boolean usingCloud() {
        return false;
    }

    void shutdown();
}

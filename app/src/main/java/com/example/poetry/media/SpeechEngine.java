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
 * 两个实现：{@link SherpaTts}（sherpa-onnx + 随 APK 分发的中文 VITS 模型，整条链路跑在
 * 本机，不联网、不依赖任何第三方 App）和 {@link CloudTts}（服务端代理的腾讯云音色，
 * 要联网、要登录、要会员）。两者由 {@link EngineRouter} 合成一个交给上层。
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

    /**
     * 音色表（可能）变了——拉到了新的目录，或者确认这一次拉不到。
     * <p>
     * 默认空实现：音色表是本机枚举出来的引擎（{@link SherpaTts}）不存在这个问题。
     * 云端那份要走网络，界面已经画完了它才到是常态，所以需要一次「表变了」的通知，
     * 而不是让界面去轮询。回调在主线程。
     */
    default void setVoiceListListener(@Nullable Runnable listener) {
    }

    /**
     * 重新取一次音色表。
     * <p>
     * 登录态变了（音色的锁定态跟着变）时值得重取一次；默认什么都不做——本机的表一直在那儿。
     */
    default void refreshVoices() {
    }

    /**
     * 音色表是不是已经落定（拉回来了，或者确认拉不到）。
     * <p>
     * 默认 true：本机枚举出来的表一直在那儿，不存在「还没到齐」。云端那份在目录到达之前
     * 只列得出离线音色，界面据此判断「眼下看到的这张表全不全」——不全就先别下结论
     * （例如「你存的云端音色用不上了」这种话，等目录到了再说）。
     */
    default boolean isVoiceListSettled() {
        return true;
    }

    /** 切换发音人 */
    boolean setVoice(@NonNull String voiceId);

    @Nullable
    String currentVoiceId();

    /** 应用完整配置（发音人 / 语速 / 音调 / 音量） */
    void apply(@NonNull TtsConfig config);

    /**
     * 读一段文本，和这首作品没有关系（试听、逐句朗读）。
     */
    void speak(@NonNull String text);

    /**
     * 读一个作品：除了文本，还带上「这是哪一首」的引用。
     * <p>
     * 绝大多数引擎只要文本，所以默认实现直接转给 {@link #speak(String)}。分出来是因为
     * 云端那条路是<b>按作品合成</b>的：服务端的 {@code POST /v1/tts/synthesize} 收的是
     * 「作品引用 + 音色 + 语速」，一次给回整首的音频，光有一段文本它无处下手。
     * <p>
     * 之所以让引用跟着这一次调用走，而不是先用 {@code setCurrentWork(ref)} 记下「当前作品」
     * 再 {@code speak(text)}：同一个界面里还有试听，读的是一句固定的话、不是这首作品。
     * 把「当前作品」挂在引擎上，那一瞬间的状态分不清「在读正文」和「在读试听句」，
     * 试听就会拿整首去合成。引用跟着调用走，没有这个歧义。
     *
     * @param workRef 作品引用，交给服务端认人（见 {@code Poem#getLookupRef()}）
     * @param text    正文，兜底时仍要用它从本机读出来
     */
    default void speakWork(@NonNull String workRef, @NonNull String text) {
        speak(text);
    }

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

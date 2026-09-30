package com.example.poetry.media;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.example.poetry.BuildConfig;
import com.example.poetry.R;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Voice;
import com.example.poetry.util.LogUtil;
import com.tencent.cloud.libqcloudtts.MediaPlayer.QCloudMediaPlayer;
import com.tencent.cloud.libqcloudtts.MediaPlayer.QCloudPlayerCallback;
import com.tencent.cloud.libqcloudtts.MediaPlayer.QPlayerError;
import com.tencent.cloud.libqcloudtts.TtsController;
import com.tencent.cloud.libqcloudtts.TtsError;
import com.tencent.cloud.libqcloudtts.TtsMode;
import com.tencent.cloud.libqcloudtts.TtsResultListener;
import com.tencent.cloud.libqcloudtts.TtsServiceError;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 腾讯云在线语音合成引擎。
 * <p>
 * 整条链路走云端 API，本地不保留语音模型：调 {@link TtsController#synthesize(String)} 把诗句
 * 推上去，音频（MP3 字节流）通过 {@link TtsResultListener#onSynthesizeData} 一段段回来，
 * 再交给 {@link QCloudMediaPlayer} 边收边播。所以「试听 / 朗读」会有一次网络往返的延迟，
 * 这是在线引擎绕不开的——界面上的「合成中…」就是为它准备的。
 * <p>
 * 密钥从 {@code local.properties} 经 {@code BuildConfig} 注入（见 {@code build.gradle.kts}），
 * 不进版本库。没填密钥时本引擎当「不可用」处理，界面不会把云端音色放出来，离线朗读照常。
 */
public final class QCloudTts implements SpeechEngine {

    private static final QCloudTts INSTANCE = new QCloudTts();

    public static QCloudTts get() {
        return INSTANCE;
    }

    /** 密钥是否就绪：{@code local.properties} 里填了三件套才进 true。 */
    private volatile boolean configured = false;

    @Nullable
    private Context appContext;

    /** 边收边播的播放器：{@link #prepare} 建一次，{@link #shutdown} 放掉。 */
    @Nullable
    private volatile QCloudMediaPlayer player;

    /**
     * 当前要用的腾讯音色 ID（{@code 101xxx} / {@code 501xxx}）。
     * {@link #apply} 和 {@link #setVoice} 都写它，播放回调在别的线程上读，所以是 volatile。
     */
    private volatile int voiceType = -1;

    /** 腾讯的 {@code Speed} 参数——注意它不是「倍率」，见 {@link #speedParameter}。 */
    private volatile float speed = 0f;
    private volatile float volume = 9.0f;

    private final AtomicBoolean speaking = new AtomicBoolean(false);

    /**
     * {@link #speak} 主动掐掉上一轮时置位。
     * <p>
     * {@code StopPlay()} 会在<b>调用线程上同步</b>回调 {@code onTTSPlayStop}（见 AAR 反编译），
     * 而那次「已停止」的含义是「被顶掉了」，不是「读完了」——上层如果把它当读完，
     * 进度条会跳到 100，开着连续朗读还会直接翻到下一篇。
     */
    private final AtomicBoolean suppressStop = new AtomicBoolean(false);

    @Nullable
    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * 云端顶不上时接手的内置引擎（{@link EngineRouter} 在 {@code prepare} 里接上）。
     * 没有它就只能在「静音」和「一句看不懂的错误」之间二选一——见 {@link #fallbackToOffline}。
     */
    @Nullable
    private volatile SpeechEngine fallback;

    /** 这一轮要读的文本：失败回退时要把它原样再交给内置引擎。 */
    private volatile String pendingText = "";

    /**
     * 这一轮有没有收到过音频。一次都没收到就失败，说明用户一个字都还没听见——
     * 这是判断该不该回退的依据：读到一半断掉再换嗓子，比停在那儿更让人困惑。
     */
    private final AtomicBoolean heardAudio = new AtomicBoolean(false);

    /**
     * 上一块入队过的音频，用来挡掉同一次回调的重复投递——见 {@link #enqueue}。
     * 留强引用是故意的：挡重靠的是数组身份，被回收掉就挡不住了。
     */
    @Nullable
    private volatile byte[] lastChunk;

    private QCloudTts() {
    }

    /** 密钥是否就绪——没配好时云端音色不会出现在列表里。 */
    public boolean isConfigured() {
        return configured;
    }

    /** 接上内置引擎：云端发不出声音时由它接手接着读。 */
    public void setFallback(@Nullable SpeechEngine engine) {
        this.fallback = engine;
    }

    @Override
    public void prepare(@NonNull Context context, @Nullable Runnable onReady) {
        Context app = context.getApplicationContext();
        appContext = app;
        if (configured && player != null) {
            // 已经起来了。TtsController 是单例，QCloudMediaPlayer 又没有 release 一类的
            // 出口（见 AAR：只有 StopPlay/PausePlay/ResumePlay），所以再 init 一次只会把
            // 上一只播放器连同它占着的 MediaPlayer 丢掉。而语音设置页每次 onResume、
            // 详情页每次打开都会走到这里，不幂等就是一路漏下去。
            if (onReady != null) {
                onReady.run();
            }
            return;
        }
        String appId = BuildConfig.QCLOUD_APP_ID;
        String secretId = BuildConfig.QCLOUD_SECRET_ID;
        String secretKey = BuildConfig.QCLOUD_SECRET_KEY;
        boolean ok = appId != null && !appId.isEmpty()
                && secretId != null && !secretId.isEmpty()
                && secretKey != null && !secretKey.isEmpty();
        if (!ok) {
            configured = false;
            if (onReady != null) {
                onReady.run();
            }
            return;
        }
        try {
            TtsController.getInstance().init(app, TtsMode.ONLINE, resultListener());
            TtsController.getInstance().setAppId(Long.valueOf(appId.trim()));
            TtsController.getInstance().setSecretId(secretId.trim());
            TtsController.getInstance().setSecretKey(secretKey.trim());
            TtsController.getInstance().setOnlineCodec("mp3");
            player = new QCloudMediaPlayer(playerCallback());
            configured = true;
        } catch (Throwable error) {
            LogUtil.w("腾讯云 TTS 初始化失败", error);
            // player 可能已经建出来了（下面那步失败），不能留着：configured 为 false
            // 时下一次 prepare 会重来，届时旧的那只就没人引用了
            player = null;
            configured = false;
        }
        if (onReady != null) {
            onReady.run();
        }
    }

    @Override
    public boolean isReady() {
        return configured;
    }

    @NonNull
    @Override
    public String statusText(@NonNull Context context) {
        if (!configured) {
            return context.getString(R.string.tts_engine_online_unauth);
        }
        return context.getString(R.string.tts_engine_online_ready, QCloudRoles.ALL.length);
    }

    @Override
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /** 本引擎不直接产出音色列表——云端音色由 {@link EngineRouter} 根据密钥和会员状态拼。 */
    @NonNull
    @Override
    public List<Voice> listVoices() {
        return new ArrayList<>();
    }

    /**
     * 记下选中的音色。
     * <p>
     * 这里必须<b>真的把 voiceType 落下来</b>，不能只校验一下 id：音色弹层和「我的」页
     * 点「试听」时走的是「{@code Speaker.setVoice(id)} → {@code speak()}」这条路，
     * 中间没有 {@code apply()}（只有语音设置页和详情页正文朗读才会 apply）。
     * 只校验不赋值的话 {@link #speak} 会撞上 {@code voiceType < 0} 那道闸，
     * 云端音色每次试听都只得到一句「暂不可用」。
     */
    @Override
    public boolean setVoice(@NonNull String voiceId) {
        Integer type = QCloudRoles.voiceTypeOf(voiceId);
        if (type == null) {
            return false;
        }
        voiceType = type;
        return true;
    }

    @Nullable
    @Override
    public String currentVoiceId() {
        return voiceType >= 0 ? QCloudRoles.voiceId(voiceType) : null;
    }

    @Override
    public void apply(@NonNull TtsConfig config) {
        Integer type = QCloudRoles.voiceTypeOf(config.getVoiceId());
        voiceType = type != null ? type : -1;
        speed = speedParameter(config.getRate());
        // 腾讯在线音量是 0–10，App 内部是 0–1，换算一下。
        volume = Math.max(0f, Math.min(10f, config.getVolume() * 10f));
    }

    /**
     * 腾讯 {@code Speed} 参数的锚点：{@code {倍率, Speed}}，倍率递增。
     * <p>
     * 取自官方文档的速度对照表——{@code -2→0.6}、{@code -1→0.8}、{@code 0→1.0}、
     * {@code 1→1.2}、{@code 2→1.5}、{@code 6→2.5}；中间的倍率按线性插值取。
     */
    private static final float[][] SPEED_ANCHORS = {
            {0.6f, -2f},
            {0.8f, -1f},
            {1.0f, 0f},
            {1.2f, 1f},
            {1.5f, 2f},
            {2.5f, 6f},
    };

    /**
     * App 的语速倍率 → 腾讯的 {@code Speed} 参数。
     * <p>
     * 两边的刻度不一样：App 用倍率（0.6–1.8，见 {@link TtsConfig}），腾讯这一头
     * {@code 0} 才是正常语速。{@code TtsController.setOnlineVoiceSpeed()} 只是把值原样
     * 塞进请求的 {@code Speed} 字段（见 AAR 反编译），所以直接传倍率的话，默认的 1.0
     * 会被服务端读成 1.2 倍——用户听到的比设置里写的快，而且 1.0 以下几乎调不动。
     */
    static float speedParameter(float rate) {
        float[] lowest = SPEED_ANCHORS[0];
        if (rate <= lowest[0]) {
            return lowest[1];
        }
        for (int i = 1; i < SPEED_ANCHORS.length; i++) {
            float[] high = SPEED_ANCHORS[i];
            if (rate <= high[0]) {
                float[] low = SPEED_ANCHORS[i - 1];
                float ratio = (rate - low[0]) / (high[0] - low[0]);
                return low[1] + ratio * (high[1] - low[1]);
            }
        }
        return SPEED_ANCHORS[SPEED_ANCHORS.length - 1][1];
    }

    @Override
    public void speak(@NonNull String text) {
        pendingText = text;
        heardAudio.set(false);
        if (!configured || player == null || voiceType < 0) {
            if (!fallbackToOffline()) {
                dispatchError(string(R.string.tts_cloud_no_voice));
            }
            return;
        }
        QCloudMediaPlayer p = player;
        if (speaking.get()) {
            // 上一轮还没读完就被新的一段顶掉了，先把它掐掉。否则旧音频会和新音频
            // 接在同一个播放器队列里，用户听到的是「上句尾巴 + 这句」。
            // StopPlay() 会在调用线程上同步回调 onTTSPlayStop（见 AAR 反编译），
            // 那一下的含义是「被顶掉了」而不是「读完了」，用 suppressStop 挡掉。
            suppressStop.set(true);
            try {
                p.StopPlay();
            } catch (Throwable ignored) {
                // 播放器已释放
            } finally {
                suppressStop.set(false);
            }
            // 兜底：万一回调是异步派发过来的，那时 suppressStop 已经放掉了，
            // 但 speaking 此时是 false，compareAndSet 同样不会误报「读完」。
            speaking.set(false);
        }
        try {
            TtsController.getInstance().setOnlineVoiceType(voiceType);
            TtsController.getInstance().setOnlineVoiceSpeed(speed);
            TtsController.getInstance().setOnlineVoiceVolume(volume);
            TtsError error = TtsController.getInstance().synthesize(text);
            if (error != null) {
                logError("合成请求失败", error, null, null);
                if (!fallbackToOffline()) {
                    dispatchError(string(R.string.tts_cloud_synth_failed, describe(error)));
                }
                return;
            }
            speaking.set(true);
            dispatchStart();
        } catch (Throwable e) {
            dispatchError(string(R.string.tts_cloud_synth_error, describe(e.getMessage())));
        }
    }

    @Override
    public void stop() {
        speaking.set(false);
        // 用户主动停了：之后迟到的失败不该再触发回退，否则「停下来」之后声音还会自己响起来
        pendingText = "";
        QCloudMediaPlayer p = player;
        if (p != null) {
            try {
                p.StopPlay();
            } catch (Throwable ignored) {
                // 播放器已释放
            }
        }
    }

    @Override
    public boolean isSpeaking() {
        return speaking.get();
    }

    @Override
    public void shutdown() {
        stop();
        try {
            TtsController.release();
        } catch (Throwable ignored) {
            // ignore
        }
        // QCloudMediaPlayer 没有 release 一类的出口，只能丢掉引用让它随 GC 走；
        // 关键是别把它留着——留着的话下一次 prepare 的幂等判断会认为「已经起来了」，
        // 但 TtsController 已经被 release，之后每次合成都会失败。
        player = null;
        voiceType = -1;
        configured = false;
    }

    // ------------------------------------------------------------------ 回调

    @NonNull
    private TtsResultListener resultListener() {
        return new TtsResultListener() {
            @Override
            public void onSynthesizeData(byte[] data, String format, String sessionId, int isEnd) {
                enqueue(data, format);
            }

            @Override
            public void onSynthesizeData(byte[] data, String format, String sessionId,
                                          int isEnd, String ext) {
                enqueue(data, format);
            }

            @Override
            public void onSynthesizeData(byte[] data, String format, String sessionId,
                                          int isEnd, String ext1, String ext2) {
                enqueue(data, format);
            }

            @Override
            public void onError(TtsError error, String sessionId, String extendParams) {
                speaking.set(false);
                logError("合成回调报错", error, sessionId, extendParams);
                // 已经出过声的失败不回退（半句换嗓子更让人困惑），仍然按错误收尾
                if (heardAudio.get() || !fallbackToOffline()) {
                    dispatchError(string(R.string.tts_cloud_synth_failed, describe(error)));
                }
            }

            @Override
            public void onOfflineAuthInfo(
                    com.tencent.cloud.libqcloudtts.engine.offlineModule.auth.QCloudOfflineAuthInfo info) {
                // 在线模式用不到离线鉴权
            }

            @Override
            public void onChunk(ByteBuffer buffer) {
                // 在线模式不分 chunk
            }
        };
    }

    @NonNull
    private QCloudPlayerCallback playerCallback() {
        return new QCloudPlayerCallback() {
            @Override
            public void onTTSPlayStart() {
            }

            @Override
            public void onTTSPlayWait() {
                // 读完了。AAR 把「这一段念完了」报在这里，而不是 onTTSPlayStop——
                // 后者只在播放出错、或者我们自己调 StopPlay() 时才发（见 AAR 的
                // QCloudMediaPlayer#onCompletion / #onError / #StopPlay）。
                // 收不到这个信号的表现是：声音停了，播放按钮却一直停在「暂停」上，
                // 进度条不动，开着连续朗读也不会往下翻。
                // 队列见底就等于整段读完：一次 synthesize 只产出一整段音频，
                // OnlineTask 把整个 ByteBuffer 一次性交回来（那个 isEnd 参数恒为 0），
                // 入队也就只会有一次。
                if (speaking.compareAndSet(true, false)) {
                    dispatchDone();
                }
            }

            @Override
            public void onTTSPlayResume() {
            }

            @Override
            public void onTTSPlayPause() {
            }

            @Override
            public void onTTSPlayNext(String sessionId, String extend) {
            }

            @Override
            public void onTTSPlayStop() {
                if (suppressStop.get()) {
                    // 我们自己刚掐掉的那一轮，不是读完了
                    return;
                }
                if (speaking.compareAndSet(true, false)) {
                    dispatchDone();
                }
            }

            @Override
            public void onTTSPlayError(QPlayerError error) {
                speaking.set(false);
                dispatchError(string(R.string.tts_cloud_play_failed,
                        describe(error == null ? null : error.getmMessage())));
            }

            @Override
            public void onTTSPlayProgress(String sessionId, int progress) {
            }
        };
    }

    private void enqueue(@Nullable byte[] data, @Nullable String format) {
        QCloudMediaPlayer p = player;
        if (p == null || data == null || data.length == 0) {
            return;
        }
        // AAR 一次回调会把三个 onSynthesizeData 重载挨个都调一遍，三次传的是同一个
        // byte[]（见反编译的 TtsAdapter$1：拿到 ByteBuffer 后连着调 4 参、5 参、6 参）。
        // 三个都入队，一次合成就会往播放器里塞三份同样的音频——用户点一次「朗读」，
        // 整首诗连着念三遍。按数组身份一挡即可；不去动重载本身，是因为挡重放在这里
        // 对「AAR 只调其中某一个重载」的版本同样安全。
        if (data == lastChunk) {
            return;
        }
        lastChunk = data;
        heardAudio.set(true);
        try {
            p.enqueue(data, format == null ? "mp3" : format, ".mp3");
        } catch (Throwable e) {
            LogUtil.w("腾讯云音频入队失败", e);
        }
    }

    // ------------------------------------------------------------------ 文案

    /** 取文案；还没拿到 Context 时给空串（同 {@code SherpaTts}）。 */
    @NonNull
    private String string(@StringRes int resId, Object... args) {
        Context ctx = appContext;
        return ctx == null ? "" : ctx.getString(resId, args);
    }

    /**
     * 腾讯那边给的错误描述可能是空的——不能让界面上出现「合成失败：null」，
     * 那种文案既没法定位问题，看着也像崩了。
     */
    @NonNull
    private String describe(@Nullable String message) {
        return message == null || message.isEmpty()
                ? string(R.string.tts_cloud_unknown_error) : message;
    }

    /**
     * 把一次失败翻成用户能看懂的一句话。
     * <p>
     * SDK 自己的 {@code getMessage()} 是「Server response error」这类笼统说法——用户拿它
     * 既分不清是额度用完了还是密钥停了，也不知道下一步该做什么。真正的原因在
     * {@code getServiceError().getCode()} 里，这里把最常见的三种翻成人话，其余原样带出。
     * <p>
     * 完整的错误码和原文仍然照写日志（见 {@link #logError}），排障不受影响。
     */
    @NonNull
    private String describe(@Nullable TtsError error) {
        TtsServiceError service = error == null ? null : error.getServiceError();
        String code = service == null ? null : service.getCode();
        if (code != null && !code.isEmpty()) {
            if (code.endsWith("PkgExhausted")) {
                return string(R.string.tts_cloud_quota_exhausted);
            }
            if (code.endsWith("PkgNotExists")) {
                return string(R.string.tts_cloud_pkg_missing);
            }
            if (code.startsWith("AuthFailure")) {
                return string(R.string.tts_cloud_auth_failed);
            }
        }
        return describe(error == null ? null : error.getMessage());
    }

    /**
     * 把一次失败的完整信息写进日志。
     * <p>
     * 界面上只能给一句「合成请求失败」，这句多半是 SDK 的笼统说法——
     * 比如 {@code Server response error}，它既可能是鉴权不过，也可能是服务未开通、
     * 额度用尽，光看这句没法定位。服务端的真实错误码和原始响都在
     * {@link TtsServiceError} 里，这里把它整条记下来，排查时直接看
     * {@code adb logcat -s Poetry:V}。
     */
    private static void logError(@NonNull String scene, @Nullable TtsError error,
                                 @Nullable String sessionId, @Nullable String extendParams) {
        if (error == null) {
            LogUtil.w(scene + "：错误对象为空");
            return;
        }
        TtsServiceError service = error.getServiceError();
        LogUtil.w(scene
                + "：code=" + error.getCode()
                + ", message=" + error.getMessage()
                + ", serviceCode=" + (service == null ? "-" : service.getCode())
                + ", serviceMessage=" + (service == null ? "-" : service.getMessage())
                + ", serviceResponse=" + (service == null ? "-" : service.getResponse())
                + ", sessionId=" + sessionId
                + ", extendParams=" + extendParams,
                error.getThrowable());
    }

    // ------------------------------------------------------------------ 转发

    private void dispatchStart() {
        if (listener != null) {
            main.post(listener::onStart);
        }
    }

    private void dispatchDone() {
        if (listener != null) {
            main.post(listener::onDone);
        }
    }

    private void dispatchError(@NonNull String message) {
        if (listener != null) {
            main.post(() -> listener.onError(message));
        }
    }

    private void dispatchNotice(@NonNull String message) {
        if (listener != null) {
            main.post(() -> listener.onNotice(message));
        }
    }

    /**
     * 一个字都还没念出来就失败了：把同一段文本交给内置引擎接着读。
     * <p>
     * 云端失败的原因多半在用户的控制之外——额度用尽、密钥被停、当前没网。这时候离线引擎
     * 就在旁边闲着，而「按了播放、没有声音、只有一条一闪而过的提示」对朗读应用来说是最差
     * 的结局。所以只要还没出过声就换引擎接着念，并且只提醒一声、不当成错误：这次朗读并没有
     * 结束，上层不该借它收尾。
     * <p>
     * 失败本身仍然照写日志——不报错不等于不记录。
     *
     * @return 有没有已经交给内置引擎接手；false 表示调用方还得按错误处理
     */
    private boolean fallbackToOffline() {
        SpeechEngine engine = fallback;
        String text = pendingText;
        if (engine == null || text == null || text.isEmpty()) {
            return false;
        }
        // 先把待读文本清掉：回退之后再来一次失败（例如内置引擎也报错）不该又绕回这里
        pendingText = "";
        // 记一笔：回退是「静默换了个引擎」，只在日志里留痕，事后才分得清这段是谁读的
        LogUtil.w("云端未出声即失败，改用内置音色继续朗读");
        dispatchNotice(string(R.string.tts_cloud_fallback));
        // 回主线程再调：内置引擎的调用点本来都在主线程上，播放状态（播放按钮、进度条）
        // 也在那边，顺序摆对了才不会出现「先响后亮」
        main.post(() -> engine.speak(text));
        return true;
    }
}

package com.example.poetry.media;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.R;
import com.example.poetry.data.Callback;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Voice;
import com.example.poetry.data.remote.ApiException;
import com.example.poetry.util.LogUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 服务端代理的云端音色（腾讯云），要联网、要登录、要会员。
 * <p>
 * <b>为什么是整首而不是逐句。</b>本机引擎是「给一段文本、读一段」，而服务端的
 * {@code POST /v1/tts/synthesize} 收的是「作品引用 + 音色 + 语速」，一次合成<b>整首</b>
 * （见 {@link SpeechEngine#speakWork}）。所以这个引擎只在 {@code speakWork()} 上有实现，
 * {@link #speak(String)} 读不了——试听、逐句朗读这类没有作品引用的场景由
 * {@link EngineRouter} 留在本机那一路。
 * <p>
 * <b>为什么不是直连。</b>直连必须把主账号的 {@code SecretId}/{@code SecretKey} 编进 APK，
 * 而 {@code BuildConfig} 的字符串常量就是明文躺在 {@code classes.dex} 里。凭据只留在服务端，
 * 客户端拿到的是一条 mp3 地址。
 * <p>
 * <b>顶不上时不报错。</b>合成失败、播放起不来，都先交给 {@link Fallback}——由路由器
 * 换成本机音色把这一遍读完，用户只收到一句说明。朗读「还在继续」和「结束了」是两件事，
 * 报成后者的代价是把正在读的一段掐掉（见 {@link SpeechEngine.Listener#onNotice}）。
 *
 * <p>本类的公开方法都在主线程调用（和上层一致），取数的回调也在主线程
 * （见 {@link Callback}），只有 {@link #stop()} 可能与媒体回调交错，所以
 * 「第几遍」和「播放器是谁」这对状态加了锁。
 */
public final class CloudTts implements SpeechEngine {

    private static final CloudTts INSTANCE = new CloudTts();

    public static CloudTts get() {
        return INSTANCE;
    }

    /**
     * 接住「这一遍换本机音色接着读」的那只手，由 {@link EngineRouter} 设进来。
     * <p>
     * 引擎自己不做兜底：它只认得云端这一条路，本机音色怎么读是路由器的事，硬编码进来
     * 就变成了两个引擎互相知道对方。给一个回调口子，谁编排谁负责。
     */
    public interface Fallback {
        /**
         * @param notice 给用户的一句说明（这次换了种方式，但朗读还在继续）
         * @param text   这一遍的正文，原样交回去
         */
        void onFallback(@NonNull String notice, @NonNull String text);
    }

    /**
     * 两次「目录没取到」之间至少隔这么久。
     * <p>
     * 接口层的熔断器是<b>全进程一个</b>（见 {@code ApiClient}）：网络类的失败累积到阈值就把
     * 整个后端拉黑 30 秒，连首页的远端刷新一起停。跨设备离线时音色目录必然每次失败，
     * 不设地板的话，用户来回进出语音设置页几次就能把它敲开。成功的重取不受这个限制——
     * 登录之后锁定态必须立刻翻过来（见 {@link #refreshVoices()}）。
     */
    private static final long RETRY_FLOOR_MS = 10_000L;

    /** 服务端给回来的是 mp3，按「媒体/语音」播，免得被当成铃声走去别的音量通道。 */
    private static final AudioAttributes AUDIO = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    private final Handler main = new Handler(Looper.getMainLooper());

    private final Object lock = new Object();

    /** 第几遍。换一遍就加一——老的合成/播放回调认出来就退场（{@link #stop()} 也是加一）。 */
    private int generation;
    @Nullable
    private MediaPlayer player;
    private boolean speaking;
    /** 这一遍已经真的出声了（只影响失败时说哪种话，见 {@link #play}）。 */
    private boolean started;

    /** 服务端下发的音色目录；没取到之前是空表。 */
    @NonNull
    private volatile List<Voice> catalog = Collections.emptyList();
    /** 目录问过了（不管成没成）。界面据此判断「眼下这张表全不全」。 */
    private volatile boolean catalogAsked;
    private volatile boolean catalogFetching;
    private volatile long lastFailureAt;
    @Nullable
    private volatile Runnable voiceListListener;

    /** 用户选中的云端音色 id，形如 {@code cloud-101001}，原样发给服务端。 */
    @NonNull
    private volatile String voiceId = "";
    private volatile float rate = TtsConfig.RATE_DEFAULT;
    private volatile float volume = TtsConfig.VOLUME_DEFAULT;

    @Nullable
    private volatile Listener listener;
    @Nullable
    private volatile Fallback fallback;
    @Nullable
    private volatile PoetryRepository repository;
    /** 只用来取文案（{@link #statusText} 那个 context 是调用方给的，这里的是自己留着用）。 */
    @Nullable
    private volatile Context appContext;

    private CloudTts() {
    }

    /** 运行时才知道顶不顶得上（会员、目录）时，换本机音色接着读的那只手。 */
    public void setFallback(@Nullable Fallback fallback) {
        this.fallback = fallback;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void prepare(@NonNull Context context, @Nullable Runnable onReady) {
        appContext = context.getApplicationContext();
        repository = PoetryRepository.get(appContext);
        if (!catalogAsked) {
            fetchCatalog();
        }
        // 云端这边没有「加载模型」这回事：能不能读由服务端的目录说了算，而它在网络上。
        // 所以这里报的只是「上下文收下了」，目录到没到另说（见 setVoiceListListener）。
        // 上层不该等它——朗读能不能起来靠的是本机那一路，把界面压在一次网络往返上
        // 是阶段一刚拆掉的那种慢（见 EngineRouter#prepare）。
        if (onReady != null) {
            main.post(onReady);
        }
    }

    @Override
    public boolean isReady() {
        return usableCount() > 0;
    }

    /**
     * 选中的云端音色现在能不能用：能用返回 {@code null}，不能用返回<b>一句给用户看的说明</b>。
     * <p>
     * 判据和措辞合在一个方法里，是因为它们必须一致。拆成「能不能」和「为什么不能」两个
     * 方法，迟早会出现「判据说能用、措辞说需会员」这种自相矛盾的组合。
     * <p>
     * 说明里那句「已用本机音色朗读」是因为调用方只会拿它去兜底：真报出不可用的时候，
     * 这一遍必然落到本机音色上（见 {@link Fallback}），说了别的才是骗人。
     */
    @Nullable
    public String unavailability(@NonNull Context context) {
        int reason;
        if (repository == null) {
            // 还没 prepare 过：连不上后端，谈不上合成
            reason = R.string.tts_cloud_no_voice;
        } else if (!Voice.isCloudId(voiceId)) {
            // 用户选的是本机音色，云端这一路根本不参与——这不是「顶不上」，是不该来问。
            // 调用方只会在选中云端音色时才问，所以到这里说明状态对不上，说句实话即可。
            reason = R.string.tts_cloud_no_voice;
        } else {
            Voice entry = find(voiceId);
            if (entry == null) {
                // 目录里没有它：要么目录没取到，要么服务端下掉了这个音色
                reason = R.string.tts_cloud_no_voice;
            } else if (entry.isLocked()) {
                // 锁定态是服务端给的口径，能不能用一律由它说了算，客户端不自己放行。
                // 但它的 locked 是一个布尔值，把两种原因（没会员 / 服务端没配好）压在了一起，
                // 而对用户来说这是两件完全不同的事：一句「去开通会员」，一句「这不是你的问题」。
                // 本机缓存的会员状态只用来挑措辞——已经是会员的人不该被告知「需会员」，
                // 那会把他支使去开通一个已经开通了的东西。
                reason = UserStore.get(context).isMember()
                        ? R.string.tts_cloud_fallback
                        : R.string.tts_cloud_member_fallback;
            } else {
                return null;
            }
        }
        return context.getString(reason);
    }

    @NonNull
    @Override
    public String statusText(@NonNull Context context) {
        if (!catalog.isEmpty()) {
            int usable = usableCount();
            return context.getString(usable > 0
                            ? R.string.tts_cloud_ready
                            : R.string.tts_cloud_locked,
                    usable);
        }
        // 失败和「还没问」要分开说：前者是当前的结论，后者只是还没到
        return context.getString(catalogAsked
                ? R.string.tts_cloud_no_voice
                : R.string.tts_cloud_fetching);
    }

    @Override
    public void shutdown() {
        stop();
        listener = null;
        voiceListListener = null;
    }

    // ------------------------------------------------------------------ 音色目录

    @NonNull
    @Override
    public List<Voice> listVoices() {
        return new ArrayList<>(catalog);
    }

    @Override
    public void setVoiceListListener(@Nullable Runnable listener) {
        this.voiceListListener = listener;
    }

    @Override
    public boolean isVoiceListSettled() {
        return catalogAsked;
    }

    @Override
    public void refreshVoices() {
        if (catalogFetching) {
            return;
        }
        if (lastFailureAt != 0L && System.currentTimeMillis() - lastFailureAt < RETRY_FLOOR_MS) {
            // 刚失败过，别接着敲：这只熔断器是全进程的
            return;
        }
        fetchCatalog();
    }

    private void fetchCatalog() {
        final PoetryRepository repo = repository;
        if (repo == null || catalogFetching) {
            return;
        }
        catalogFetching = true;
        repo.voices(new Callback<List<Voice>>() {
            @Override
            public void onData(@NonNull List<Voice> data) {
                catalogFetching = false;
                catalogAsked = true;
                lastFailureAt = 0L;
                catalog = Collections.unmodifiableList(new ArrayList<>(data));
                notifyVoiceList();
            }

            @Override
            public void onError(@Nullable Throwable error) {
                catalogFetching = false;
                catalogAsked = true;
                lastFailureAt = System.currentTimeMillis();
                // 不弹任何东西：顶不上时朗读会换本机音色（见 Fallback），而这一趟多半是
                // 冷启动时跑的（见 PoetryApp），那时候没有人在等音色列表
                LogUtil.d("云端音色目录没取到：" + error);
                notifyVoiceList();
            }
        });
    }

    /** 目录落定了，说一声。回调本身就在主线程（见 {@link Callback}）。 */
    private void notifyVoiceList() {
        Runnable listener = voiceListListener;
        if (listener != null) {
            listener.run();
        }
    }

    private int usableCount() {
        int usable = 0;
        for (Voice voice : catalog) {
            if (!voice.isLocked()) {
                usable++;
            }
        }
        return usable;
    }

    @Nullable
    private Voice find(@NonNull String id) {
        for (Voice voice : catalog) {
            if (id.equals(voice.getId())) {
                return voice;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 配置

    @Override
    public boolean setVoice(@NonNull String voiceId) {
        String id = Voice.normalizeId(voiceId);
        if (!Voice.isCloudId(id)) {
            // 用户改选本机音色了，云端这一侧让位。让位不等于「忘掉」：他挑过的云端音色
            // 由配置记着（见 TtsConfig），会员一开、服务端一回来就该切回去；这里只是
            // 声明「现在读的不是我」。不让位的话，{@link #currentVoiceId()} 会继续报那个
            // 旧音色，界面上就成了「选的是本机音色、勾却在云端那一行」。
            this.voiceId = "";
            return false;
        }
        this.voiceId = id;
        // 认不认这个音色由服务端说了算（合成时原样发给它），这里能保证的只是「id 收下了」
        return true;
    }

    @Nullable
    @Override
    public String currentVoiceId() {
        // 只有「目录里有这个音色、它又没上锁」才算数——这才是现在真会出声的那个。
        // 用不上时报 null，让路由器回落到本机音色；界面据此判断用户的云端选择
        // 是不是被顶替了（见 VoiceSettingsActivity#reloadVoices）。
        Voice entry = find(voiceId);
        return entry != null && !entry.isLocked() ? voiceId : null;
    }

    @Override
    public void apply(@NonNull TtsConfig config) {
        rate = config.getRate();
        volume = config.getVolume();
        // 音调没有对应的旋钮：服务端的合成参数只有语速（见 tencent.go 的请求体）
        String id = Voice.normalizeId(config.getVoiceId());
        // 归这一路的条件只有一个：配置里写的就是云端音色。不是就让位（同 setVoice），
        // 免得把一个更早选过的云端音色继续当成「现在这个」。
        voiceId = Voice.isCloudId(id) ? id : "";
        // 音量作用在正在播的那一条上，和本机引擎一样：拖了马上能听到
        MediaPlayer playing;
        synchronized (lock) {
            playing = player;
        }
        if (playing != null) {
            try {
                playing.setVolume(volume, volume);
            } catch (Throwable ignored) {
                // 已经进入错误/结束状态：这一条马上就会被丢掉，不值得打断调用方
            }
        }
    }

    // ------------------------------------------------------------------ 朗读

    @Override
    public void speak(@NonNull String text) {
        // 云端是按作品合成的（见 speakWork）：光有一段文本没有对应的接口。
        // 正常走不到这里——路由器把没有作品引用的朗读都留在本机那一路了（试听、逐句朗读）。
        // 真到了这里就是接线错了，说清楚比默默不出声好。
        dispatchError(string(R.string.tts_cloud_text_only));
    }

    @Override
    public void speakWork(@NonNull String workRef, @NonNull String text) {
        final PoetryRepository repo = repository;
        final Context context = appContext;
        if (repo == null || context == null) {
            dispatchError(string(R.string.tts_cloud_no_voice));
            return;
        }
        final int round = beginRound();
        repo.synthesize(workRef, voiceId, rate, new Callback<String>() {
            @Override
            public void onData(@NonNull String url) {
                play(round, url, text);
            }

            @Override
            public void onError(@Nullable Throwable error) {
                // 还没出声就失败了：这一遍交给路由器用本机音色读完，不报错
                handBack(round, noticeFor(context, error), text);
            }
        });
    }

    @Override
    public void stop() {
        MediaPlayer stale;
        synchronized (lock) {
            generation++;
            stale = player;
            player = null;
            speaking = false;
            started = false;
        }
        release(stale);
    }

    @Override
    public boolean isSpeaking() {
        synchronized (lock) {
            return speaking;
        }
    }

    // ------------------------------------------------------------------ 播放

    /** 开一遍新的：掐掉上一遍、作废在飞的旧回调，并先应一声（合成有往返，不能让用户干等）。 */
    private int beginRound() {
        MediaPlayer stale;
        int round;
        synchronized (lock) {
            stale = player;
            player = null;
            started = false;
            round = ++generation;
            speaking = true;
        }
        release(stale);
        if (listener != null) {
            listener.onStart();
        }
        return round;
    }

    private void play(final int round, @NonNull String url, @NonNull String text) {
        final MediaPlayer mp = new MediaPlayer();
        synchronized (lock) {
            if (round != generation) {
                // 这一遍已经被停掉了（用户按了停止、又点了别的）
                release(mp);
                return;
            }
            player = mp;
        }
        try {
            mp.setAudioAttributes(AUDIO);
            mp.setVolume(volume, volume);
            mp.setDataSource(url);
            mp.setOnPreparedListener(prepared -> {
                if (!current(round)) {
                    drop(prepared);
                    return;
                }
                synchronized (lock) {
                    started = true;
                }
                prepared.start();
            });
            mp.setOnCompletionListener(completed -> done(round, completed));
            mp.setOnErrorListener((failed, what, extra) -> {
                // 出声之后失败（读到一半连接断了）不再换嗓子重读：那会让用户以为
                // 又从头开始了一遍。此时如实结束这一遍，他自己会再点一次。
                if (wasStarted()) {
                    // what/extra 是 MediaPlayer 的内部错误号（-1004、1 这种），
                    // 用户照着它做不了任何事，所以记进日志、不摆到界面上
                    LogUtil.w("云端音频播放中断：what=" + what + " extra=" + extra);
                    fail(round, failed, string(R.string.tts_cloud_play_failed));
                } else {
                    fallbackWith(round, failed, string(R.string.tts_cloud_fallback), text);
                }
                return true;
            });
            mp.prepareAsync();
        } catch (Throwable error) {
            // setDataSource 不认这条 URL、prepareAsync 状态不对，都算「没出声就失败」
            LogUtil.w("云端音频起不来", error);
            fallbackWith(round, mp, string(R.string.tts_cloud_fallback), text);
        }
    }

    private void done(int round, @NonNull MediaPlayer mp) {
        drop(mp);
        boolean live;
        synchronized (lock) {
            live = round == generation;
            if (live) {
                speaking = false;
            }
        }
        if (live && listener != null) {
            listener.onDone();
        }
    }

    /** 出声之后失败：如实结束这一遍（上层要收尾，不能只当提醒）。 */
    private void fail(int round, @NonNull MediaPlayer mp, @NonNull String message) {
        drop(mp);
        boolean live;
        synchronized (lock) {
            live = round == generation;
            if (live) {
                speaking = false;
            }
        }
        if (live) {
            dispatchError(message);
        }
    }

    /**
     * 没出声就失败了：把这一遍交回路由器，用本机音色接着读完，只发一句说明。
     * <p>
     * 交给别人之前先确认这一遍还算数——用户可能已经按了停止，那时谁都别出声。
     */
    private void handBack(int round, @NonNull String notice, @NonNull String text) {
        fallbackWith(round, null, notice, text);
    }

    private void fallbackWith(int round, @Nullable MediaPlayer mp, @NonNull String notice,
                              @NonNull String text) {
        if (mp != null) {
            drop(mp);
        }
        boolean current;
        synchronized (lock) {
            current = round == generation;
            if (current) {
                speaking = false;
            }
        }
        if (!current) {
            return;
        }
        Fallback hand = fallback;
        if (hand != null) {
            hand.onFallback(notice, text);
        } else {
            // 没接手的（不该发生）：至少把话说清楚，别静悄悄地什么都不发生
            dispatchError(notice);
        }
    }

    /** 合成失败的说明：会员那条单独一句，指向下一步动作，其余统一「暂时不可用」。 */
    @NonNull
    private String noticeFor(@NonNull Context context, @Nullable Throwable error) {
        if (error instanceof ApiException
                && ((ApiException) error).getCode() == ApiException.VIP_REQUIRED) {
            return context.getString(R.string.tts_cloud_member_fallback);
        }
        return context.getString(R.string.tts_cloud_fallback);
    }

    // ------------------------------------------------------------------ 小工具

    private boolean current(int round) {
        synchronized (lock) {
            return round == generation;
        }
    }

    private boolean wasStarted() {
        synchronized (lock) {
            return started;
        }
    }

    /** 把播放器收下来（它可能已经被换掉），并释放。 */
    private void drop(@NonNull MediaPlayer mp) {
        synchronized (lock) {
            if (player == mp) {
                player = null;
            }
        }
        release(mp);
    }

    private static void release(@Nullable MediaPlayer mp) {
        if (mp == null) {
            return;
        }
        try {
            mp.reset();
        } catch (Throwable ignored) {
            // 已经释放 / 状态不对：下面的 release 才是必须做的那一步
        }
        try {
            mp.release();
        } catch (Throwable ignored) {
        }
    }

    /** 取一句文案；没 prepare 过时连 context 都没有——那种接线错误在日志里，别拿空指针崩给用户。 */
    @NonNull
    private String string(int resId, Object... formatArgs) {
        Context context = appContext;
        return context == null ? "" : context.getString(resId, formatArgs);
    }

    private void dispatchError(@NonNull String message) {
        Listener listener = this.listener;
        if (listener != null) {
            listener.onError(message);
        }
    }

    @Override
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }
}

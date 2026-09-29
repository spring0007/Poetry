package com.example.poetry.media;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.R;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Voice;
import com.example.poetry.util.LogUtil;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import kotlin.jvm.functions.Function1;

/**
 * 内置离线引擎：sherpa-onnx + 随 APK 分发的中文 VITS 语音包，整条合成链路跑在本机。
 * <p>
 * 一个语音包（见 {@link VoicePacks}）是一套模型，包里可以有多个发音人——模型自己按
 * {@code sid} 区分音色。这里把每个包里挑出来的发音人摊平成一张音色表交给界面，
 * 界面不需要知道音色属于哪个包、更不需要知道 sid 是什么。
 * <p>
 * 四件事值得记一笔：
 * <ol>
 *   <li>模型放在 assets 里，APK 装好即自带，不需要联网。首启把它们拷到
 *       {@code filesDir/tts_model/<包>/}，之后按文件路径加载——三个 rule FST 是路径参数，
 *       走 AssetManager 能否被 JNI 正确解析没有验证过，拷一次是稳的；</li>
 *   <li>Android 10+ 不允许 dlopen 应用数据目录里的 .so，所以 native 库必须随 APK 分发
 *       （见 {@code app/libs/}），模型是纯数据才可以自由摆放；</li>
 *   <li>合成放在单线程上、边合成边播（{@link AudioTrack} 流式写入），所以「停止」能立刻静音；</li>
 *   <li>音色 id 是 {@code sherpa-<包>-<sid>}，会原样存进用户配置。{@code sid} 只是数组下标，
 *       所以加载时逐行核对 {@code speakers.txt}，见 {@link VoicePacks#verifiedRoles}。</li>
 * </ol>
 * <p>
 * 多个包会各自持有一份 native 引擎（onnx 模型约 30 MB），所以「内置多少套语音库」
 * 不是免费的。眼下只有 {@link VoicePacks#AISHELL3} 一个包；真要加到三四个，
 * 就该改成按选中的音色懒加载，而不是 {@link #loadAll} 里那样一次全开。
 * <p>
 * 音调没有对应参数——VITS 只有时长这一个旋钮，改音高得另训模型。见 {@code TTS.md}。
 */
public final class SherpaTts implements SpeechEngine {

    /** 模型拷贝的目标目录，下面按包 id 再分一层。 */
    private static final String MODEL_DIR = "tts_model";

    /**
     * 模型拷好之后落的标记文件。内容是「这份拷贝是从哪一版安装包解出来的」，
     * 见 {@link #installStamp}——不能只看它存不存在。
     */
    private static final String READY_MARKER = ".ready";

    /**
     * 只有一个语音包的年代，模型是直接摊在 {@code tts_model/} 下的。
     * 升上来的机器上那份拷贝已经没人读，却仍占着 20 MB，第一次加载时顺手清掉。
     */
    private static final String[] LEGACY_FLAT_FILES = {
            "zh_CN-xiao_ya-medium.onnx",
            "tokens.txt",
            "lexicon.txt",
            "phone.fst",
            "date.fst",
            "number.fst",
            READY_MARKER,
    };

    /**
     * 合成线程数。原来写死 2；按核数自适应能把 RTF 再压一截
     * （官方在树莓派 4B 上实测 aishell3：2 线程 0.220，4 线程 0.156）。
     */
    private static int defaultThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        if (cores >= 8) {
            return 4;
        }
        if (cores >= 6) {
            return 3;
        }
        return 2;
    }

    /**
     * 联与联之间补的静音（毫秒）。
     * <p>
     * 一个「联」是一次 {@code generate()}，两次调用之间原来是<b>零间隔</b>——上一联的尾音
     * 直接撞上下一联的首字，听感就是「赶」。诗词本来就是慢读的体裁，联末留一口气，
     * 既像人读的样子，也让每一联的意思落得下来。
     * <p>
     * 这个是<b>我们自己插的静音</b>，不是靠模型参数，所以时长精确可控。
     * {@code Speaker.estimateDurationMs} 也用它来估进度条，改这里要一并生效。
     */
    static final long BLOCK_GAP_MS = 320L;

    /**
     * AudioTrack 的缓冲时长（秒）。
     * <p>
     * 这个数字是「卡不卡」的关键：合成与播放是两个线程，播放线程写满缓冲后会阻塞、
     * 等合成线程补上。缓冲太浅，合成稍慢一点就断音。原来用
     * {@code getMinBufferSize() * 2}（22050 Hz 单声道下通常只有 0.1 秒左右），
     * 合成线程一转头去算下一句缓冲就见底，于是<b>每一句之间都必然静音</b>。
     * 给到 1 秒，等于允许合成偶尔落后 1 秒而不影响听感。
     */
    private static final float BUFFER_SECONDS = 1.0f;

    /** 合成最多可以领先播放多少段。越大越抗抖动，内存占用也越大（每段 ≈ 1 MB）。 */
    private static final int QUEUE_DEPTH = 4;

    /** 播放线程取队列的轮询间隔，同时也是它检查「本轮是否作废」的周期。 */
    private static final long POLL_TIMEOUT_MS = 50L;

    /** 等播放头追上已写入位置的超时，防止异常情况下收尾卡死。 */
    private static final long DRAIN_TIMEOUT_MS = 5000L;

    /** 引擎状态：还没加载 / 就绪 / assets 里缺模型 / 初始化失败。 */
    private static final int STATE_IDLE = 0;
    private static final int STATE_READY = 1;
    private static final int STATE_MISSING = 2;
    private static final int STATE_FAILED = 3;

    private static volatile SherpaTts instance;

    /** 加载好的一个语音包：描述 + native 引擎 + 核对通过的发音人。 */
    private static final class Loaded {

        @NonNull
        final VoicePacks.Pack pack;
        @NonNull
        final OfflineTts engine;
        final int sampleRate;
        @NonNull
        final List<VoicePacks.Role> roles;
        /** 模型认到的发音人总数（不只登记过的那几个），供音色筛选工具用。 */
        final int numSpeakers;
        /** {@code speakers.txt} 的原始内容，下标即 sid，供音色筛选工具用。 */
        @NonNull
        final List<String> speakerIds;

        Loaded(@NonNull VoicePacks.Pack pack, @NonNull OfflineTts engine, int sampleRate,
               @NonNull List<VoicePacks.Role> roles, int numSpeakers,
               @NonNull List<String> speakerIds) {
            this.pack = pack;
            this.engine = engine;
            this.sampleRate = sampleRate;
            this.roles = roles;
            this.numSpeakers = numSpeakers;
            this.speakerIds = speakerIds;
        }
    }

    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Context appContext;
    private Listener listener;

    private ExecutorService worker;
    private final List<Loaded> loaded = new ArrayList<>();

    private int state = STATE_IDLE;
    private String failureReason = "";

    private String currentVoiceId = "";

    /** 首启时可能有两个调用方同时来要模型，合并成一次加载。 */
    private boolean loading;
    private Runnable pendingReady;

    private float pendingRate = TtsConfig.RATE_DEFAULT;
    private float pendingVolume = TtsConfig.VOLUME_DEFAULT;

    /**
     * 播放轮次：递增即作废上一轮，迟到的回调据此丢弃。
     * 合成、播放两个线程都在无锁地读它，所以必须是 {@code volatile}。
     */
    private volatile int generation;
    /** 当前在播的 AudioTrack，供 {@link #stop()} 从别的线程掐断阻塞中的写入。 */
    private volatile AudioTrack track;
    private volatile boolean speaking;

    private SherpaTts() {
    }

    public static SherpaTts get() {
        if (instance == null) {
            synchronized (SherpaTts.class) {
                if (instance == null) {
                    instance = new SherpaTts();
                }
            }
        }
        return instance;
    }

    /**
     * 加载模型。全部在后台线程做：拷 30 MB + 建 native 引擎，主线程干这个会 ANR。
     */
    public void prepare(@NonNull Context context, @Nullable Runnable onReady) {
        final Context app = context.getApplicationContext();
        synchronized (lock) {
            appContext = app;
            if (state == STATE_READY && !loaded.isEmpty()) {
                dispatchReady(onReady);
                return;
            }
            if (loading) {
                // 首启时 DetailActivity 和语音设置页会各调一次 prepare：合并成一次加载，
                // 回调取最后一次（两处要的都是「刷新界面」，幂等）。
                pendingReady = onReady;
                return;
            }
            loading = true;
            pendingReady = onReady;
        }
        worker().execute(() -> {
            loadAll(app);
            Runnable ready;
            synchronized (lock) {
                loading = false;
                ready = pendingReady;
                pendingReady = null;
            }
            dispatchReady(ready);
        });
    }

    /**
     * 把 {@link VoicePacks#ALL} 里的语音包逐个加载进来。
     * <p>
     * 一个包加载失败不让整个引擎失败——能读的包照读，只是音色少几款。
     * 一个包都没成，才按「全坏」报出去。
     */
    private void loadAll(@NonNull Context app) {
        pruneLegacyLayout(app);
        List<Loaded> ready = new ArrayList<>();
        boolean anyAssets = false;
        String reason = "";
        for (VoicePacks.Pack pack : VoicePacks.ALL) {
            File base;
            try {
                base = ensureModel(app, pack);
            } catch (IOException error) {
                LogUtil.w("拷「" + pack.id + "」的模型失败", error);
                anyAssets = true;
                reason = describe(error);
                continue;
            }
            if (base == null) {
                // assets 里就没有：仓库用 .gitignore 忽略了这个目录，新克隆必然如此
                LogUtil.w("assets/" + pack.assetDir + " 里缺模型文件");
                continue;
            }
            anyAssets = true;
            try {
                ready.add(open(pack, base));
            } catch (Throwable error) {
                // native 库加载失败是 UnsatisfiedLinkError（Error 而非 Exception），
                // 这里是朗读的唯一实现，绝不能把整个 App 带崩
                LogUtil.w("「" + pack.id + "」初始化失败", error);
                reason = describe(error);
            }
        }
        synchronized (lock) {
            loaded.clear();
            loaded.addAll(ready);
            if (!ready.isEmpty()) {
                // 音色 id 归一：存着的老 id（例如只有小雅那个年代的 sherpa-xiao-ya）
                // 在这里落到新包的默认发音人上，界面与实际发声的会是同一个
                currentVoiceId = resolve(currentVoiceId);
            }
        }
        if (!ready.isEmpty()) {
            setState(STATE_READY, "");
        } else if (anyAssets) {
            setState(STATE_FAILED, reason);
        } else {
            setState(STATE_MISSING, "");
        }
    }

    /**
     * 打开一个语音包：建 native 引擎、核对发音人。
     *
     * @throws IOException 发音人核对全部失败——这种包宁可不用，也不能让用户选中一个陌生人
     */
    @NonNull
    private Loaded open(@NonNull VoicePacks.Pack pack, @NonNull File base) throws IOException {
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig(
                new File(base, pack.modelName).getAbsolutePath(),
                new File(base, "lexicon.txt").getAbsolutePath(),
                new File(base, "tokens.txt").getAbsolutePath(),
                /* dataDir= */ "",
                /* dictDir= */ "",
                pack.noiseScale, pack.noiseW, pack.lengthScale);
        // 十参构造对每个子模型都做非空校验（Kotlin 的 checkNotNullParameter），
        // 传 null 会直接抛 NPE，所以用无参构造 + setter：其余子模型留作默认空配置。
        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setVits(vits);
        model.setNumThreads(defaultThreads());
        model.setDebug(false);
        model.setProvider("cpu");
        // silenceScale：每个内部句末额外补的静音。原为 0.2（每句都拖一下），
        // 中途降到 0.05 又太赶；现在取 0.15——联内的逗号停顿有个呼吸，联与联之间
        // 由我们自己的 BLOCK_GAP_MS 负责，两者分工不同。
        OfflineTtsConfig config = new OfflineTtsConfig(
                model, ruleFsts(pack, base), /* ruleFars= */ "", /* maxNumSentences= */ 1,
                /* silenceScale= */ 0.15f);
        // AssetManager 传 null = 按文件路径读；构造里的非空校验只针对 config。
        OfflineTts engine = new OfflineTts(null, config);

        List<String> speakers = readSpeakers(base);
        if (engine.numSpeakers() != speakers.size()) {
            LogUtil.w("「" + pack.id + "」引擎认到 " + engine.numSpeakers()
                    + " 个发音人，speakers.txt 有 " + speakers.size() + " 行");
        }
        List<VoicePacks.Role> roles = VoicePacks.verifiedRoles(pack, speakers);
        if (roles.size() < pack.roles.length) {
            LogUtil.w("「" + pack.id + "」发音人核对没过：登记的 " + pack.roles.length
                    + " 个里只剩 " + roles.size() + " 个可用");
        }
        if (roles.isEmpty()) {
            engine.release();
            throw new IOException("发音人一个都没对上：" + pack.id);
        }
        int sampleRate = engine.sampleRate() > 0 ? engine.sampleRate() : pack.fallbackSampleRate;
        // 把设备上那份 speakers.txt 的指纹打出来：音色「对不上 / 少了几款」时，
        // 拿这一行跟仓库里的 assets/tts/<包>/speakers.txt 比一下就知道是不是模型拷贝过期了
        LogUtil.i("「" + pack.id + "」就绪, sampleRate=" + sampleRate
                + ", threads=" + defaultThreads()
                + ", speakers=" + engine.numSpeakers() + ", 可用音色=" + roles.size()
                + ", speakers.txt=" + speakers.size() + " 行"
                + (speakers.isEmpty() ? ""
                        : "（首 " + speakers.get(0) + " / 末 " + speakers.get(speakers.size() - 1) + "）"));
        return new Loaded(pack, engine, sampleRate, roles, engine.numSpeakers(), speakers);
    }

    /** 文本正规化 FST，按登记顺序串联，拼成引擎要的绝对路径。 */
    @NonNull
    private static String ruleFsts(@NonNull VoicePacks.Pack pack, @NonNull File base) {
        StringBuilder joined = new StringBuilder();
        for (String name : pack.ruleFsts) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(new File(base, name).getAbsolutePath());
        }
        return joined.toString();
    }

    /** 读 {@code speakers.txt}：第 n 行就是 sid = n 的官方发音人 id。 */
    @NonNull
    private static List<String> readSpeakers(@NonNull File base) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(base, "speakers.txt")), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line.trim());
            }
        }
        return lines;
    }

    /**
     * 把模型从 assets 拷到 filesDir；已经拷过<b>且是当前安装包解出来的</b>才跳过。
     * 缺文件返回 null（不算异常，是「这个包没带模型」）。
     * <p>
     * <b>为什么标记要带戳。</b>原来只看 {@code .ready} 在不在，于是这份拷贝一旦生成就
     * 再也不会更新：assets 里换了模型、改了 {@code speakers.txt}，设备上那份旧的会一直
     * 用下去。表现出来不是崩溃，而是「音色对不上 / 音色少了几款」这类极难定位的问题——
     * {@code speakers.txt} 与 {@link VoicePacks} 登记的编号一旦错开，
     * {@link VoicePacks#verifiedRoles} 就会把对不上的发音人悄悄丢掉。详见 {@code TTS-ALTERNATIVES.md}。
     */
    @Nullable
    private File ensureModel(@NonNull Context app, @NonNull VoicePacks.Pack pack)
            throws IOException {
        AssetManager assets = app.getAssets();
        String[] available = assets.list(pack.assetDir);
        if (available == null || available.length == 0) {
            return null;
        }
        List<String> names = Arrays.asList(available);
        for (String name : pack.files) {
            if (!names.contains(name)) {
                LogUtil.w("assets/" + pack.assetDir + " 缺少 " + name);
                return null;
            }
        }

        File dir = new File(new File(app.getFilesDir(), MODEL_DIR), pack.id);
        File marker = new File(dir, READY_MARKER);
        String stamp = pack.id + ":" + installStamp(app);
        if (marker.isFile() && stamp.equals(readMarker(marker))) {
            return dir;
        }
        if (marker.isFile()) {
            // 装了新版 / 上次拷到一半被杀掉：整目录重来，别让半套或过期的模型留在盘上
            LogUtil.i("「" + pack.id + "」的模型拷贝对不上当前安装包，重新解一次");
        }
        deleteRecursively(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("建不了目录 " + dir);
        }
        for (String name : pack.files) {
            copy(assets, pack.assetDir + "/" + name, new File(dir, name));
        }
        writeMarker(marker, stamp);
        return dir;
    }

    /**
     * 本次安装的戳。应用一更新就变，所以「戳对不上」= 设备上那份模型该重拷了。
     * <p>
     * 用安装时间而不是给 assets 逐个算摘要：摘要要读满 30 MB，每次启动都得付这个代价；
     * 而安装戳是现成的，代价只是「每次应用更新多拷一次模型」——那本来就是一次性的 IO。
     */
    private static long installStamp(@NonNull Context app) {
        try {
            return app.getPackageManager()
                    .getPackageInfo(app.getPackageName(), 0).lastUpdateTime;
        } catch (Throwable error) {
            // 取不到就退化成 0：同一个安装内稳定，行为等同旧版，不会每次启动都重拷
            LogUtil.w("取不到安装时间戳，模型拷贝的失效判断退化为「只看标记在不在」", error);
            return 0L;
        }
    }

    @NonNull
    private static String readMarker(@NonNull File marker) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(marker), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException error) {
            // 读不出来就当没写过，重拷一次即可
            return "";
        }
    }

    private static void writeMarker(@NonNull File marker, @NonNull String stamp)
            throws IOException {
        try (OutputStream out = new FileOutputStream(marker)) {
            out.write(stamp.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /** 清掉只有小雅那个年代留下的平铺模型文件。见 {@link #LEGACY_FLAT_FILES}。 */
    private static void pruneLegacyLayout(@NonNull Context app) {
        File dir = new File(app.getFilesDir(), MODEL_DIR);
        if (!new File(dir, READY_MARKER).isFile()) {
            return;
        }
        for (String name : LEGACY_FLAT_FILES) {
            deleteRecursively(new File(dir, name));
        }
        LogUtil.i("已清掉旧版平铺在 tts_model/ 下的模型文件");
    }

    private static void copy(@NonNull AssetManager assets, @NonNull String from,
                             @NonNull File to) throws IOException {
        try (InputStream in = assets.open(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
            }
            out.flush();
            if (total == 0) {
                throw new IOException("空文件 " + from);
            }
        }
    }

    private static void deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        // 删不掉也不致命：后面 mkdirs/覆盖写会把该有的补上
        if (!file.delete()) {
            LogUtil.i("删不掉 " + file);
        }
    }

    private void setState(int newState, @NonNull String reason) {
        synchronized (lock) {
            state = newState;
            failureReason = reason;
        }
    }

    private static String describe(@NonNull Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }

    // ------------------------------------------------------------------ 状态

    @Override
    public boolean isReady() {
        synchronized (lock) {
            return state == STATE_READY && !loaded.isEmpty();
        }
    }

    @NonNull
    @Override
    public String statusText(@NonNull Context context) {
        int current;
        String reason;
        synchronized (lock) {
            current = state;
            reason = failureReason;
        }
        switch (current) {
            case STATE_READY:
                return context.getString(R.string.tts_engine_builtin_ready, roleCount());
            case STATE_FAILED:
                return context.getString(R.string.tts_engine_builtin_failed, reason);
            case STATE_MISSING:
                return context.getString(R.string.tts_engine_builtin_missing);
            default:
                // 还没加载过。正常路径上问不到——界面都是等 prepare 的回调之后才来读状态，
                // 那时 state 已经定了。真读到就按「不可用」说。
                return context.getString(R.string.tts_engine_builtin_missing);
        }
    }

    @Override
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------ 音色

    @NonNull
    @Override
    public List<Voice> listVoices() {
        List<Voice> result = new ArrayList<>();
        String current = currentVoiceId();
        for (Loaded item : loaded()) {
            for (VoicePacks.Role role : item.roles) {
                result.add(voice(item.pack, role, current));
            }
        }
        return result;
    }

    @NonNull
    private Voice voice(@NonNull VoicePacks.Pack pack, @NonNull VoicePacks.Role role,
                        @NonNull String currentId) {
        String id = VoicePacks.voiceId(pack, role.sid);
        Voice item = new Voice();
        item.setId(id);
        item.setName(string(role.nameRes));
        item.setDesc(string(role.descRes));
        item.setLocale("zh-CN");
        item.setTag1(string(role.male ? R.string.tts_tag_male : R.string.tts_tag_female));
        item.setTag2(string(R.string.tts_tag_chinese));
        item.setSelected(id.equals(currentId));
        return item;
    }

    /**
     * 记下选中的音色。传进来的 id 不认（老版本的 id、或这个发音人这次没通过核对）时不报错，
     * 而是落到默认发音人上——对用户来说「换了台手机，音色回到默认」比「朗读报错」好得多。
     *
     * @return 传进来的 id 是不是原样生效了
     */
    @Override
    public boolean setVoice(@NonNull String voiceId) {
        String resolved = resolve(voiceId);
        synchronized (lock) {
            currentVoiceId = resolved;
        }
        return resolved.equals(voiceId);
    }

    @Nullable
    @Override
    public String currentVoiceId() {
        synchronized (lock) {
            return currentVoiceId;
        }
    }

    /**
     * 把任意 id 归一成一个当前真的能用的音色 id。
     * 认不出来就退到第一个包——包里的发音人优先，包没了就退到包本身。
     */
    @NonNull
    private String resolve(@Nullable String voiceId) {
        List<Loaded> packs = loaded();
        if (packs.isEmpty()) {
            return "";
        }
        int[] target = locate(packs, voiceId);
        return VoicePacks.voiceId(packs.get(target[0]).pack, target[1]);
    }

    /**
     * 找出 id 该落到哪个包、哪个 sid 上，返回 {@code {包下标, sid}}。
     * 包在、但发音人认不出来时，落到这个包的默认发音人。
     */
    @NonNull
    private static int[] locate(@NonNull List<Loaded> packs, @Nullable String voiceId) {
        VoicePacks.Address address = VoicePacks.parse(voiceId);
        if (address != null) {
            for (int i = 0; i < packs.size(); i++) {
                Loaded item = packs.get(i);
                if (item.pack != address.pack) {
                    continue;
                }
                if (VoicePacks.roleForSid(item.pack, address.sid) != null) {
                    return new int[]{i, address.sid};
                }
                return new int[]{i, fallbackSid(item)};
            }
        }
        return new int[]{0, fallbackSid(packs.get(0))};
    }

    /** 包在、但存着的发音人不可用时的落脚点。 */
    private static int fallbackSid(@NonNull Loaded item) {
        for (VoicePacks.Role role : item.roles) {
            if (role.sid == item.pack.defaultSid) {
                return role.sid;
            }
        }
        return item.roles.get(0).sid;
    }

    @Nullable
    private Loaded loadedFor(@NonNull String voiceId) {
        List<Loaded> packs = loaded();
        return packs.isEmpty() ? null : packs.get(locate(packs, voiceId)[0]);
    }

    private static int sidFor(@NonNull Loaded item, @Nullable String voiceId) {
        VoicePacks.Address address = VoicePacks.parse(voiceId);
        if (address != null && address.pack == item.pack
                && VoicePacks.roleForSid(item.pack, address.sid) != null) {
            return address.sid;
        }
        return fallbackSid(item);
    }

    /** 快照一份已加载的包，供合成线程安全遍历（{@code loaded} 会被 shutdown 清空）。 */
    @NonNull
    private List<Loaded> loaded() {
        synchronized (lock) {
            return new ArrayList<>(loaded);
        }
    }

    private boolean isLoaded(@NonNull Loaded item) {
        synchronized (lock) {
            return loaded.contains(item);
        }
    }

    private int roleCount() {
        int total = 0;
        for (Loaded item : loaded()) {
            total += item.roles.size();
        }
        return total;
    }

    // ------------------------------------------------------------------ 参数

    /**
     * 语速可调（合成时作为 speed 传下去），音量可调（AudioTrack 上改），
     * 音调没有对应参数——VITS 只有时长这一个旋钮，改音高得另训模型。见 TTS.md。
     */
    @Override
    public void apply(@NonNull TtsConfig config) {
        AudioTrack current;
        synchronized (lock) {
            pendingRate = config.getRate();
            pendingVolume = config.getVolume();
            // 音调刻意忽略：VITS 只有时长（语速）一个旋钮，没有音高参数
            current = track;
        }
        if (current != null) {
            try {
                current.setVolume(clampVolume(pendingVolume()));
            } catch (Throwable ignored) {
                // 正在收尾的 track：音量这次不生效，下一句重新建 track 时会带上
            }
        }
        String voiceId = config.getVoiceId();
        if (voiceId != null && !voiceId.isEmpty()) {
            setVoice(voiceId);
        }
    }

    private float pendingVolume() {
        synchronized (lock) {
            return pendingVolume;
        }
    }

    private static float clampVolume(float volume) {
        if (volume < 0f) {
            return 0f;
        }
        return volume > 1f ? 1f : volume;
    }

    // ------------------------------------------------------------------ 朗读

    /**
     * 一轮朗读的共享状态。合成线程往 {@link #queue} 里放，播放线程从里面取。
     * <p>
     * 「轮次」（{@link #gen}）是这里唯一的同步手段：{@code stop()}、下一次 {@code speak()}、
     * {@code shutdown()} 都只是把 {@code SherpaTts.generation} 加一，两个线程各自在循环里
     * 核对自己的轮次，过期就收工。这样「停止」不需要去中断任何一个线程。
     */
    private static final class Round {

        final int gen;
        /** 有界队列：合成跑得太快时阻塞在这里，天然形成背压，也限制内存占用。 */
        @NonNull
        final ArrayBlockingQueue<float[]> queue = new ArrayBlockingQueue<>(QUEUE_DEPTH);
        /** 本轮在用的 AudioTrack，供 {@code stop()} 掐断阻塞中的写入。 */
        @Nullable
        volatile AudioTrack track;
        /** 合成线程已收工（正常或出错）。 */
        volatile boolean synthesized;
        /**
         * 播放线程已经退出。合成线程必须盯着它：否则一旦播放线程因为建不出 AudioTrack
         * 之类的原因先走了，合成线程会在「队列满」上一直阻塞，把那个单线程执行器占死，
         * 之后所有 {@code speak()} 都排不上队。
         */
        volatile boolean consumerGone;
        /** 收场方式：非空代表这一轮要以错误结束。 */
        @Nullable
        volatile String error;
        /** 已经写进 AudioTrack 的帧数。 */
        volatile long framesWritten;
        /**
         * 本轮的合成回调。native 是按回调对象的<b>类</b>去查方法的，所以一轮里必须复用
         * 同一个实例（见 {@link ChunkCallback}），每联新建一个没有意义。
         */
        @Nullable
        Function1<float[], Integer> callback;

        Round(int gen) {
            this.gen = gen;
        }
    }

    @Override
    public void speak(@NonNull String text) {
        float rate;
        float volume;
        String voice;
        synchronized (lock) {
            if (state != STATE_READY || loaded.isEmpty()) {
                dispatchError(string(R.string.tts_play_failed));
                return;
            }
            rate = pendingRate;
            volume = pendingVolume;
            voice = resolve(currentVoiceId);
        }
        // 用哪套模型、哪个发音人，开新一轮之前就定下来：读到一半被换了音色不是用户想要的
        Loaded target = loadedFor(voice);
        if (target == null) {
            dispatchError(string(R.string.tts_play_failed));
            return;
        }
        // 古读/叶韵替换放在切分之前：它只换字、不动标点和换行，所以 TextChunker 照旧能按联切
        List<String> chunks = TextChunker.split(PoetryReadings.forSpeech(text));
        if (chunks.isEmpty()) {
            dispatchError(string(R.string.tts_play_failed));
            return;
        }
        // 先作废上一轮并掐断在播的声音，再开新一轮
        Round round = new Round(newRound());
        speaking = true;
        // 立刻反馈成「正在朗读」：第一段合成还要几百毫秒，等它出来再报太迟钝
        dispatchStart();
        // 合成与播放是两个线程，中间隔一个有界队列——这是「不卡顿」的全部秘密：
        // 合成可以领先播放若干段，播放线程永远有东西可写，不必等合成算完
        startPlayer(round, target.sampleRate, clampVolume(volume));
        worker().execute(() -> synthesize(round, chunks, target, sidFor(target, voice), rate));
    }

    /** 本轮是否已作废（被 stop()、下一次 speak() 或 shutdown() 顶掉）。 */
    private boolean expired(@NonNull Round round) {
        return round.gen != generation;
    }

    /**
     * 本轮是否已经没人要了：被顶掉，或者播放线程已经退出。
     * 合成侧一律用它判断要不要继续——只看 {@link #expired} 会在播放侧异常退出时卡住。
     */
    private boolean abandoned(@NonNull Round round) {
        return expired(round) || round.consumerGone;
    }

    /**
     * 合成线程：逐块合成，把音频塞进队列。
     * <p>
     * 用 {@link OfflineTts#generateWithCallback} 而不是 {@code generate()}：sherpa-onnx 会把
     * 收到的文本再按标点切成句、逐句合成，每合成完一句就回调一次。于是多了三件事——
     * <ol>
     *   <li>第一句出来就能播，不必等整块合成完；</li>
     *   <li>回调返回 {@code 0} 能让 native 层<b>立刻</b>停止生成，「停止」之后不再白算；</li>
     *   <li>回调里往队列阻塞地放，等于给 native 施加背压（队列满就暂停生成）。</li>
     * </ol>
     * 回调本身有讲究，不能写成 lambda，见 {@link ChunkCallback}。
     */
    private void synthesize(@NonNull Round round, @NonNull List<String> chunks,
                            @NonNull Loaded target, int sid, float rate) {
        try {
            // 联间静音只分配一次，反复入队没问题——播放侧只读它
            float[] gap = null;
            for (int i = 0; i < chunks.size(); i++) {
                // 包可能刚被 shutdown 释放掉，那样再喂给 native 就是崩
                if (abandoned(round) || !isLoaded(target)) {
                    return;
                }
                String chunk = chunks.get(i);
                target.engine.generateWithCallback(chunk, sid, rate, callbackFor(round));
                if (i + 1 < chunks.size() && !abandoned(round)) {
                    if (gap == null) {
                        gap = new float[Math.max(1,
                                (int) (target.sampleRate * BLOCK_GAP_MS / 1000L))];
                    }
                    enqueue(round, gap);
                }
            }
        } catch (Throwable error) {
            LogUtil.w("内置语音合成失败", error);
            if (!abandoned(round)) {
                round.error = string(R.string.tts_play_failed);
            }
        } finally {
            round.synthesized = true;
        }
    }

    /** 一轮复用一个回调对象；同一轮里要合成好几联，没必要每联新建一个。 */
    private Function1<float[], Integer> callbackFor(@NonNull Round round) {
        if (round.callback == null) {
            round.callback = new ChunkCallback(round);
        }
        return round.callback;
    }

    /**
     * 交给 native 的合成回调。返回值即「还要不要继续算」：{@code 0} 停，非 {@code 0} 继续。
     * <p>
     * <b>这里必须是类，不能写成 lambda 或匿名类。</b>sherpa-onnx 的 native 侧不是按接口调回调，
     * 而是拿回调对象的 class 去 {@code GetMethodID} 找「名字 + 描述符」为
     * {@code invoke([F)Ljava/lang/Integer;} 的方法——那是 Kotlin 编译 lambda 时会一并生成的
     * <i>特化签名</i>（Kotlin 的 lambda 类同时有 {@code invoke(float[])} 和桥
     * {@code invoke(Object)}）。javac 生成的 lambda 类只有桥那一条，于是 GetMethodID 落空，
     * 抛出的 {@code NoSuchMethodError} 留在 JNI 的 pending exception 里，直到 native 下一次
     * 调 JNI（分配输出缓冲）才引爆，进程当场 SIGABRT——现象就是点一下播放直接闪回桌面。
     * <p>
     * 所以这里要写成一个「真的实现了 {@code invoke(float[])} 的类」：{@code Function1<float[], Integer>}
     * 在 Java 眼里接口方法就是 {@code Integer invoke(float[])}，照它实现即可，javac 会自己补上桥
     * {@code Object invoke(Object)}——正好凑成 Kotlin 那份 lambda 类的样子。
     * （别手动再写一个 {@code invoke(Object)}，那会被判成和接口方法同名同擦除而编译不过。）
     */
    private final class ChunkCallback implements Function1<float[], Integer> {

        private final Round round;

        ChunkCallback(@NonNull Round round) {
            this.round = round;
        }

        /** native 按这个方法名 + 描述符查找，改名或改签名都会让播放当场崩掉。 */
        public Integer invoke(float[] samples) {
            if (abandoned(round)) {
                return 0;
            }
            if (samples == null || samples.length == 0) {
                return 1;
            }
            // 复制一份再入队：数组的所有权在 native 侧，不该假设它不会被复用
            enqueue(round, Arrays.copyOf(samples, samples.length));
            return abandoned(round) ? 0 : 1;
        }
    }

    /** 把一段音频放进队列；队列满就在循环里等，直到放进去或本轮没人要了。 */
    private void enqueue(@NonNull Round round, @NonNull float[] samples) {
        while (!abandoned(round)) {
            try {
                if (round.queue.offer(samples, POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 起播放线程。优先级略高于合成线程，免得写缓冲的时候被合成抢走 CPU。 */
    private void startPlayer(@NonNull Round round, int sampleRate, float volume) {
        Thread player = new Thread(() -> playback(round, sampleRate, volume), "sherpa-tts-player");
        player.setPriority(Thread.NORM_PRIORITY + 2);
        player.start();
    }

    /**
     * 播放线程：从队列取音频、转 16bit PCM、写 AudioTrack。
     * <p>
     * 写是阻塞的，缓冲写满就停在这里等播放头——这正是我们要的：合成可以一直领先跑，
     * 只要缓冲里还有声音，播放就不断。收尾（等播放头追平、回调上层）也在这个线程上做，
     * 所以 {@code onDone} 一定发生在最后一个音播完之后。
     */
    private void playback(@NonNull Round round, int sampleRate, float volume) {
        AudioTrack local = null;
        short[] pcm = new short[0];
        // 本轮的电平增益，拿到第一段音频后定下来，整轮沿用（见 gainFor）
        float gain = 0f;
        try {
            while (!expired(round)) {
                float[] samples;
                try {
                    samples = round.queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (samples == null) {
                    // 合成收工且队列已空 = 这一轮读完了
                    if (round.synthesized && round.queue.isEmpty()) {
                        break;
                    }
                    continue;
                }
                if (local == null) {
                    // 采样率由模型给出，不必等第一段音频——Loaded 里已经存了
                    local = createTrack(sampleRate, volume);
                    if (local == null) {
                        round.error = string(R.string.tts_play_failed);
                        break;
                    }
                    if (!adopt(round, local)) {
                        // 建 track 的这点时间里被 stop() 了：别再往下走，交给 finally 释放
                        break;
                    }
                }
                if (pcm.length < samples.length) {
                    // 缓冲复用：段长会变，只在不够时扩容，避免每段一次大对象分配
                    pcm = new short[samples.length];
                }
                if (gain <= 0f) {
                    float peak = peakOf(samples);
                    gain = gainFor(peak);
                    LogUtil.i(String.format(Locale.US,
                            "本轮电平归一 x%.2f（峰值 %.3f）", gain, peak));
                }
                int frames = toPcm(samples, pcm, gain);
                if (!write(local, pcm, frames, round)) {
                    // 写失败（不是被停止）要如实报错，否则整段音频会被静默截断
                    if (!expired(round) && round.error == null) {
                        round.error = string(R.string.tts_play_failed);
                    }
                    break;
                }
                round.framesWritten += frames;
            }
        } catch (Throwable error) {
            LogUtil.w("内置语音播放失败", error);
            if (!expired(round)) {
                round.error = string(R.string.tts_play_failed);
            }
        } finally {
            if (expired(round)) {
                // 用户按了停止 / 又开了一轮：不回调，由 stop()/speak() 负责收尾
                speaking = false;
            } else if (round.error != null) {
                dispatchError(round.error);
                speaking = false;
            } else {
                // 写完不等于放完：缓冲里还压着没播的音频，等播放头追上再收尾，
                // 否则最后几个字会被 release() 直接吞掉
                drain(local, round.framesWritten, round);
                if (!expired(round)) {
                    logUnderruns(local);
                    speaking = false;
                    dispatchDone();
                }
            }
            releaseTrack(local, round);
            // 最后才置位：上面的收尾判断要区分「被停止」和「自己走完」
            round.consumerGone = true;
        }
    }

    /** 登记本轮在用的 track；登记期间再查一次是否作废，避免「漏停」一个刚建好的 track。 */
    private boolean adopt(@NonNull Round round, @NonNull AudioTrack local) {
        synchronized (lock) {
            if (round.gen != generation) {
                return false;
            }
            round.track = local;
            track = local;
            return true;
        }
    }

    private void releaseTrack(@Nullable AudioTrack local, @NonNull Round round) {
        if (local == null) {
            return;
        }
        synchronized (lock) {
            if (round.track == local) {
                round.track = null;
            }
            if (track == local) {
                track = null;
            }
        }
        try {
            local.pause();
            local.flush();
        } catch (Throwable ignored) {
            // 已经被 stop() 停过了
        }
        try {
            local.release();
        } catch (Throwable ignored) {
            // 重复释放：不该发生，但发生在后台线程上会直接崩，所以兜住
        }
    }

    // ------------------------------------------------------------------ 电平

    /**
     * 音量归一的目标峰值。留一点余量，别贴着 1.0。
     */
    private static final float TARGET_PEAK = 0.93f;

    /**
     * 增益上限，约 +14 dB。再高就不是「补电平」而是「放大底噪」了；
     * 而且增益是数字域的，抬不出信噪比。
     */
    private static final float MAX_GAIN = 5.0f;

    /**
     * 求这一轮该乘的增益。
     * <p>
     * <b>为什么要做这件事。</b>aishell3 的输出电平很低——实测「音色筛选」导出的 174 个
     * 发音人，峰值普遍只有 0.15–0.45，也就是<b>离满量程还差 7–15 dB</b>。原来直接把它
     * 乘 32767 交给 AudioTrack，等于 DAC 的行程只用了三成：在手机上听就是「又轻又远」，
     * 用户说的「不清晰」有很大一部分其实是这个。
     * <p>
     * 数字域抬音量不会改变信噪比（信号和底噪一起抬），但也<b>不会有任何损失</b>——
     * 空着的那 10 dB 纯属浪费。
     * <p>
     * <b>为什么按「轮」而不是按「句」。</b>一轮朗读里发音人是固定的，电平也稳定；
     * 逐句各自归一反而会让句与句之间的音量跳来跳去（pumping）。而不同发音人之间差
     * 8 dB 是客观存在的，所以这个增益必须<b>跟着发音人走</b>——用本轮第一段的峰值定下来，
     * 整轮沿用。
     */
    static float gainFor(float peak) {
        if (peak <= 0.01f) {
            // 基本是静音：没有可靠的电平可依，不放大
            return 1f;
        }
        // 只抬不压：模型输出本来就饱满时保持原样
        return Math.min(Math.max(TARGET_PEAK / peak, 1f), MAX_GAIN);
    }

    /** 一段采样的峰值。 */
    static float peakOf(@NonNull float[] samples) {
        float peak = 0f;
        for (float sample : samples) {
            float magnitude = Math.abs(sample);
            if (magnitude > peak) {
                peak = magnitude;
            }
        }
        return peak;
    }

    /** float → 16bit PCM（乘增益并软限幅），写进复用的缓冲。返回写入的帧数。 */
    private static int toPcm(@NonNull float[] samples, @NonNull short[] pcm, float gain) {
        int count = Math.min(samples.length, pcm.length);
        for (int i = 0; i < count; i++) {
            pcm[i] = (short) (limit(samples[i] * gain) * 32767f);
        }
        return count;
    }

    /** 软拐点：超过这个幅度才开始压缩。 */
    private static final float SOFT_KNEE = 0.8f;

    /**
     * 软限幅。
     * <p>
     * 电平增益是按<b>本轮第一段</b>的峰值定的、整轮沿用（见 {@link #gainFor}）。万一后面某段
     * 比首段更响，直接乘增益就会越过 1.0——硬截断是刺耳的爆音。这里在 0.8 以上接一段
     * 平滑曲线，渐近到 1.0：|x| ≤ 0.8 完全线性（听不出差别），越过去压缩越多，
     * 到 1.5 时输出 0.9996。既不会截断，也不会像 tanh 那样把整个动态范围压扁。
     */
    private static float limit(float value) {
        float magnitude = Math.abs(value);
        if (magnitude <= SOFT_KNEE) {
            return value;
        }
        float over = (magnitude - SOFT_KNEE) / (1f - SOFT_KNEE);
        float shaped = SOFT_KNEE + (1f - SOFT_KNEE) * (float) Math.tanh(over);
        return value < 0f ? -shaped : shaped;
    }

    /**
     * 阻塞写。缓冲写满就停在这里等播放头追上——「合成领先、播放跟上」全靠它。
     *
     * @return 是否整段写完；本轮作废或写失败时返回 false
     */
    private boolean write(@NonNull AudioTrack local, @NonNull short[] pcm, int frames,
                          @NonNull Round round) {
        int offset = 0;
        while (offset < frames) {
            if (expired(round)) {
                return false;
            }
            int n = local.write(pcm, offset, frames - offset);
            if (n <= 0) {
                return false;
            }
            offset += n;
        }
        return true;
    }

    /** 等播放头追上已写入的位置，最多等 {@link #DRAIN_TIMEOUT_MS}。 */
    private void drain(@Nullable AudioTrack local, long framesWritten, @NonNull Round round) {
        if (local == null || framesWritten <= 0) {
            return;
        }
        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (!expired(round)
                && (local.getPlaybackHeadPosition() & 0xFFFFFFFFL) < framesWritten
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 把欠载次数写进日志——这是「卡不卡」唯一可信的客观指标。
     * 一次完整朗读里它不再增长，就说明播放线程一次都没被饿着（见 {@code TTS.md} 自测清单）。
     */
    private static void logUnderruns(@Nullable AudioTrack local) {
        if (local == null) {
            return;
        }
        try {
            LogUtil.i("本轮播放欠载 " + local.getUnderrunCount() + " 次");
        } catch (Throwable ignored) {
            // 个别 ROM 上取不到，不影响播放
        }
    }

    @Nullable
    private AudioTrack createTrack(int sampleRate, float volume) {
        int rate = sampleRate > 0 ? sampleRate : VoicePacks.AISHELL3.fallbackSampleRate;
        int min = AudioTrack.getMinBufferSize(rate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            min = rate * 2;  // 拿不到建议值：按 0.5 秒的 16bit 单声道算
        }
        // 缓冲给到 1 秒而不是「最小值 ×2」：合成线程算下一句的时候，播放线程还有一大截
        // 声音可放，不会立刻断。见 BUFFER_SECONDS 的注释。
        int bufferBytes = Math.max(min, (int) (rate * 2 * BUFFER_SECONDS));
        try {
            AudioTrack created = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (created.getState() != AudioTrack.STATE_INITIALIZED) {
                created.release();
                LogUtil.w("AudioTrack 没初始化成功");
                return null;
            }
            created.setVolume(volume);
            created.play();
            return created;
        } catch (Throwable error) {
            LogUtil.w("建 AudioTrack 失败", error);
            return null;
        }
    }

    /**
     * 开新一轮：作废上一轮的回调，并掐断正在播的声音。
     * {@code AudioTrack.pause()} 会让另一个线程里阻塞中的 {@code write()} 立刻返回，
     * 这就是「停止」能即时静音的原因。
     *
     * @return 新的轮次号
     */
    private int newRound() {
        AudioTrack current;
        int gen;
        synchronized (lock) {
            gen = ++generation;
            current = track;
            track = null;
        }
        if (current != null) {
            try {
                current.pause();
                current.flush();
            } catch (Throwable ignored) {
                // 已经释放了：什么都不用做
            }
        }
        return gen;
    }

    @Override
    public void stop() {
        newRound();
        speaking = false;
    }

    @Override
    public boolean isSpeaking() {
        return speaking;
    }

    @Override
    public void shutdown() {
        newRound();
        speaking = false;
        ExecutorService current;
        synchronized (lock) {
            state = STATE_IDLE;
            currentVoiceId = "";
            current = worker;
            worker = null;
        }
        if (current != null) {
            // 在同一个单线程上释放 native 引擎，避免和正在跑的 generate 撞车
            current.execute(this::releaseEngines);
            current.shutdown();
        } else {
            releaseEngines();
        }
    }

    private void releaseEngines() {
        List<Loaded> packs;
        synchronized (lock) {
            packs = new ArrayList<>(loaded);
            loaded.clear();
        }
        for (Loaded item : packs) {
            try {
                item.engine.release();
            } catch (Throwable error) {
                LogUtil.w("释放 native 引擎失败（" + item.pack.id + "）", error);
            }
        }
    }

    // ------------------------------------------------- 发音人筛选（只给调试构建用）

    /**
     * 下面这几个方法只服务于 {@code src/debug} 里的音色筛选工具
     * （{@code VoiceAuditionActivity}，见 {@code TTS-ALTERNATIVES.md} §6.3）：
     * 正常朗读永远走 {@link #speak}，业务代码不该调用它们。
     * <p>
     * 存在的理由：{@link VoicePacks} 只登记了从 174 个发音人里按基频挑出来的 15 个，
     * 而「音色好不好」跟基频没有关系。要重新挑人，就得能把<b>每一个</b> sid 都合成一遍
     * 听一听、量一量。
     */

    /**
     * 模型认到的发音人总数（含没登记的那些）；引擎没就绪时返回 0。
     * <p>
     * 取「引擎认到的个数」和「{@code speakers.txt} 的行数」里小的那个：
     * 只有两边都有，这个 sid 才既有声音、又有可写进 {@link VoicePacks} 的官方编号。
     * 两边不一致时上面 {@link #open} 已经告过警了。
     */
    int speakerCount() {
        List<Loaded> packs = loaded();
        if (packs.isEmpty()) {
            return 0;
        }
        Loaded item = packs.get(0);
        return Math.min(item.numSpeakers, item.speakerIds.size());
    }

    /** 采样率；引擎没就绪时返回 0。 */
    int sampleRate() {
        List<Loaded> packs = loaded();
        return packs.isEmpty() ? 0 : packs.get(0).sampleRate;
    }

    /** {@code speakers.txt} 里第 {@code sid} 行的官方发音人 id。 */
    @Nullable
    String speakerId(int sid) {
        List<Loaded> packs = loaded();
        if (packs.isEmpty()) {
            return null;
        }
        List<String> ids = packs.get(0).speakerIds;
        return sid >= 0 && sid < ids.size() ? ids.get(sid) : null;
    }

    /**
     * 指定 sid 合成一段，返回 [-1,1] 的 float 采样（不播放、不入队）。
     * 必须是后台线程调用——一次合成要几百毫秒。
     */
    @Nullable
    float[] synthesizeForAudition(@NonNull String text, int sid, float rate) {
        List<Loaded> packs = loaded();
        if (packs.isEmpty()) {
            return null;
        }
        Loaded target = packs.get(0);
        if (sid < 0 || sid >= target.numSpeakers) {
            return null;
        }
        GeneratedAudio audio = target.engine.generate(text, sid, rate);
        return audio == null ? null : audio.getSamples();
    }

    // ------------------------------------------------------------------ 线程与回调

    @NonNull
    private ExecutorService worker() {
        synchronized (lock) {
            if (worker == null || worker.isShutdown()) {
                worker = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "sherpa-tts");
                    thread.setPriority(Thread.NORM_PRIORITY);
                    return thread;
                });
            }
            return worker;
        }
    }

    private void dispatchReady(@Nullable Runnable onReady) {
        if (onReady != null) {
            main.post(onReady);
        }
    }

    private void dispatchStart() {
        Listener target = listener;
        if (target != null) {
            main.post(target::onStart);
        }
    }

    private void dispatchDone() {
        Listener target = listener;
        if (target != null) {
            main.post(target::onDone);
        }
    }

    private void dispatchError(@NonNull String message) {
        Listener target = listener;
        if (target != null) {
            main.post(() -> target.onError(message));
        }
    }

    @NonNull
    private String string(int resId) {
        Context context;
        synchronized (lock) {
            context = appContext;
        }
        return context == null ? "" : context.getString(resId);
    }

    @NonNull
    private String string(int resId, @NonNull Object... args) {
        Context context;
        synchronized (lock) {
            context = appContext;
        }
        return context == null ? "" : context.getString(resId, args);
    }
}

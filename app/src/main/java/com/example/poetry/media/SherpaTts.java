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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** 模型拷好之后落一个标记文件，避免每次启动重新拷 30 MB。 */
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

    /** 合成线程数。手机上有 2 个就够；再多只会和 UI 抢核。 */
    private static final int NUM_THREADS = 2;

    /** 每次交给 AudioTrack 的帧数，取小一点是为了让「停止」切得更干脆。 */
    private static final int WRITE_FRAMES = 1024;

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

        Loaded(@NonNull VoicePacks.Pack pack, @NonNull OfflineTts engine, int sampleRate,
               @NonNull List<VoicePacks.Role> roles) {
            this.pack = pack;
            this.engine = engine;
            this.sampleRate = sampleRate;
            this.roles = roles;
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

    /** 播放轮次：递增即作废上一轮，迟到的回调据此丢弃。 */
    private int generation;
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
        model.setNumThreads(NUM_THREADS);
        model.setDebug(false);
        model.setProvider("cpu");
        OfflineTtsConfig config = new OfflineTtsConfig(
                model, ruleFsts(pack, base), /* ruleFars= */ "", /* maxNumSentences= */ 1,
                /* silenceScale= */ 0.2f);
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
        LogUtil.i("「" + pack.id + "」就绪, sampleRate=" + sampleRate
                + ", speakers=" + engine.numSpeakers() + ", 可用音色=" + roles.size());
        return new Loaded(pack, engine, sampleRate, roles);
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
     * 把模型从 assets 拷到 filesDir，只在第一次做；已经拷过返回已有目录。
     * 缺文件返回 null（不算异常，是「这个包没带模型」）。
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
        if (marker.isFile()) {
            return dir;
        }
        // 上次拷到一半就被杀掉：标记文件没写成，这里从头来一遍
        deleteRecursively(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("建不了目录 " + dir);
        }
        for (String name : pack.files) {
            copy(assets, pack.assetDir + "/" + name, new File(dir, name));
        }
        if (!marker.createNewFile()) {
            throw new IOException("写不了标记文件 " + marker);
        }
        return dir;
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
        List<String> chunks = TextChunker.split(text);
        if (chunks.isEmpty()) {
            dispatchError(string(R.string.tts_play_failed));
            return;
        }
        // 先作废上一轮并掐断在播的声音，再开新一轮
        final int gen = newRound();
        speaking = true;
        // 立刻反馈成「正在朗读」：第一段合成还要几百毫秒，等它出来再报太迟钝
        dispatchStart();
        worker().execute(() -> play(chunks, gen, target, sidFor(target, voice), rate, volume));
    }

    /** 逐句合成、逐句播放。整条链都在合成线程上，主线程只收回调。 */
    private void play(@NonNull List<String> chunks, int gen, @NonNull Loaded target, int sid,
                      float rate, float volume) {
        AudioTrack local = null;
        long framesWritten = 0L;
        try {
            for (String chunk : chunks) {
                // 包可能刚被 shutdown 释放掉，那样再喂给 native 就是崩
                if (gen != generation || !isLoaded(target)) {
                    return;
                }
                GeneratedAudio audio = target.engine.generate(chunk, sid, rate);
                if (audio == null) {
                    throw new IOException("合成返回空结果");
                }
                float[] samples = audio.getSamples();
                if (samples == null || samples.length == 0) {
                    continue;
                }
                if (local == null) {
                    // 采样率由模型给出，拿到第一段音频才知道
                    local = createTrack(target.sampleRate, clampVolume(volume));
                    if (local == null) {
                        throw new IOException("AudioTrack 初始化失败");
                    }
                    track = local;
                }
                int written = write(local, samples, gen);
                if (written < 0) {
                    return;
                }
                framesWritten += written;
            }
            if (gen != generation) {
                return;
            }
            // 写完不等于放完：流式 track 里还压着没播的缓冲，等播放头追上再收尾，
            // 否则最后几个字会被 release() 直接吞掉
            drain(local, framesWritten, gen);
            if (gen == generation) {
                dispatchDone();
            }
        } catch (Throwable error) {
            LogUtil.w("内置语音合成失败", error);
            if (gen == generation) {
                dispatchError(string(R.string.tts_play_failed));
            }
        } finally {
            if (gen == generation) {
                speaking = false;
            }
            if (local != null) {
                synchronized (lock) {
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
                local.release();
            }
        }
    }

    /**
     * 把 float 采样转成 16bit PCM 写进 AudioTrack。阻塞写，所以返回就代表「排进缓冲了」。
     *
     * @return 写入的帧数；被下一轮 / stop() 打断，或写失败时返回 -1
     */
    private int write(@NonNull AudioTrack local, @NonNull float[] samples, int gen) {
        short[] pcm = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float value = samples[i];
            if (value > 1f) {
                value = 1f;
            } else if (value < -1f) {
                value = -1f;
            }
            pcm[i] = (short) (value * 32767f);
        }
        int offset = 0;
        while (offset < pcm.length) {
            if (gen != generation) {
                return -1;
            }
            int count = Math.min(WRITE_FRAMES, pcm.length - offset);
            int n = local.write(pcm, offset, count);
            if (n <= 0) {
                return -1;
            }
            offset += n;
        }
        return pcm.length;
    }

    /** 等播放头追上已写入的位置，最多等 {@link #DRAIN_TIMEOUT_MS}。 */
    private void drain(@Nullable AudioTrack local, long framesWritten, int gen) {
        if (local == null || framesWritten <= 0) {
            return;
        }
        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (gen == generation
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

    @Nullable
    private AudioTrack createTrack(int sampleRate, float volume) {
        int rate = sampleRate > 0 ? sampleRate : VoicePacks.AISHELL3.fallbackSampleRate;
        int min = AudioTrack.getMinBufferSize(rate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            min = rate;  // 拿不到建议值：约 0.5 秒的缓冲
        }
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
                    .setBufferSizeInBytes(min * 2)
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

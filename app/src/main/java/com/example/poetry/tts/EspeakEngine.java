package com.example.poetry.tts;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.R;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Voice;
import com.example.poetry.media.SpeechEngine;
import com.reecedunn.espeak.SpeechSynthesis;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * espeak-ng 离线合成引擎。
 * <p>
 * 链路：文本 → {@link SpeechSynthesis#synthesize}（同步合成，逐块回调 16bit PCM）
 * → {@link AudioTrack} 播放 → 状态回调回主线程。
 * <p>
 * 两个关键约束：
 * <ol>
 *   <li>原生 synthesize 内部会调用 {@code espeak_Synchronize()}，是阻塞调用，必须跑在后台线程；</li>
 *   <li>初始化要读约 700KB 的 {@code phondata}，且需先完成 {@link EspeakData#ensureInstalled}，
 *       因此 {@link #prepare} 是异步的，期间 {@link #isReady()} 为 false。</li>
 * </ol>
 * 任何环节失败都不向上抛异常，而是通过 {@link #statusText} 与 onError 暴露，
 * 由 {@code Speaker} 门面切换到系统 TTS。
 */
public final class EspeakEngine implements SpeechEngine, SpeechSynthesis.SynthReadyCallback {

    private static final String TAG = "EspeakEngine";

    /** espeak 官方默认语速（words per minute） */
    private static final int RATE_BASE = 175;
    /** espeak 音调基准值 0-100 */
    private static final int PITCH_BASE = 50;

    private static volatile EspeakEngine instance;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object trackLock = new Object();

    private volatile Context appContext;
    private volatile SpeechSynthesis synth;
    private volatile List<com.reecedunn.espeak.Voice> nativeVoices = Collections.emptyList();
    private volatile String failureReason = "";
    private volatile String currentVoiceId = "";
    private volatile boolean speaking;

    private Listener listener;
    private AudioTrack track;
    private volatile boolean cancelled;

    private float pendingRate = TtsConfig.RATE_DEFAULT;
    private float pendingPitch = TtsConfig.PITCH_DEFAULT;
    private float pendingVolume = TtsConfig.VOLUME_DEFAULT;
    private float pendingPitchRange = TtsConfig.PITCHRANGE_DEFAULT;
    private int pendingWordGap = TtsConfig.WORDGAP_DEFAULT;

    private EspeakEngine() {
    }

    public static EspeakEngine get() {
        if (instance == null) {
            synchronized (EspeakEngine.class) {
                if (instance == null) {
                    instance = new EspeakEngine();
                }
            }
        }
        return instance;
    }

    // ------------------------------------------------------------------ 初始化

    /**
     * 异步准备：释放数据 + 初始化原生库。可重复调用，内部有幂等判断。
     *
     * @param onReady 主线程回调，用于 UI 刷新状态（可为 null）
     */
    public void prepare(@NonNull Context context, @Nullable Runnable onReady) {
        final Context app = context.getApplicationContext();
        appContext = app;
        worker.execute(() -> {
            prepareInternal(app);
            if (onReady != null) {
                main.post(onReady);
            }
        });
    }

    private void prepareInternal(@NonNull Context app) {
        SpeechSynthesis current = synth;
        if (current != null && current.isInitialized()) {
            return;
        }
        if (!SpeechSynthesis.isLibraryLoaded()) {
            failureReason = app.getString(R.string.tts_status_no_library);
            Log.w(TAG, "libttsespeak.so 未加载");
            return;
        }
        File dir;
        try {
            // 优先用外部投放的（可能是全量语种），没有再从 assets 释放内置精简版
            dir = EspeakData.resolveDir(app);
            if (dir == null) {
                dir = EspeakData.ensureInstalled(app);
            }
        } catch (Throwable error) {
            Log.w(TAG, "释放语音数据失败", error);
            failureReason = app.getString(R.string.tts_status_no_data);
            return;
        }
        if (dir == null) {
            failureReason = app.getString(R.string.tts_status_no_data);
            return;
        }
        try {
            File parent = dir.getParentFile();
            SpeechSynthesis created = new SpeechSynthesis(
                    parent == null ? dir.getAbsolutePath() : parent.getAbsolutePath(), this);
            if (!created.isInitialized()) {
                failureReason = app.getString(R.string.tts_status_init_failed);
                return;
            }
            synth = created;
            nativeVoices = addFormantVariants(filterVoices(created.getAvailableVoices(), dir));
            applyPending();
            if (!currentVoiceId.isEmpty()) {
                doSetVoice(currentVoiceId);
            }
            failureReason = "";
            Log.i(TAG, "espeak-ng 就绪，可用音色 " + nativeVoices.size() + " 个，采样率 "
                    + created.getSampleRate());
        } catch (Throwable error) {
            Log.w(TAG, "初始化 espeak-ng 失败", error);
            failureReason = app.getString(R.string.tts_status_init_failed);
        }
    }

    /**
     * 过滤音色：剔除缺少词典的语种、依赖外部 mbrola 库的条目，
     * 以及本项目不提供的英语（中文诗词 App 只需中文音色）。
     * 最后再补上「女声」变体，避免 UI 里出现「选了也发不出声」的选项。
     */
    @NonNull
    private List<com.reecedunn.espeak.Voice> filterVoices(
            @NonNull List<com.reecedunn.espeak.Voice> voices, @NonNull File dataDir) {
        List<com.reecedunn.espeak.Voice> result = new ArrayList<>();
        for (com.reecedunn.espeak.Voice voice : voices) {
            if (voice == null || voice.name == null) {
                continue;
            }
            String id = voice.identifier == null ? "" : voice.identifier;
            if (voice.name.startsWith("mb") || id.contains("/mb/")) {
                continue; // mbrola 需要额外二进制与语音库，本项目未集成
            }
            // 排除拉丁转写变体（cmn-Latn-pinyin / yue-Latn-jyutping 等）：
            // 它们按拼音 / 粤拼朗读罗马字，不是中文发音，混入列表会误导用户
            if (voice.name.contains("Latn")) {
                continue;
            }
            String lang = langCodeOf(voice.name);
            if (lang.isEmpty() || "en".equals(lang)) {
                continue; // 不提供英语语音
            }
            File dict = new File(dataDir, lang + "_dict");
            if (!dict.exists() || dict.length() <= 0) {
                continue;
            }
            result.add(voice);
        }
        Collections.sort(result, new Comparator<com.reecedunn.espeak.Voice>() {
            @Override
            public int compare(com.reecedunn.espeak.Voice a, com.reecedunn.espeak.Voice b) {
                int delta = chineseRank(a.name) - chineseRank(b.name);
                if (delta != 0) {
                    return delta;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return result;
    }

    /**
     * 为每个可用的中文语种补一组「男声 / 女声」共振峰变体。
     * <p>
     * espeak-ng 用 {@code <语言>+<变体>} 的组合名加载共振峰变体，对应
     * {@code voices/!v/<变体>} 文件（m1-m8 为不同男声共振峰，f1-f5 为女声）。
     * 这里手动构造并追加到列表，让用户能在界面里挑选最不「洋腔」的男声 / 女声。
     * <p>
     * 每种性别给 5 档（低沉→明亮 / 柔和→甜美），覆盖足够音色又不至于列表过长；
     * 若数据里缺某个变体文件（如外部全量数据不完整），加载会失败，
     * 但选择时 {@link #doSetVoice} 已做保护，不会让 App 崩溃。
     */
    @NonNull
    private List<com.reecedunn.espeak.Voice> addFormantVariants(
            @NonNull List<com.reecedunn.espeak.Voice> base) {
        if (base.isEmpty()) {
            return base;
        }
        java.util.Set<String> langs = new java.util.HashSet<>();
        for (com.reecedunn.espeak.Voice voice : base) {
            langs.add(langCodeOf(voice.name));
        }
        List<com.reecedunn.espeak.Voice> result = new ArrayList<>(base);
        for (String lang : new String[]{"cmn", "yue", "hak"}) {
            if (!langs.contains(lang)) {
                continue;
            }
            for (String variant : VARIANT_INFO.keySet()) {
                final String variantId = lang + "+" + variant;
                boolean exists = false;
                for (com.reecedunn.espeak.Voice voice : result) {
                    if (variantId.equals(voice.identifier)) {
                        exists = true;
                        break;
                    }
                }
                if (exists) {
                    continue;
                }
                VariantInfo info = VARIANT_INFO.get(variant);
                result.add(new com.reecedunn.espeak.Voice(
                        variantId, variantId, info.gender, 0, Locale.SIMPLIFIED_CHINESE));
            }
        }
        // 重新排序：按语种分组，组内女声在前、男声在后，再按档位顺序，方便浏览
        Collections.sort(result, VOICE_COMPARATOR);
        return result;
    }

    /** 共振峰变体的展示信息：性别 + 中文档位标签 + 组内排序权重 */
    private static final class VariantInfo {
        final int gender;
        final String label;
        final int order;

        VariantInfo(int gender, @NonNull String label, int order) {
            this.gender = gender;
            this.label = label;
            this.order = order;
        }
    }

    /** 男声 m1-m8 / 女声 f1-f5 共振峰变体（espeak voices/!v/ 下实际存在） */
    private static final java.util.Map<String, VariantInfo> VARIANT_INFO = new java.util.HashMap<>();

    static {
        VARIANT_INFO.put("m1", new VariantInfo(SpeechSynthesis.GENDER_MALE, "低沉", 1));
        VARIANT_INFO.put("m2", new VariantInfo(SpeechSynthesis.GENDER_MALE, "浑厚", 2));
        VARIANT_INFO.put("m3", new VariantInfo(SpeechSynthesis.GENDER_MALE, "标准", 3));
        VARIANT_INFO.put("m4", new VariantInfo(SpeechSynthesis.GENDER_MALE, "清亮", 4));
        VARIANT_INFO.put("m5", new VariantInfo(SpeechSynthesis.GENDER_MALE, "明亮", 5));
        VARIANT_INFO.put("f1", new VariantInfo(SpeechSynthesis.GENDER_FEMALE, "柔和", 1));
        VARIANT_INFO.put("f2", new VariantInfo(SpeechSynthesis.GENDER_FEMALE, "温婉", 2));
        VARIANT_INFO.put("f3", new VariantInfo(SpeechSynthesis.GENDER_FEMALE, "标准", 3));
        VARIANT_INFO.put("f4", new VariantInfo(SpeechSynthesis.GENDER_FEMALE, "清脆", 4));
        VARIANT_INFO.put("f5", new VariantInfo(SpeechSynthesis.GENDER_FEMALE, "甜美", 5));
    }

    /** 取组合名里的变体后缀（"cmn+m3" → "m3"），非组合名返回 null */
    @Nullable
    private static String variantOf(@NonNull String identifier) {
        int plus = identifier.indexOf('+');
        return plus > 0 ? identifier.substring(plus + 1) : null;
    }

    /** 音色列表排序：先按语种，再女声在前男声在后，最后按档位；非变体（基础音色）排最前 */
    private static final Comparator<com.reecedunn.espeak.Voice> VOICE_COMPARATOR =
            new Comparator<com.reecedunn.espeak.Voice>() {
                @Override
                public int compare(com.reecedunn.espeak.Voice a, com.reecedunn.espeak.Voice b) {
                    int rank = chineseRank(a.name) - chineseRank(b.name);
                    if (rank != 0) {
                        return rank;
                    }
                    int ga = genderRank(a);
                    int gb = genderRank(b);
                    if (ga != gb) {
                        return ga - gb;
                    }
                    return orderOf(a) - orderOf(b);
                }

                private int genderRank(@NonNull com.reecedunn.espeak.Voice v) {
                    VariantInfo info = variantInfoOf(v);
                    if (info == null) {
                        return 0; // 基础音色
                    }
                    // 女声排前面
                    return info.gender == SpeechSynthesis.GENDER_FEMALE ? 1 : 2;
                }

                private int orderOf(@NonNull com.reecedunn.espeak.Voice v) {
                    VariantInfo info = variantInfoOf(v);
                    return info == null ? 0 : info.order;
                }
            };

    /**
     * 默认发音人：优先普通话，其次粤语 / 客家话，最后是列表首个可用项；
     * 跳过拉丁转写变体（它们按拼音/粤拼朗读，不是中文发音）。
     */
    @Nullable
    private String pickDefaultVoice() {
        String fallback = null;
        for (com.reecedunn.espeak.Voice voice : nativeVoices) {
            if (voice.identifier == null || voice.name.contains("Latn")) {
                continue;
            }
            if (fallback == null) {
                fallback = voice.identifier;
            }
            if ("cmn".equals(langCodeOf(voice.name))) {
                return voice.identifier;
            }
        }
        return fallback;
    }

    private static int chineseRank(@NonNull String name) {
        switch (langCodeOf(name)) {
            case "cmn":
                return 0;
            case "yue":
                return 1;
            case "hak":
                return 2;
            case "zh":
                return 3;
            default:
                return 10;
        }
    }

    @NonNull
    private static String langCodeOf(@NonNull String name) {
        int dash = name.indexOf('-');
        if (dash > 0) {
            return name.substring(0, dash);
        }
        int plus = name.indexOf('+');
        return plus > 0 ? name.substring(0, plus) : name;
    }

    // ------------------------------------------------------------------ SpeechEngine

    @Override
    public boolean isReady() {
        SpeechSynthesis engine = synth;
        return engine != null && engine.isInitialized();
    }

    @NonNull
    @Override
    public String statusText(@NonNull Context context) {
        if (!SpeechSynthesis.isLibraryLoaded()) {
            return context.getString(R.string.tts_status_no_library);
        }
        if (isReady()) {
            String version = SpeechSynthesis.getVersion();
            return version.isEmpty()
                    ? context.getString(R.string.tts_status_ready)
                    : context.getString(R.string.tts_status_ready_version, version);
        }
        if (!failureReason.isEmpty()) {
            return failureReason;
        }
        return context.getString(R.string.tts_status_preparing);
    }

    @Override
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public List<Voice> listVoices() {
        List<com.reecedunn.espeak.Voice> source = nativeVoices;
        List<Voice> result = new ArrayList<>();
        if (source.isEmpty()) {
            result.add(placeholder());
            return result;
        }
        for (com.reecedunn.espeak.Voice voice : source) {
            result.add(toAppVoice(voice));
        }
        return result;
    }

    @NonNull
    private Voice toAppVoice(@NonNull com.reecedunn.espeak.Voice voice) {
        VariantInfo info = variantInfoOf(voice);
        String gender = (voice.gender == SpeechSynthesis.GENDER_FEMALE) ? "女声" : "男声";
        Voice item = new Voice();
        item.setId(voice.identifier);
        item.setName(displayName(voice));
        if (info != null) {
            item.setDesc("共振峰变体 · " + info.label);
            item.setTag2(gender + " · " + info.label);
        } else {
            item.setDesc(voice.identifier);
            item.setTag2(gender + " · " + voice.ageLabel());
        }
        item.setTag1("离线引擎");
        item.setLocale(voice.locale == null ? "zh-CN" : voice.locale.toLanguageTag());
        item.setSelected(voice.identifier.equals(currentVoiceId));
        return item;
    }

    @NonNull
    private Voice placeholder() {
        Voice item = new Voice();
        item.setId("");
        item.setName(text(R.string.voice_default_name));
        item.setDesc(text(R.string.tts_status_preparing));
        item.setTag1("离线引擎");
        item.setTag2("默认");
        item.setSelected(true);
        return item;
    }

    @NonNull
    private String displayName(@NonNull com.reecedunn.espeak.Voice voice) {
        VariantInfo info = variantInfoOf(voice);
        if (info != null) {
            String lang = langCodeOf(voice.name);
            String langLabel = LANGUAGE_NAMES.get(lang);
            if (langLabel == null) {
                langLabel = voice.locale == null ? lang
                        : voice.locale.getDisplayName(Locale.SIMPLIFIED_CHINESE);
            }
            String gender = info.gender == SpeechSynthesis.GENDER_FEMALE ? "女声" : "男声";
            return langLabel + " · " + gender + " · " + info.label;
        }
        // 基础音色（无共振峰变体）走原有逻辑
        String name = voice.name;
        int plus = name.indexOf('+');
        String baseName = plus > 0 ? name.substring(0, plus) : name;
        String lang = langCodeOf(baseName);
        String label = LANGUAGE_NAMES.get(lang);
        if (label == null) {
            label = voice.locale == null ? baseName
                    : voice.locale.getDisplayName(Locale.SIMPLIFIED_CHINESE);
        }
        if (!baseName.equals(lang)) {
            String extra = baseName.substring(lang.length()).replace('-', ' ').trim();
            if (!extra.isEmpty()) {
                label += " · " + extra;
            }
        }
        String gender = (voice.gender == SpeechSynthesis.GENDER_FEMALE) ? "女声" : "男声";
        return label + " · " + gender;
    }

    /** 取音色对应的共振峰变体信息（仅组合名音色有），无则 null */
    @Nullable
    private static VariantInfo variantInfoOf(@NonNull com.reecedunn.espeak.Voice voice) {
        if (voice.identifier == null) {
            return null;
        }
        String suffix = variantOf(voice.identifier);
        return suffix == null ? null : VARIANT_INFO.get(suffix);
    }

    @Override
    public boolean setVoice(@NonNull String voiceId) {
        currentVoiceId = voiceId;
        if (!isReady()) {
            return false;
        }
        return doSetVoice(voiceId);
    }

    private boolean doSetVoice(@NonNull String voiceId) {
        SpeechSynthesis engine = synth;
        if (engine == null) {
            return false;
        }
        if (voiceId.isEmpty()) {
            return true;
        }
        // 女声变体以组合名（如 cmn+f3）标识，必须走 setVoiceByName 才能正确套用共振峰变体
        if (voiceId.indexOf('+') >= 0) {
            return engine.setVoiceByName(voiceId);
        }
        com.reecedunn.espeak.Voice match = null;
        for (com.reecedunn.espeak.Voice voice : nativeVoices) {
            if (voice.identifier.equals(voiceId)) {
                match = voice;
                break;
            }
        }
        if (match == null) {
            return false;
        }
        // 优先按「语言名 + 性别 + 年龄」匹配（上游默认做法，最稳），失败再按标识精确选择
        if (engine.setVoiceByProperties(match.name, match.gender, match.age)) {
            return true;
        }
        return engine.setVoiceByName(match.identifier);
    }

    @Nullable
    @Override
    public String currentVoiceId() {
        return currentVoiceId;
    }

    @Override
    public void apply(@NonNull TtsConfig config) {
        pendingRate = config.getRate();
        pendingPitch = config.getPitch();
        pendingVolume = config.getVolume();
        pendingPitchRange = config.getPitchRange();
        pendingWordGap = config.getWordGap();
        if (!config.getVoiceId().isEmpty()) {
            setVoice(config.getVoiceId());
        }
        applyPending();
    }

    private void applyPending() {
        SpeechSynthesis engine = synth;
        if (engine == null) {
            return;
        }
        engine.setParameter(SpeechSynthesis.PARAM_RATE, toRate(pendingRate));
        engine.setParameter(SpeechSynthesis.PARAM_PITCH, toPitch(pendingPitch));
        engine.setParameter(SpeechSynthesis.PARAM_VOLUME, toVolume(pendingVolume));
        // 语调幅度：直接影响中文四声的起伏，是消「洋腔 / 平调」的关键
        engine.setParameter(SpeechSynthesis.PARAM_PITCH_RANGE, toPitchRange(pendingPitchRange));
        // 词间停顿：诗词吟诵可适度调高，逐字断词的中文不宜过高否则一字一顿
        engine.setParameter(SpeechSynthesis.PARAM_WORD_GAP, pendingWordGap);
    }

    /** 语速倍率 → 每分钟字数 */
    private static int toRate(float rate) {
        int wpm = Math.round(RATE_BASE * rate);
        return Math.min(SpeechSynthesis.RATE_MAX, Math.max(SpeechSynthesis.RATE_MIN, wpm));
    }

    /** 音调倍率 → 0-100（50 为基准） */
    private static int toPitch(float pitch) {
        int value = Math.round(PITCH_BASE * pitch);
        return Math.min(SpeechSynthesis.PITCH_MAX, Math.max(0, value));
    }

    /** 音量倍率 → 0-200（100 为满幅） */
    private static int toVolume(float volume) {
        int value = Math.round(100f * volume);
        return Math.min(SpeechSynthesis.VOLUME_MAX, Math.max(0, value));
    }

    /** 语调幅度倍率 → 0-100（50 为基准，对应 espeak PARAM_PITCH_RANGE） */
    private static int toPitchRange(float pitchRange) {
        int value = Math.round(50f * pitchRange);
        return Math.min(100, Math.max(0, value));
    }

    @Override
    public void speak(@NonNull String text) {
        if (!isReady()) {
            notifyError(failureReason.isEmpty() ? defaultError() : failureReason);
            return;
        }
        applyPending();
        worker.execute(() -> doSpeak(text));
    }

    private void doSpeak(@NonNull String text) {
        SpeechSynthesis engine = synth;
        if (engine == null) {
            notifyError(defaultError());
            return;
        }
        cancelled = false;
        AudioTrack localTrack = openTrack(engine.getSampleRate());
        if (localTrack == null) {
            notifyError(defaultError());
            return;
        }
        speaking = true;
        notifyStart();
        try {
            localTrack.play();
            // 同步合成：方法返回时整段音频已写完（或被 stop 中断）
            engine.synthesize(text, false);
        } catch (Throwable error) {
            Log.w(TAG, "朗读失败", error);
            notifyError(defaultError());
        } finally {
            releaseTrack(localTrack);
            speaking = false;
            notifyDone();
        }
    }

    @Override
    public void stop() {
        cancelled = true;
        SpeechSynthesis engine = synth;
        if (engine != null) {
            engine.stop(); // espeak_Cancel：中断合成，回调随即收尾
        }
        synchronized (trackLock) {
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                } catch (IllegalStateException ignored) {
                    // ignore
                }
            }
        }
    }

    @Override
    public boolean isSpeaking() {
        return speaking;
    }

    @Override
    public void shutdown() {
        stop();
        synth = null;
        speaking = false;
    }

    // ------------------------------------------------------------------ 音频

    @Nullable
    private AudioTrack openTrack(int sampleRate) {
        if (sampleRate <= 0) {
            Log.w(TAG, "采样率非法：" + sampleRate);
            return null;
        }
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioTrack 不支持该采样率：" + sampleRate);
            return null;
        }
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build();
            AudioTrack created = new AudioTrack(attrs, format, minBuffer * 2,
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            synchronized (trackLock) {
                releaseTrack(track);
                track = created;
            }
            return created;
        } catch (Throwable error) {
            Log.w(TAG, "创建 AudioTrack 失败", error);
            return null;
        }
    }

    private void releaseTrack(@Nullable AudioTrack target) {
        if (target == null) {
            return;
        }
        try {
            if (target.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                target.stop();
            }
        } catch (IllegalStateException ignored) {
            // ignore
        }
        try {
            target.release();
        } catch (Exception ignored) {
            // ignore
        }
        synchronized (trackLock) {
            if (track == target) {
                track = null;
            }
        }
    }

    /** 原生合成回调：一块 PCM16 数据（发生在 worker 线程） */
    @Override
    public void onSynthDataReady(byte[] audioData) {
        if (cancelled || audioData == null || audioData.length == 0) {
            return;
        }
        AudioTrack local;
        synchronized (trackLock) {
            local = track;
        }
        if (local == null || local.getState() != AudioTrack.STATE_INITIALIZED) {
            return;
        }
        try {
            local.write(audioData, 0, audioData.length);
        } catch (Exception error) {
            Log.w(TAG, "写入音频数据失败", error);
        }
    }

    /** 整段合成结束（或被 stop 中断） */
    @Override
    public void onSynthDataComplete() {
        AudioTrack local;
        synchronized (trackLock) {
            local = track;
        }
        if (local != null) {
            try {
                local.stop();
            } catch (IllegalStateException ignored) {
                // ignore
            }
        }
    }

    // ------------------------------------------------------------------ 回调派发

    private void notifyStart() {
        Listener target = listener;
        if (target != null) {
            main.post(target::onStart);
        }
    }

    private void notifyDone() {
        Listener target = listener;
        if (target != null) {
            main.post(target::onDone);
        }
    }

    private void notifyError(@Nullable final String message) {
        Listener target = listener;
        if (target == null) {
            return;
        }
        final String text = (message == null || message.isEmpty()) ? defaultError() : message;
        main.post(() -> target.onError(text));
    }

    @NonNull
    private String defaultError() {
        return text(R.string.tts_play_failed);
    }

    @NonNull
    private String text(int resId) {
        Context app = appContext;
        return app == null ? "" : app.getString(resId);
    }

    private static final Map<String, String> LANGUAGE_NAMES = new HashMap<>();

    static {
        LANGUAGE_NAMES.put("cmn", "普通话");
        LANGUAGE_NAMES.put("yue", "粤语");
        LANGUAGE_NAMES.put("hak", "客家话");
        LANGUAGE_NAMES.put("zh", "中文");
        LANGUAGE_NAMES.put("ja", "日语");
        LANGUAGE_NAMES.put("ko", "韩语");
        LANGUAGE_NAMES.put("fr", "法语");
        LANGUAGE_NAMES.put("de", "德语");
        LANGUAGE_NAMES.put("es", "西班牙语");
        LANGUAGE_NAMES.put("ru", "俄语");
        LANGUAGE_NAMES.put("it", "意大利语");
        LANGUAGE_NAMES.put("pt", "葡萄牙语");
        LANGUAGE_NAMES.put("ar", "阿拉伯语");
        LANGUAGE_NAMES.put("nl", "荷兰语");
        LANGUAGE_NAMES.put("la", "拉丁语");
    }
}

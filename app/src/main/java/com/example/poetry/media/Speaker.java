package com.example.poetry.media;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.R;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Voice;
import com.example.poetry.util.LogUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 朗读的唯一入口（UI 只认识这个类）。
 * <p>
 * 底下是 {@link EngineRouter}，它把两个引擎合成一个：内置离线引擎 {@link SherpaTts}
 * （sherpa-onnx + 随 APK 分发的中文 VITS 语音包，整条合成链路跑在本机，装上就能读，
 * 不需要联网、也不依赖任何第三方 App）和服务端代理的云端音色 {@link CloudTts}
 * （要联网、要登录、要会员）。语音包缺失或 native 库加载失败时 {@link #isReady()}
 * 为 false，界面应当给出引导而不是硬读。
 * <p>
 * 这一层另外管一件事：记住用户<i>选</i>的音色，好在引擎就绪后照它再下发一次。但界面上
 * 「当前是哪个音色」一律以 {@link #currentVoiceId()} 为准——它每次都问引擎，报的是真正
 * 会发声的那个，不是用户选的那个（两者不一定相同，见该方法）。
 */
public final class Speaker {

    private static volatile Speaker instance;

    private final SpeechEngine engine = EngineRouter.get();

    private Listener listener;

    /**
     * 用户选中的音色 id（<b>意图</b>，不是「实际会发声的那个」）。
     * <p>
     * 两者不是一回事：云端音色要会员 + 密钥都齐了才用得上，条件不满足时引擎的发声落回内置
     * 音色，而这里记的还是用户挑的那个——这是故意的，会员一开就该自动切回去，不该把他的
     * 选择抹掉。界面要显示「当前音色」时用 {@link #currentVoiceId()}，别读这个字段。
     */
    private volatile String selectedVoiceId = "";
    /** 最近一次 apply 下来的整份配置；改语速时以它为底，避免把其它字段冲掉。 */
    private final TtsConfig current = new TtsConfig();

    private final SpeechEngine.Listener engineListener = new SpeechEngine.Listener() {
        @Override
        public void onStart() {
            dispatchStart();
        }

        @Override
        public void onDone() {
            dispatchDone();
        }

        @Override
        public void onError(String message) {
            dispatchError(message);
        }

        @Override
        public void onNotice(String message) {
            dispatchNotice(message);
        }
    };

    private Speaker() {
    }

    public static Speaker get() {
        if (instance == null) {
            synchronized (Speaker.class) {
                if (instance == null) {
                    instance = new Speaker();
                }
            }
        }
        return instance;
    }

    /** Legacy alias retained for compatibility. */
    public interface Listener extends SpeechEngine.Listener {
    }

    /** 加载模型；回调可能在状态确定前就被调用，界面对两种时序都要安全。 */
    public void init(@NonNull Context context) {
        prepare(context, null);
    }

    /** 加载模型；onReady 在状态确定后回主线程调用（模型缺失时也会调）。 */
    public void prepare(@NonNull Context context, @Nullable Runnable onReady) {
        try {
            engine.setListener(engineListener);
            engine.prepare(context, () -> {
                // 引擎刚就绪时把用户选的音色再下发一次：加载完成前调的 setVoice 会被丢掉。
                // 下发的必须是「他选的」而不是「之前凑合用的」——云端音色当初没配上用不了，
                // 现在配好了就该切回去，这一句就是它唯一的机会。
                String chosen = selectedVoiceId;
                if (chosen != null && !chosen.isEmpty()) {
                    engine.setVoice(chosen);
                }
                if (onReady != null) {
                    onReady.run();
                }
            });
        } catch (Throwable error) {
            LogUtil.w("speech prepare failed", error);
        }
    }

    public boolean isReady() {
        return engine.isReady();
    }

    @NonNull
    public String statusText(@NonNull Context context) {
        return engine.statusText(context);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /**
     * 引擎不可用时返回空列表。上层对空列表是安全的：音色弹层在
     * {@code size() <= 1} 时会显示说明行，语音设置页也只会显示「0 种可用」。
     * <p>
     * 云端音色也在表里（由 {@link EngineRouter} 拼进来），可能随目录晚到——界面想知道
     * 「表是不是已经全了」，看 {@link #isVoiceListSettled()}。
     */
    @NonNull
    public List<Voice> listVoices() {
        List<Voice> result = new ArrayList<>(engine.listVoices());
        // 打勾跟着「实际会发声的那个」走，而不是用户上次选的那个：没开通会员时选了云端音色
        // 也不会用它发声，勾留在云端那一行就是句假话——何况那一行是锁着的，勾根本画不出来，
        // 结果是整张表一个勾都没有，用户看到的是「哪个都没选中」。
        String currentId = currentVoiceId();
        boolean matched = false;
        for (Voice voice : result) {
            boolean selected = currentId != null && currentId.equals(voice.getId());
            voice.setSelected(selected);
            if (selected) {
                matched = true;
            }
        }
        if (!matched && !result.isEmpty()) {
            // 存着的音色已经不在了（例如换过语音包）：先显示第一个
            result.get(0).setSelected(true);
        }
        return result;
    }

    /**
     * @return 传进来的 id 是不是原样生效了；存着的老音色 id 会落到默认发音人上，那时返回 false
     */
    public boolean setVoice(@NonNull String voiceId) {
        selectedVoiceId = voiceId;
        return engine.setVoice(voiceId);
    }

    /**
     * 实际会发声的音色 id。打勾、发音人的名字，都以它为准。
     * <p>
     * 每次都问引擎，不把自己记着的 {@link #selectedVoiceId} 报出去。那个是用户<i>选</i>的，
     * 引擎未必照做，而且它往往是在引擎就绪之前就记下的（见 {@code PoetryApp}，配置一读出来就
     * apply），之后没人再对一遍——「界面写着智萌、响的是内置音色」就是这么来的。
     */
    @Nullable
    public String currentVoiceId() {
        return engine.currentVoiceId();
    }

    public void apply(@NonNull TtsConfig config) {
        if (config.getVoiceId() != null && !config.getVoiceId().isEmpty()) {
            selectedVoiceId = config.getVoiceId();
        }
        current.setVoiceId(selectedVoiceId);
        current.setRate(config.getRate());
        current.setPitch(config.getPitch());
        current.setVolume(config.getVolume());
        engine.apply(current);
    }

    /**
     * 只改语速。这里用的是 {@link #current} 而不是新建一份配置——
     * 新建 TtsConfig 只塞 rate，会把音调和音量一起重置成默认值。
     */
    public void setRate(float rate) {
        current.setRate(rate);
        engine.apply(current);
    }

    public void speak(@NonNull String text) {
        engine.speak(text);
    }

    /**
     * 读一个作品（详情页正文朗读）。
     * <p>
     * 和 {@link #speak(String)} 分开，是因为云端音色是按作品合成整首的（见
     * {@link SpeechEngine#speakWork}）：带上「这是哪一首」，它才合成得了。没有作品引用的
     * 朗读（试听、逐句朗读）继续走 {@code speak}，那边恒用本机音色。
     *
     * @param workRef 作品引用，见 {@code Poem#getLookupRef()}
     */
    public void speakWork(@NonNull String workRef, @NonNull String text) {
        engine.speakWork(workRef, text);
    }

    /**
     * 音色表变了（云端目录拉到了，或确认拉不到）。
     * <p>
     * 云端那份要走网络，界面画完了它才到是常态，所以需要一次通知而不是轮询；
     * 回调在主线程。设置页注册它来重画音色列表，别的页面不关心可以不管。
     */
    public void setVoiceListListener(@Nullable Runnable listener) {
        engine.setVoiceListListener(listener);
    }

    /** 音色表是不是已经落定（拉回来了，或确认拉不到）。见 {@link SpeechEngine#isVoiceListSettled()} */
    public boolean isVoiceListSettled() {
        return engine.isVoiceListSettled();
    }

    /**
     * 重新取一次音色表。
     * <p>
     * 登录态一变，云端音色的锁定态就跟着变（那是服务端判的），所以要重取一次。
     * 本机音色不受影响；接口侧自带节流，重复调用是安全的。
     */
    public void refreshVoices() {
        engine.refreshVoices();
    }

    /**
     * 估算一次朗读的时长（毫秒），给详情页进度条用。
     * <p>
     * 必须是「和实际播放口径一致」的估算：字数只决定发音部分，还要算上语速，
     * 以及离线引擎在联与联之间插的那段 {@link SherpaTts#BLOCK_GAP_MS} 静音。
     * 少算任何一项，进度条都会在声音结束前先跑满。
     * <p>
     * 云端（{@link #usingCloud()}）要分开算，两处口径本来就不同：句间的停顿是合成
     * 音频里自带的，不该再加离线的块间隔；反过来，开场要多等一次网络往返 + 服务端
     * 合成，短句上这段占比很大。按离线那套估短句会提前跑满进度条。
     *
     * @param text 正文（可以带换行，与交给 {@link #speak} 的一致）
     * @param rate 语速倍率，见 {@code TtsConfig}
     */
    public long estimateDurationMs(@NonNull String text, float rate) {
        List<String> blocks = TextChunker.split(PoetryReadings.forSpeech(text));
        int chars = 0;
        for (String block : blocks) {
            chars += block.length();
        }
        float safeRate = rate < TtsConfig.RATE_MIN ? TtsConfig.RATE_DEFAULT : rate;
        // 240 ms/字 是 rate=1.0 时的实测经验值（取自「音色筛选」导出的时长）
        long speaking = Math.round(chars * MS_PER_CHAR / safeRate);
        if (engine.usingCloud()) {
            return Math.max(2000L, speaking + CLOUD_LEAD_MS);
        }
        long gaps = Math.max(0, blocks.size() - 1) * SherpaTts.BLOCK_GAP_MS;
        return Math.max(2000L, speaking + gaps);
    }

    /** rate=1.0 时每个字符约占的毫秒数。 */
    private static final float MS_PER_CHAR = 240f;

    /** 一次云端合成的固定开销：鉴权 + 一次网络往返 + 服务端出第一段音频的耗时。 */
    private static final long CLOUD_LEAD_MS = 800L;

    /** 下一句会不会走云端——进度条按哪种口径估时长，看这个。 */
    public boolean usingCloud() {
        return engine.usingCloud();
    }

    public void stop() {
        engine.stop();
    }

    public boolean isSpeaking() {
        return engine.isSpeaking();
    }

    public void shutdown() {
        engine.shutdown();
        listener = null;
    }

    // ------------------------------------------------------------------ 转发

    private void dispatchStart() {
        if (listener != null) {
            listener.onStart();
        }
    }

    private void dispatchDone() {
        if (listener != null) {
            listener.onDone();
        }
    }

    private void dispatchError(String message) {
        if (listener != null) {
            listener.onError(message);
        }
    }

    /** 提醒：这次朗读还在继续，只是换了种方式（例如云端音色换成了本机音色）。 */
    private void dispatchNotice(String message) {
        if (listener != null) {
            listener.onNotice(message);
        }
    }

    @NonNull
    public static String unavailable(@NonNull Context context) {
        return context.getString(R.string.tts_unavailable);
    }
}

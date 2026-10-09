package com.example.poetry.media;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.R;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Voice;

import java.util.ArrayList;
import java.util.List;

/**
 * 朗读引擎的路由器：把两个子引擎归一成一个，交给上层。
 *
 * <p>子引擎有两个：
 * <ul>
 *   <li>{@link SherpaTts} —— sherpa-onnx + 随 APK 分发的中文 VITS 语音包，整条链路跑在本机，
 *       不联网、也不依赖任何第三方 App。它是<b>底座</b>：装上就能读，一切兜底都落到它身上。</li>
 *   <li>{@link CloudTts} —— 服务端代理的腾讯云音色，要联网、要登录、要会员。它是
 *       <b>可选能力</b>：顶不上时不能变成「读不了」。</li>
 * </ul>
 *
 * <h3>按什么分发</h3>
 *
 * <p>音色 id 分两族（见 {@link Voice#CLOUD_PREFIX}），归属一眼可辨：{@code cloud-*} 走云端，
 * 其余走本机。但「读一段文本」（{@link #speak}）和「读一个作品」（{@link #speakWork}）
 * 的走法不同——云端是<b>按作品合成整首</b>的，没有作品引用它无处下手。所以：
 * 试听、逐句朗读这类没有作品引用的调用留在本机那一路（哪怕选中的是云端音色，
 * 也说一句「试听用本机音色」），只有 {@link #speakWork} 才可能交给云端。
 *
 * <h3>顶不上时的兜底</h3>
 *
 * <p>云端能用与否是<b>运行时</b>的事（会员到期、密钥没配、网络断了），选音色的时候
 * 未必看得出来。所以真正的判定放在每一次朗读前（{@link CloudTts#unavailability}），
 * 顶不上就换本机音色把这一遍读完，只发一句 {@code onNotice}，<b>不当成错误</b>——
 * 报成错误的代价是把正在读的一段掐掉（见 {@link SpeechEngine.Listener#onNotice}）。
 * 合成/播放中途起不来的，由 {@link CloudTts.Fallback} 交回这里，走的是同一条路。
 *
 * <h3>为什么这里没有腾讯云直连</h3>
 *
 * <p>早先考虑过客户端直连腾讯云。直连必须把主账号的 {@code SecretId} / {@code SecretKey}
 * 编译进 APK，而 {@code BuildConfig} 的字符串常量就是明文躺在 {@code classes.dex} 里的——
 * 实测反编译能直接搜出密钥原文，等于把账号交出去。所以走服务端代理，凭据只留在服务端
 * （{@code POST /v1/tts/synthesize}）。
 */
public final class EngineRouter implements SpeechEngine {

    private static final EngineRouter INSTANCE = new EngineRouter();

    public static EngineRouter get() {
        return INSTANCE;
    }

    private final SherpaTts offline = SherpaTts.get();
    private final CloudTts cloud = CloudTts.get();

    @Nullable
    private Listener downstream;

    /** {@link CloudTts#unavailability} 要个 Context 取文案；也留给兜底时说话用。 */
    @Nullable
    private Context appContext;

    /**
     * 用户当前选中的音色 id（最近一次 {@link #setVoice} / {@link #apply} 下来的）。
     * <p>
     * 路由器自己记，而不是去问某个子引擎：谁都可能记着「以前那个」，而云端音色是不是
     * 还用得上，得看用户<b>现在</b>选的是什么。
     */
    @NonNull
    private volatile String selected = "";

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

    /**
     * 云端顶不上时接着读的那只手：说一句「换了种方式」，剩下的用本机音色读完。
     * <p>
     * 本机引擎自己起不来时（语音包缺失）它会如实报错，不用这里再兜一层。
     */
    private final CloudTts.Fallback handBack = (notice, text) -> {
        if (downstream != null) {
            downstream.onNotice(notice);
        }
        offline.speak(text);
    };

    private EngineRouter() {
    }

    @Override
    public void prepare(@NonNull Context context, @Nullable Runnable onReady) {
        appContext = context.getApplicationContext();
        offline.setListener(forward);
        offline.prepare(context, onReady);
        // 云端的回调也收在同一个 forward 上，但 onReady 不往它那儿送，原因有两个：
        // 一是「引擎就绪」的语义是「能不能读了」，不该压在一次网络往返上（阶段一刚
        // 拆掉的就是这种等待）；二是 SherpaTts.prepare 在并发调用时只保留最后一次回调，
        // 两个引擎各报一次反而会让上层收到两次同一件事。
        // 目录到没到另有一条通知：见 SpeechEngine#setVoiceListListener。
        cloud.setListener(forward);
        cloud.setFallback(handBack);
        cloud.prepare(context, null);
    }

    @Override
    public boolean isReady() {
        // 本机语音包缺失时云端仍能顶上，所以是「或」不是「且」。
        // 注意它只回答「有东西能读」，具体这一遍走哪条路由 speakWork 现算（见那里）。
        return offline.isReady() || cloud.isReady();
    }

    @NonNull
    @Override
    public String statusText(@NonNull Context context) {
        if (offline.isReady()) {
            // 本机那一路好了就报它：绝大多数时候用户读的就是它
            return offline.statusText(context);
        }
        if (cloud.isReady()) {
            // 本机语音包缺失，但云端能顶上——别把状态说得像「什么都读不了」
            return context.getString(R.string.tts_cloud_only);
        }
        // 两路都用不上：本机是朗读的底座、也是用户最该去修的那一处，先说它。
        // 云端那边的进度（正要到 / 需会员 / 取不到）在音色列表里看得见。
        return offline.statusText(context);
    }

    @Override
    public void setListener(@Nullable Listener listener) {
        this.downstream = listener;
    }

    @NonNull
    @Override
    public List<Voice> listVoices() {
        // 两张表拼起来：本机的（随包分发，永远排在前头）+ 云端的（服务端下发，可能还没到）。
        // 打勾由 Speaker 按 currentVoiceId() 统一标，这里只负责把表备齐。
        List<Voice> result = new ArrayList<>(offline.listVoices());
        result.addAll(cloud.listVoices());
        return result;
    }

    @Override
    public void setVoiceListListener(@Nullable Runnable listener) {
        cloud.setVoiceListListener(listener);
    }

    @Override
    public boolean isVoiceListSettled() {
        return cloud.isVoiceListSettled();
    }

    @Override
    public void refreshVoices() {
        cloud.refreshVoices();
    }

    @Override
    public boolean setVoice(@NonNull String voiceId) {
        selected = Voice.normalizeId(voiceId);
        // 云端那一侧每次都要收到，好知道该不该「让位」（见 CloudTts#setVoice）：它要是
        // 一直记着上一次选过的云端音色，界面上就会出现「选的是本机音色、勾却在云端那一行」。
        cloud.setVoice(selected);
        if (Voice.isCloudId(selected)) {
            // 云端音色不往本机引擎送：SherpaTts 不认识 cloud-*，会顺势把发音人落到默认上，
            // 抹掉「云端顶不上时用谁」。返回 false 也是实话——这个 id 本机确实认不了。
            return false;
        }
        return offline.setVoice(selected);
    }

    @Nullable
    @Override
    public String currentVoiceId() {
        // 云端音色选着、也真的用得上，那「现在是谁在读」就是它。
        // 用不上时（会员没开、服务端没配、目录里没有）报本机音色——它才是真会出声的那个。
        if (Voice.isCloudId(selected) && cloud.currentVoiceId() != null) {
            return selected;
        }
        return offline.currentVoiceId();
    }

    @Override
    public void apply(@NonNull TtsConfig config) {
        selected = Voice.normalizeId(config.getVoiceId());
        cloud.apply(config);
        offline.apply(forOffline(config));
    }

    /**
     * 交给本机引擎的那一份配置。
     * <p>
     * 云端音色 id 要从它这头抹掉：{@code SherpaTts} 不认识 {@code cloud-*}，拿到就会把发音人
     * 落到默认上（见 {@code SherpaTts.resolve}）。而它记着的那一个是「云端顶不上时用谁」，
     * 不该被一次云端音色的选择连坐抹掉。语速、音量两边都要，所以只动音色这一个字段。
     */
    @NonNull
    private static TtsConfig forOffline(@NonNull TtsConfig config) {
        if (!Voice.isCloudId(config.getVoiceId())) {
            return config;
        }
        TtsConfig copy = config.copy();
        copy.setVoiceId("");
        return copy;
    }

    @Override
    public void speak(@NonNull String text) {
        // 没有作品引用的朗读（试听、逐句朗读）走本机音色：云端那条路合成不了任意一段文本。
        // 选中云端音色时说明一句，否则用户会以为自己刚选的音色没生效。
        Context context = appContext;
        if (context != null && Voice.isCloudId(selected) && cloud.currentVoiceId() != null
                && downstream != null) {
            downstream.onNotice(context.getString(R.string.tts_cloud_preview));
        }
        offline.speak(text);
    }

    @Override
    public void speakWork(@NonNull String workRef, @NonNull String text) {
        Context context = appContext;
        if (context != null && Voice.isCloudId(selected)) {
            // 选中云端音色才问云端：它顶得上（返回 null）就走服务端合成整首，
            // 顶不上就说一句为什么、本机音色接着读。
            String reason = cloud.unavailability(context);
            if (reason == null) {
                cloud.speakWork(workRef, text);
                return;
            }
            if (downstream != null) {
                downstream.onNotice(reason);
            }
        }
        offline.speak(text);
    }

    @Override
    public void stop() {
        offline.stop();
        cloud.stop();
    }

    @Override
    public boolean isSpeaking() {
        return offline.isSpeaking() || cloud.isSpeaking();
    }

    @Override
    public boolean usingCloud() {
        // 「下一遍正文朗读会走云端吗」——判据和 currentVoiceId 一致
        return Voice.isCloudId(selected) && cloud.currentVoiceId() != null;
    }

    @Override
    public void shutdown() {
        offline.shutdown();
        cloud.shutdown();
    }
}

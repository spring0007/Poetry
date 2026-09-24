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
 * 底下是 {@link SherpaTts}：sherpa-onnx + 随 APK 分发的中文 VITS 语音包，
 * 整条合成链路跑在本机，装上就能读，不需要联网、也不依赖任何第三方 App。
 * 语音包缺失或 native 库加载失败时 {@link #isReady()} 为 false，
 * 界面应当给出引导而不是硬读。
 * <p>
 * 这一层另外管一件事：把选中的音色记在自己身上，UI 的选择状态以这里为准。
 */
public final class Speaker {

    private static volatile Speaker instance;

    private final SherpaTts engine = SherpaTts.get();

    private Listener listener;

    /** UI 里选中的音色，选择状态的唯一来源。 */
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
                // 引擎刚就绪时把选中的音色再下发一次：加载完成前调的 setVoice 会被丢掉
                String chosen = selectedVoiceId;
                if (chosen != null && !chosen.isEmpty()) {
                    engine.setVoice(chosen);
                }
                syncVoiceFromEngine();
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
     */
    @NonNull
    public List<Voice> listVoices() {
        List<Voice> result = new ArrayList<>(engine.listVoices());
        String currentId = selectedVoiceId;
        if (currentId == null || currentId.isEmpty()) {
            currentId = engine.currentVoiceId();
        }
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
        boolean exact = engine.setVoice(voiceId);
        syncVoiceFromEngine();
        return exact;
    }

    @Nullable
    public String currentVoiceId() {
        return selectedVoiceId;
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
        syncVoiceFromEngine();
    }

    /**
     * 把引擎归一之后的音色 id 收回来。
     * <p>
     * 归一发生在引擎内部（见 {@code SherpaTts.resolve}）：存着的老 id、或者这次没通过核对的
     * 发音人，都会被落到默认音色上。如果这边不同步，「朗读」用的是默认音色、界面却给另一个
     * 音色打勾——用户会觉得选中的音色不生效。
     */
    private void syncVoiceFromEngine() {
        String resolved = engine.currentVoiceId();
        if (resolved != null && !resolved.isEmpty()) {
            selectedVoiceId = resolved;
        }
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

    @NonNull
    public static String unavailable(@NonNull Context context) {
        return context.getString(R.string.tts_unavailable);
    }
}

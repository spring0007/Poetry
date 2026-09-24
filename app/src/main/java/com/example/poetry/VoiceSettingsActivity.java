package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.poetry.ui.Skin;
import com.example.poetry.adapter.VoiceAdapter;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Voice;
import com.example.poetry.databinding.ActivityVoiceSettingsBinding;
import com.example.poetry.media.Speaker;
import com.example.poetry.util.Sliders;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 语音设置页：发音人、语速、音调、音量，外加引擎本身的状态。
 * <p>
 * 合成不依赖任何外部程序——语音模型随 APK 分发，装在应用里（见 {@code media.SherpaTts}）。
 * 所以这一页没有「去装个引擎」「去导入语音包」这类出口，只需要把「引擎起来了没有」
 * 讲清楚：模型加载是异步的，用户可能在本页刚打开时就看到状态。
 * <p>
 * 所有改动立即写入 {@link UserStore}（单份 JSON 快照），下次启动自动恢复；
 * 同时立即下发给引擎，便于当场试听。
 */
public class VoiceSettingsActivity extends AppCompatActivity {

    private ActivityVoiceSettingsBinding binding;
    private UserStore store;
    private TtsConfig config;
    private VoiceAdapter adapter;
    private final List<Voice> voices = new ArrayList<>();
    private boolean testing;

    public static void open(@NonNull Context context) {
        context.startActivity(new Intent(context, VoiceSettingsActivity.class));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityVoiceSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        store = UserStore.get(this);
        config = store.getTtsConfig();

        binding.backButton.setOnClickListener(v -> finish());
        setupVoices();
        setupSliders();
        setupActions();

        Speaker.get().setListener(new Speaker.Listener() {
            @Override
            public void onStart() {
                // 合成中：保持「停止」按钮可用
            }

            @Override
            public void onDone() {
                setTesting(false);
            }

            @Override
            public void onError(String message) {
                setTesting(false);
                Toast.makeText(VoiceSettingsActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });

        Speaker.get().prepare(this, this::onEngineReady);
        refreshEngineState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 引擎可能是离开这段时间里加载完的（首启时加载要好几秒），回来再对一次状态
        Speaker.get().prepare(this, this::onEngineReady);
        refreshEngineState();
    }

    // ------------------------------------------------------------------ 音色

    private void setupVoices() {
        adapter = new VoiceAdapter(new VoiceAdapter.Listener() {
            @Override
            public void onVoiceClick(@NonNull Voice voice) {
                selectVoice(voice);
            }

            @Override
            public void onTryClick(@NonNull Voice voice) {
                selectVoice(voice);
                startPreview();
            }
        });
        binding.voiceList.setLayoutManager(new LinearLayoutManager(this));
        binding.voiceList.setAdapter(adapter);
        reloadVoices();
    }

    private void reloadVoices() {
        voices.clear();
        voices.addAll(Speaker.get().listVoices());
        // 引擎是音色的唯一权威。存着的老 id（例如只有小雅那个年代的 sherpa-xiao-ya）
        // 或者换过语音包之后不存在的音色，都会被引擎归一成默认发音人——这里跟着它走，
        // 保证界面上打勾的那个就是实际会发声的那个
        String current = Speaker.get().currentVoiceId();
        if (current == null || current.isEmpty()) {
            current = config.getVoiceId();
        }
        boolean matched = false;
        for (Voice voice : voices) {
            boolean selected = voice.getId().equals(current);
            voice.setSelected(selected);
            if (selected) {
                matched = true;
            }
        }
        if (!matched && !voices.isEmpty()) {
            // 音色表整个对不上（例如换了语音包）：落到第一个，用户不用自己去理解这个落差
            voices.get(0).setSelected(true);
            current = voices.get(0).getId();
        }
        if (current != null && !current.isEmpty() && !current.equals(config.getVoiceId())) {
            config.setVoiceId(current);
            persist();
        }
        adapter.submit(voices);
        binding.voiceMeta.setText(getString(R.string.tts_voice_meta, voices.size()));
    }

    private void selectVoice(@NonNull Voice voice) {
        for (Voice item : voices) {
            item.setSelected(false);
        }
        voice.setSelected(true);
        config.setVoiceId(voice.getId());
        persist();
        adapter.submit(voices);
        Speaker.get().setVoice(voice.getId());
    }

    // ------------------------------------------------------------------ 引擎状态

    private void onEngineReady() {
        refreshEngineState();
        reloadVoices();
    }

    private void refreshEngineState() {
        String status = Speaker.get().statusText(this);
        binding.engineStatus.setText(getString(R.string.tts_engine_status, status));
    }

    // ------------------------------------------------------------------ 参数滑杆

    private void setupSliders() {
        applyConfigToSliders();

        binding.rateSlider.addOnChangeListener((slider, value, fromUser) -> {
            config.setRate(Sliders.snapAndPushBack(slider, value));
            updateValueLabels();
            persistAndApply();
        });
        binding.pitchSlider.addOnChangeListener((slider, value, fromUser) -> {
            config.setPitch(Sliders.snapAndPushBack(slider, value));
            updateValueLabels();
            persistAndApply();
        });
        binding.volumeSlider.addOnChangeListener((slider, value, fromUser) -> {
            config.setVolume(Sliders.snapAndPushBack(slider, value));
            updateValueLabels();
            persistAndApply();
        });
    }

    /**
     * 把 config 里的三个值搬进滑杆。
     * <p>
     * 值先按滑杆自己的步进栅格收敛再 {@code setValue()}——存下来的值可能不在栅格上
     * （例如拖拽留下的 0.92727274，见 {@link Sliders}），那会让 setValue 在首次布局时抛
     * {@code IllegalStateException}，而那个崩点跟这行代码隔着好几层布局回调。
     * 同时写回 config：界面上显示不出来的值不该继续留在配置里，否则标签、滑杆、
     * 引擎三边对不上（{@code persist()} 只在真的修过东西时才写盘）。
     */
    private void applyConfigToSliders() {
        float rate = Sliders.snap(binding.rateSlider, config.getRate());
        float pitch = Sliders.snap(binding.pitchSlider, config.getPitch());
        float volume = Sliders.snap(binding.volumeSlider, config.getVolume());
        boolean healed = rate != config.getRate()
                || pitch != config.getPitch()
                || volume != config.getVolume();

        config.setRate(rate);
        config.setPitch(pitch);
        config.setVolume(volume);
        binding.rateSlider.setValue(config.getRate());
        binding.pitchSlider.setValue(config.getPitch());
        binding.volumeSlider.setValue(config.getVolume());
        updateValueLabels();

        if (healed) {
            persist();
        }
    }

    private void updateValueLabels() {
        binding.rateValue.setText(String.format(Locale.CHINA,
                getString(R.string.tts_ratio_value), config.getRate()));
        binding.pitchValue.setText(String.format(Locale.CHINA,
                getString(R.string.tts_ratio_value), config.getPitch()));
        binding.volumeValue.setText(String.format(Locale.CHINA,
                getString(R.string.tts_percent_value), Math.round(config.getVolume() * 100)));
    }

    // ------------------------------------------------------------------ 操作

    private void setupActions() {
        binding.testButton.setOnClickListener(v -> {
            if (testing) {
                Speaker.get().stop();
                setTesting(false);
            } else {
                startPreview();
            }
        });

        binding.resetButton.setOnClickListener(v -> {
            config = TtsConfig.defaults();
            persist();
            Speaker.get().apply(config);
            applyConfigToUi();
            Toast.makeText(this, R.string.tts_reset_done, Toast.LENGTH_SHORT).show();
        });
    }

    private void applyConfigToUi() {
        applyConfigToSliders();
        refreshEngineState();
        reloadVoices();
    }

    private void startPreview() {
        Speaker.get().apply(config);
        setTesting(true);
        Speaker.get().speak(getString(R.string.tts_preview_text));
    }

    private void setTesting(boolean value) {
        testing = value;
        binding.testButton.setText(value ? R.string.tts_stop : R.string.tts_test);
    }

    private void persistAndApply() {
        persist();
        Speaker.get().apply(config);
    }

    private void persist() {
        store.setTtsConfig(config);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Speaker.get().stop();
        Speaker.get().setListener(null);
        binding = null;
    }
}

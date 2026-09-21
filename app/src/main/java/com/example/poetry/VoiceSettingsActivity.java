package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.poetry.adapter.VoiceAdapter;
import com.example.poetry.data.AppExecutors;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Voice;
import com.example.poetry.databinding.ActivityVoiceSettingsBinding;
import com.example.poetry.media.Speaker;
import com.example.poetry.tts.EspeakData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 语音设置页：朗读引擎、发音人、语速、音调、音量。
 * <p>
 * 所有改动立即写入 {@link UserStore}（单份 JSON 快照），下次启动自动恢复；
 * 同时立即下发给当前引擎，便于当场试听。
 */
public class VoiceSettingsActivity extends AppCompatActivity {

    private ActivityVoiceSettingsBinding binding;
    private UserStore store;
    private TtsConfig config;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VoiceAdapter adapter;
    private final List<Voice> voices = new ArrayList<>();
    private boolean testing;

    public static void open(@NonNull Context context) {
        context.startActivity(new Intent(context, VoiceSettingsActivity.class));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
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
                // ignore
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

        // espeak 是异步准备的，就绪后刷新状态与音色列表
        Speaker.get().prepare(this, this::refreshEngineState);
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
        String saved = config.getVoiceId();
        boolean matched = false;
        for (Voice voice : voices) {
            boolean selected = voice.getId().equals(saved);
            voice.setSelected(selected);
            if (selected) {
                matched = true;
            }
        }
        if (!matched && !voices.isEmpty()) {
            voices.get(0).setSelected(true);
            config.setVoiceId(voices.get(0).getId());
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

    private void refreshEngineState() {
        String status = Speaker.get().statusText(this);
        binding.engineStatus.setText(getString(R.string.tts_engine_status, status));
    }

    // ------------------------------------------------------------------ 参数滑杆

    private void setupSliders() {
        binding.rateSlider.setValue(config.getRate());
        binding.pitchSlider.setValue(config.getPitch());
        binding.volumeSlider.setValue(config.getVolume());
        binding.pitchRangeSlider.setValue(config.getPitchRange());
        binding.wordGapSlider.setValue((float) config.getWordGap());
        updateValueLabels();

        binding.rateSlider.addOnChangeListener((slider, value, fromUser) -> {
            config.setRate(value);
            updateValueLabels();
            persistAndApply();
        });
        binding.pitchSlider.addOnChangeListener((slider, value, fromUser) -> {
            config.setPitch(value);
            updateValueLabels();
            persistAndApply();
        });
        binding.volumeSlider.addOnChangeListener((slider, value, fromUser) -> {
            config.setVolume(value);
            updateValueLabels();
            persistAndApply();
        });
        binding.pitchRangeSlider.addOnChangeListener((slider, value, fromUser) -> {
            config.setPitchRange(value);
            updateValueLabels();
            persistAndApply();
        });
        binding.wordGapSlider.addOnChangeListener((slider, value, fromUser) -> {
            config.setWordGap(Math.round(value));
            updateValueLabels();
            persistAndApply();
        });
    }

    private void updateValueLabels() {
        binding.rateValue.setText(String.format(Locale.CHINA,
                getString(R.string.tts_ratio_value), config.getRate()));
        binding.pitchValue.setText(String.format(Locale.CHINA,
                getString(R.string.tts_ratio_value), config.getPitch()));
        binding.volumeValue.setText(String.format(Locale.CHINA,
                getString(R.string.tts_percent_value), Math.round(config.getVolume() * 100)));
        binding.pitchRangeValue.setText(String.format(Locale.CHINA,
                getString(R.string.tts_ratio_value), config.getPitchRange()));
        binding.wordGapValue.setText(String.format(Locale.CHINA,
                getString(R.string.tts_pace_value), config.getWordGap()));
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

        binding.reloadButton.setOnClickListener(v -> reloadData());
    }

    private void applyConfigToUi() {
        binding.rateSlider.setValue(config.getRate());
        binding.pitchSlider.setValue(config.getPitch());
        binding.volumeSlider.setValue(config.getVolume());
        binding.pitchRangeSlider.setValue(config.getPitchRange());
        binding.wordGapSlider.setValue((float) config.getWordGap());
        updateValueLabels();
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

    /** 清空并重新释放 espeak-ng-data（排查数据损坏时用） */
    private void reloadData() {
        Toast.makeText(this, R.string.tts_status_preparing, Toast.LENGTH_SHORT).show();
        AppExecutors.get().io(() -> {
            EspeakData.clearInternal(this);
            boolean ok = EspeakData.ensureInstalled(this) != null;
            AppExecutors.get().main(() -> {
                Toast.makeText(this, ok ? R.string.tts_reload_done : R.string.tts_reload_failed,
                        Toast.LENGTH_SHORT).show();
                Speaker.get().prepare(this, this::refreshEngineState);
            });
        });
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
        handler.removeCallbacksAndMessages(null);
        Speaker.get().stop();
        Speaker.get().setListener(null);
        binding = null;
    }
}

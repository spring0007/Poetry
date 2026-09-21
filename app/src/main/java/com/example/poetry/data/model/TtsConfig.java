package com.example.poetry.data.model;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 语音合成配置（发音人 / 语速 / 音调 / 音量 / 引擎）。
 * <p>
 * 单位约定：对外统一使用易理解的倍率；各引擎内部再换算成自己的量程
 * （eSpeak 用语速 wpm、音调 0-100、音量 0-200；系统 TTS 用 0.5-2.0 倍率）。
 * <p>
 * 所有 setter 都做区间收敛，反序列化失败时返回 {@link #defaults()}，
 * 保证配置损坏也不会影响朗读以外的功能。
 */
public class TtsConfig {

    /** espeak-ng 离线引擎（项目内置 libttsespeak.so + espeak-ng-data） */
    public static final String ENGINE_ESPEAK = "espeak";

    public static final float RATE_MIN = 0.6f;
    public static final float RATE_MAX = 1.8f;
    // 中文诗词偏慢更清晰、也更少「机器连读」的生硬感
    public static final float RATE_DEFAULT = 0.9f;

    public static final float PITCH_MIN = 0.5f;
    public static final float PITCH_MAX = 1.5f;
    public static final float PITCH_DEFAULT = 1.0f;

    public static final float VOLUME_MIN = 0f;
    public static final float VOLUME_MAX = 1f;
    public static final float VOLUME_DEFAULT = 0.9f;

    /**
     * 语调幅度倍率（espeak PARAM_PITCH_RANGE，基准 50，量程 0-100）。
     * 偏低 → 平淡机械；偏高 → 夸张唱腔。中文四声需要足够幅度才清楚，默认 1.0。
     */
    public static final float PITCHRANGE_MIN = 0.5f;
    public static final float PITCHRANGE_MAX = 1.5f;
    public static final float PITCHRANGE_DEFAULT = 1.0f;

    /**
     * 词间停顿档位（espeak PARAM_WORD_GAP，0 = 无额外停顿）。
     * 中文按字断词，档位过高会一字一顿显得生硬，默认 0；诗词吟诵时可适当调高。
     */
    public static final int WORDGAP_MIN = 0;
    public static final int WORDGAP_MAX = 10;
    public static final int WORDGAP_DEFAULT = 0;

    private String engine = ENGINE_ESPEAK;
    private String voiceId = "";
    private float rate = RATE_DEFAULT;
    private float pitch = PITCH_DEFAULT;
    private float volume = VOLUME_DEFAULT;
    private float pitchRange = PITCHRANGE_DEFAULT;
    private int wordGap = WORDGAP_DEFAULT;

    @NonNull
    public static TtsConfig defaults() {
        return new TtsConfig();
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        // 项目仅使用内置 espeak-ng 离线引擎，不再提供系统 TTS 选项
        this.engine = ENGINE_ESPEAK;
    }

    public boolean isEspeak() {
        return true;
    }

    public String getVoiceId() {
        return voiceId;
    }

    public void setVoiceId(String voiceId) {
        this.voiceId = voiceId == null ? "" : voiceId;
    }

    public float getRate() {
        return rate;
    }

    public void setRate(float rate) {
        this.rate = clamp(rate, RATE_MIN, RATE_MAX, RATE_DEFAULT);
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = clamp(pitch, PITCH_MIN, PITCH_MAX, PITCH_DEFAULT);
    }

    public float getVolume() {
        return volume;
    }

    public void setVolume(float volume) {
        this.volume = clamp(volume, VOLUME_MIN, VOLUME_MAX, VOLUME_DEFAULT);
    }

    public float getPitchRange() {
        return pitchRange;
    }

    public void setPitchRange(float pitchRange) {
        this.pitchRange = clamp(pitchRange, PITCHRANGE_MIN, PITCHRANGE_MAX, PITCHRANGE_DEFAULT);
    }

    public int getWordGap() {
        return wordGap;
    }

    public void setWordGap(int wordGap) {
        if (wordGap < WORDGAP_MIN) {
            this.wordGap = WORDGAP_MIN;
        } else {
            this.wordGap = Math.min(wordGap, WORDGAP_MAX);
        }
    }

    @NonNull
    public TtsConfig copy() {
        TtsConfig copy = new TtsConfig();
        copy.engine = engine;
        copy.voiceId = voiceId;
        copy.rate = rate;
        copy.pitch = pitch;
        copy.volume = volume;
        copy.pitchRange = pitchRange;
        copy.wordGap = wordGap;
        return copy;
    }

    private static float clamp(float value, float min, float max, float fallback) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return fallback;
        }
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    // ------------------------------------------------------------------ 序列化

    private static final String KEY_ENGINE = "engine";
    private static final String KEY_VOICE = "voice";
    private static final String KEY_RATE = "rate";
    private static final String KEY_PITCH = "pitch";
    private static final String KEY_VOLUME = "volume";
    private static final String KEY_PITCHRANGE = "pitchRange";
    private static final String KEY_WORDGAP = "wordGap";

    @NonNull
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put(KEY_ENGINE, engine);
            json.put(KEY_VOICE, voiceId);
            json.put(KEY_RATE, (double) rate);
            json.put(KEY_PITCH, (double) pitch);
            json.put(KEY_VOLUME, (double) volume);
            json.put(KEY_PITCHRANGE, (double) pitchRange);
            json.put(KEY_WORDGAP, wordGap);
        } catch (JSONException ignored) {
            // JSONObject.put 基本类型不会抛异常
        }
        return json;
    }

    /** 解析配置；任何异常都回落到默认值 */
    @NonNull
    public static TtsConfig fromJson(@NonNull String raw) {
        if (raw.isEmpty()) {
            return defaults();
        }
        try {
            JSONObject json = new JSONObject(raw);
            TtsConfig config = new TtsConfig();
            config.setEngine(json.optString(KEY_ENGINE, ENGINE_ESPEAK));
            config.setVoiceId(json.optString(KEY_VOICE, ""));
            config.setRate((float) json.optDouble(KEY_RATE, RATE_DEFAULT));
            config.setPitch((float) json.optDouble(KEY_PITCH, PITCH_DEFAULT));
            config.setVolume((float) json.optDouble(KEY_VOLUME, VOLUME_DEFAULT));
            config.setPitchRange((float) json.optDouble(KEY_PITCHRANGE, PITCHRANGE_DEFAULT));
            config.setWordGap(json.optInt(KEY_WORDGAP, WORDGAP_DEFAULT));
            return config;
        } catch (JSONException e) {
            return defaults();
        }
    }
}

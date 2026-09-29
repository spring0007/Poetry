package com.example.poetry.data.model;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 语音合成配置（发音人 / 语速 / 音调 / 音量）。
 * <p>
 * 单位约定：对外统一使用易理解的倍率（语速 / 音调为倍率，音量 0-1），
 * 由引擎内部换算成自己的量程。{@code SherpaTts} 里语速是合成参数（speed），
 * 音量走 AudioTrack.setVolume，音调没有对应旋钮——VITS 只有时长这一个可调项。
 * <p>
 * 所有 setter 都做区间收敛，反序列化失败时返回 {@link #defaults()}，
 * 保证配置损坏也不会影响朗读以外的功能。
 */
public class TtsConfig {

    public static final float RATE_MIN = 0.6f;
    public static final float RATE_MAX = 1.8f;
    // 中文诗词偏慢更清晰、也更少「机器连读」的生硬感
    public static final float RATE_DEFAULT = 1.0f;

    public static final float PITCH_MIN = 0.5f;
    public static final float PITCH_MAX = 1.5f;
    public static final float PITCH_DEFAULT = 1.0f;

    public static final float VOLUME_MIN = 0f;
    public static final float VOLUME_MAX = 1f;
    public static final float VOLUME_DEFAULT = 0.9f;

    private String voiceId = "";
    private float rate = RATE_DEFAULT;
    private float pitch = PITCH_DEFAULT;
    private float volume = VOLUME_DEFAULT;

    @NonNull
    public static TtsConfig defaults() {
        return new TtsConfig();
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

    @NonNull
    public TtsConfig copy() {
        TtsConfig copy = new TtsConfig();
        copy.voiceId = voiceId;
        copy.rate = rate;
        copy.pitch = pitch;
        copy.volume = volume;
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

    private static final String KEY_VOICE = "voice";
    private static final String KEY_RATE = "rate";
    private static final String KEY_PITCH = "pitch";
    private static final String KEY_VOLUME = "volume";

    @NonNull
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put(KEY_VOICE, voiceId);
            json.put(KEY_RATE, (double) rate);
            json.put(KEY_PITCH, (double) pitch);
            json.put(KEY_VOLUME, (double) volume);
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
            config.setVoiceId(json.optString(KEY_VOICE, ""));
            config.setRate((float) json.optDouble(KEY_RATE, RATE_DEFAULT));
            config.setPitch((float) json.optDouble(KEY_PITCH, PITCH_DEFAULT));
            config.setVolume((float) json.optDouble(KEY_VOLUME, VOLUME_DEFAULT));
            return config;
        } catch (JSONException e) {
            return defaults();
        }
    }
}

package com.example.poetry.media;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 朗读引擎（基于系统 TextToSpeech，无需额外依赖）。
 * <p>
 * 音色来源：
 * <ul>
 *   <li>{@link #listVoices()} 枚举系统已安装的中文音色；</li>
 *   <li>云端音色留待后台接口下发（{@code ApiConfig.API_VOICES}）。</li>
 * </ul>
 */
public final class Speaker implements TextToSpeech.OnInitListener {

    private static final String TAG = "Speaker";
    private static final String UTTERANCE_ID = "poetry_utterance";

    private static volatile Speaker instance;

    private TextToSpeech tts;
    private boolean ready;
    private android.speech.tts.Voice currentVoice;
    private Listener listener;

    public interface Listener {
        void onStart();

        void onDone();

        void onError(String message);
    }

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

    public void init(@NonNull Context context) {
        if (tts != null) {
            return;
        }
        try {
            tts = new TextToSpeech(context.getApplicationContext(), this);
        } catch (Exception e) {
            Log.e(TAG, "init TextToSpeech failed", e);
        }
    }

    @Override
    public void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS && tts != null;
        if (!ready) {
            return;
        }
        int result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // 中文不可用时不置为失败，仍可尝试朗读
            Log.w(TAG, "Chinese TTS data missing");
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                if (listener != null) {
                    listener.onStart();
                }
            }

            @Override
            public void onDone(String utteranceId) {
                if (listener != null) {
                    listener.onDone();
                }
            }

            @Override
            @Deprecated
            public void onError(String utteranceId) {
                if (listener != null) {
                    listener.onError("朗读失败");
                }
            }

            @Override
            public void onError(String utteranceId, int errorCode) {
                if (listener != null) {
                    listener.onError("朗读失败：" + errorCode);
                }
            }
        });
    }

    public boolean isReady() {
        return ready && tts != null;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------ 音色

    /**
     * 系统可用的中文音色；设备不支持时返回一条默认音色占位。
     */
    @NonNull
    public List<com.example.poetry.data.model.Voice> listVoices() {
        List<com.example.poetry.data.model.Voice> list = new ArrayList<>();
        if (!isReady()) {
            list.add(defaultVoice());
            return list;
        }
        try {
            java.util.Set<android.speech.tts.Voice> systemVoices = tts.getVoices();
            if (systemVoices == null || systemVoices.isEmpty()) {
                list.add(defaultVoice());
                return list;
            }
            for (android.speech.tts.Voice voice : systemVoices) {
                Locale locale = voice.getLocale();
                if (locale == null || !"zh".equalsIgnoreCase(locale.getLanguage())) {
                    continue;
                }
                com.example.poetry.data.model.Voice item = new com.example.poetry.data.model.Voice();
                item.setId(voice.getName());
                item.setName(prettyName(voice));
                item.setDesc(locale.getDisplayName(Locale.SIMPLIFIED_CHINESE));
                item.setLocale(locale.toLanguageTag());
                item.setTag1("系统内置");
                item.setTag2("质量 " + qualityLabel(voice));
                list.add(item);
            }
        } catch (Exception e) {
            Log.w(TAG, "listVoices failed", e);
        }
        if (list.isEmpty()) {
            list.add(defaultVoice());
        }
        java.util.Collections.sort(list, (a, b) -> a.getName().compareTo(b.getName()));
        return list;
    }

    @NonNull
    private com.example.poetry.data.model.Voice defaultVoice() {
        com.example.poetry.data.model.Voice voice = new com.example.poetry.data.model.Voice();
        voice.setId("default");
        voice.setName("默认中文");
        voice.setDesc("系统默认语音合成");
        voice.setTag1("系统内置");
        voice.setTag2("默认");
        return voice;
    }

    @NonNull
    private static String prettyName(@NonNull android.speech.tts.Voice voice) {
        String name = voice.getName();
        if (name == null || name.isEmpty()) {
            return "未命名音色";
        }
        // 系统音色名通常形如 zh-CN-language，取最后一段更易读
        int slash = name.lastIndexOf('/');
        String tail = slash >= 0 ? name.substring(slash + 1) : name;
        return tail.replace('-', ' ');
    }

    @NonNull
    private static String qualityLabel(@NonNull android.speech.tts.Voice voice) {
        int quality = voice.getQuality();
        switch (quality) {
            case android.speech.tts.Voice.QUALITY_VERY_HIGH:
                return "极高";
            case android.speech.tts.Voice.QUALITY_HIGH:
                return "高";
            case android.speech.tts.Voice.QUALITY_NORMAL:
                return "标准";
            case android.speech.tts.Voice.QUALITY_LOW:
                return "低";
            default:
                return "标准";
        }
    }

    // ------------------------------------------------------------ 朗读控制

    public boolean setVoice(@NonNull String voiceId) {
        if (!isReady()) {
            return false;
        }
        try {
            java.util.Set<android.speech.tts.Voice> systemVoices = tts.getVoices();
            if (systemVoices != null) {
                for (android.speech.tts.Voice voice : systemVoices) {
                    if (voice.getName() != null && voice.getName().equals(voiceId)) {
                        tts.setVoice(voice);
                        currentVoice = voice;
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "setVoice failed", e);
        }
        return false;
    }

    @Nullable
    public String currentVoiceId() {
        return currentVoice == null ? null : currentVoice.getName();
    }

    public void setRate(float rate) {
        if (isReady()) {
            tts.setSpeechRate(rate);
        }
    }

    public void setPitch(float pitch) {
        if (isReady()) {
            tts.setPitch(pitch);
        }
    }

    public void speak(@NonNull String text) {
        if (!isReady()) {
            if (listener != null) {
                listener.onError("语音合成不可用");
            }
            return;
        }
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID);
    }

    public void stop() {
        if (isReady()) {
            tts.stop();
        }
    }

    public boolean isSpeaking() {
        return isReady() && tts.isSpeaking();
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        ready = false;
        listener = null;
    }
}

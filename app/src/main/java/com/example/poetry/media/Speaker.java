package com.example.poetry.media;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.R;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Voice;
import com.example.poetry.tts.EspeakEngine;

import java.util.List;

/**
 * Single entry point for speech playback (the only UI-facing surface).
 *
 * This app uses ONLY the bundled eSpeak-NG offline engine (libttsespeak.so + espeak-ng-data).
 * System TextToSpeech is intentionally NOT offered, so voice output is consistent across devices.
 * If eSpeak-NG fails to initialise, playback surfaces show a message instead of silently switching engines.
 */
public final class Speaker {

    private static final String TAG = "Speaker";

    private static volatile Speaker instance;

    private final EspeakEngine espeak = EspeakEngine.get();

    private Listener listener;

    private final Listener forwarder = new Listener() {
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

    /** Kick off async eSpeak-NG preparation. */
    public void init(@NonNull Context context) {
        try {
            espeak.setListener(forwarder);
            espeak.prepare(context, null);
        } catch (Throwable error) {
            Log.w(TAG, "espeak prepare failed", error);
        }
    }

    /** Force eSpeak-NG preparation and invoke onReady on the main thread when done. */
    public void prepare(@NonNull Context context, @Nullable Runnable onReady) {
        espeak.setListener(forwarder);
        espeak.prepare(context, onReady);
    }

    public boolean isReady() {
        return espeak.isReady();
    }

    @NonNull
    public String statusText(@NonNull Context context) {
        return espeak.statusText(context);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @NonNull
    public List<Voice> listVoices() {
        return espeak.listVoices();
    }

    public boolean setVoice(@NonNull String voiceId) {
        return espeak.setVoice(voiceId);
    }

    @Nullable
    public String currentVoiceId() {
        return espeak.currentVoiceId();
    }

    public void apply(@NonNull TtsConfig config) {
        espeak.apply(config);
    }

    /** Backwards-compatible helper: change rate only. */
    public void setRate(float rate) {
        TtsConfig config = new TtsConfig();
        config.setRate(rate);
        espeak.apply(config);
    }

    public void speak(@NonNull String text) {
        espeak.speak(text);
    }

    public void stop() {
        espeak.stop();
    }

    public boolean isSpeaking() {
        return espeak.isSpeaking();
    }

    public void shutdown() {
        espeak.shutdown();
        listener = null;
    }

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

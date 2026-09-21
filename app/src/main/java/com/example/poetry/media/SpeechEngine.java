package com.example.poetry.media;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Voice;

import java.util.List;

/**
 * 语音合成引擎的统一抽象。
 * <p>
 * 当前唯一实现为 {@code EspeakEngine}：项目内置的 espeak-ng（离线、无网络依赖）。
 * 上层（详情页朗读条、音色弹层、语音设置页）只依赖本接口，
 * 因此切换或新增引擎不需要改动 UI。
 */
public interface SpeechEngine {

    /** 播放状态回调 */
    interface Listener {
        void onStart();

        void onDone();

        void onError(String message);
    }

    /** 引擎是否可用（库 + 数据 + 初始化均成功） */
    boolean isReady();

    /** 不可用时给用户的说明 */
    @NonNull
    String statusText(@NonNull Context context);

    void setListener(@Nullable Listener listener);

    /** 可选音色列表；引擎不可用时返回一个占位项，保证 UI 不空白 */
    @NonNull
    List<Voice> listVoices();

    /** 切换发音人 */
    boolean setVoice(@NonNull String voiceId);

    @Nullable
    String currentVoiceId();

    /** 应用完整配置（发音人 / 语速 / 音调 / 音量） */
    void apply(@NonNull TtsConfig config);

    void speak(@NonNull String text);

    void stop();

    boolean isSpeaking();

    void shutdown();
}

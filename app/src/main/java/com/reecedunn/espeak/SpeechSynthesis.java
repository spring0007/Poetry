/*
 * Copyright (C) 2012-2017 Reece H. Dunn
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * eSpeak-NG 的 JNI 绑定。
 *
 * 【重要】包名必须是 com.reecedunn.espeak —— 原生库 libttsespeak.so 中导出的符号为
 * Java_com_reecedunn_espeak_SpeechSynthesis_*，包名或类名一旦修改，JNI 将无法解析。
 *
 * 相比上游 android-eSpeak 的改动：
 *  1. 库加载失败不再抛出 UnsatisfiedLinkError，改为记录 sLibraryLoaded，供上层降级处理；
 *  2. 去掉 TextToSpeechService / PreferenceActivity 相关依赖，只保留纯合成能力；
 *  3. 数据路径由调用方传入（上游写死 context.getDir("voices")），便于管理。
 */
package com.reecedunn.espeak;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SpeechSynthesis {

    private static final String TAG = "SpeechSynthesis";

    public static final int GENDER_UNSPECIFIED = 0;
    public static final int GENDER_MALE = 1;
    public static final int GENDER_FEMALE = 2;

    public static final int AGE_ANY = 0;
    public static final int AGE_YOUNG = 12;
    public static final int AGE_OLD = 60;

    public static final int CHANNEL_COUNT_MONO = 1;
    public static final int FORMAT_PCM_S16 = 2;

    /** espeak_PARAMETER 枚举值（见 speak_lib.h） */
    public static final int PARAM_RATE = 1;
    public static final int PARAM_VOLUME = 2;
    public static final int PARAM_PITCH = 3;
    public static final int PARAM_PITCH_RANGE = 4;
    public static final int PARAM_PUNCTUATION = 5;
    public static final int PARAM_WORD_GAP = 7;

    /** 语速取值区间（words per minute） */
    public static final int RATE_MIN = 80;
    public static final int RATE_MAX = 449;
    public static final int RATE_NORMAL = 175;

    public static final int VOLUME_MAX = 200;
    public static final int PITCH_MAX = 100;

    private static boolean sLibraryLoaded = false;

    static {
        try {
            System.loadLibrary("ttsespeak");
            nativeClassInit();
            sLibraryLoaded = true;
        } catch (Throwable error) {
            // 缺少 .so / ABI 不匹配 / 系统限制：不影响 App 其它功能，由上层降级到系统 TTS
            Log.e(TAG, "无法加载 espeak-ng 原生库", error);
        }
    }

    /** 原生库是否可用（libttsespeak.so 加载且 JNI 方法表初始化成功） */
    public static boolean isLibraryLoaded() {
        return sLibraryLoaded;
    }

    @NonNull
    public static String getVersion() {
        if (!sLibraryLoaded) {
            return "";
        }
        try {
            String version = nativeGetVersion();
            return version == null ? "" : version;
        } catch (Throwable error) {
            Log.e(TAG, "获取 espeak 版本失败", error);
            return "";
        }
    }

    @Nullable
    private final SynthReadyCallback callback;

    private boolean initialized = false;
    private int sampleRate = 0;
    private int voiceCount = 0;

    /**
     * @param dataParentPath espeak-ng-data 目录的<b>父目录</b>；原生侧会自行拼接 "/espeak-ng-data"。
     * @param callback       音频数据回调，允许为 null（仅合成不播放时用得上）。
     */
    public SpeechSynthesis(@NonNull String dataParentPath, @Nullable SynthReadyCallback callback) {
        this.callback = callback;
        attemptInit(dataParentPath);
    }

    private void attemptInit(@NonNull String dataParentPath) {
        if (initialized) {
            return;
        }
        try {
            sampleRate = nativeCreate(dataParentPath);
        } catch (Throwable error) {
            Log.e(TAG, "初始化 espeak-ng 失败", error);
            sampleRate = 0;
        }
        if (sampleRate == 0) {
            Log.e(TAG, "初始化合成库失败：path=" + dataParentPath);
            return;
        }
        initialized = true;
        Log.i(TAG, "espeak-ng 就绪，采样率 = " + sampleRate);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannelCount() {
        return CHANNEL_COUNT_MONO;
    }

    public int getAudioFormat() {
        return FORMAT_PCM_S16;
    }

    public int getVoiceCount() {
        return voiceCount;
    }

    // ------------------------------------------------------------------ 音色

    /**
     * 列出数据目录中可用的音色。
     * 未初始化时返回空列表，调用方负责给出兜底展示。
     */
    @NonNull
    public List<Voice> getAvailableVoices() {
        List<Voice> voices = new ArrayList<>();
        if (!initialized) {
            return voices;
        }
        try {
            String[] results = nativeGetAvailableVoices();
            if (results == null) {
                return voices;
            }
            voiceCount = results.length / 4;
            for (int i = 0; i + 3 < results.length; i += 4) {
                Voice voice = buildVoice(results[i], results[i + 1], results[i + 2], results[i + 3]);
                if (voice != null) {
                    voices.add(voice);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "读取音色列表失败", error);
        }
        return voices;
    }

    @Nullable
    private static Voice buildVoice(String name, String identifier, String genderRaw, String ageRaw) {
        if (name == null || identifier == null) {
            return null;
        }
        int gender;
        int age;
        try {
            gender = Integer.parseInt(genderRaw);
            age = Integer.parseInt(ageRaw);
        } catch (NumberFormatException e) {
            return null;
        }
        Locale locale = localeFor(name);
        if (locale == null) {
            return null;
        }
        return new Voice(name, identifier, gender, age, locale);
    }

    /**
     * 由 espeak 的语言名（形如 cmn / zh / en-029 / es-419）构建 Locale。
     * 无法识别时返回 null，该音色会被跳过（与上游一致）。
     */
    @Nullable
    private static Locale localeFor(@NonNull String name) {
        Locale fixed = sLocaleFixes.get(name);
        if (fixed != null) {
            return fixed;
        }
        String[] parts = name.split("-");
        try {
            switch (parts.length) {
                case 1:
                    return new Locale(parts[0]);
                case 2:
                    return new Locale(parts[0], parts[1]);
                case 3:
                    return new Locale(parts[0], parts[1], parts[2]);
                default:
                    return new Locale(parts[0], parts[1], parts[3]);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 按名称（identifier）选定音色；支持 "identifier+variant" 变体写法 */
    public boolean setVoiceByName(@NonNull String name) {
        if (!initialized) {
            return false;
        }
        try {
            return nativeSetVoiceByName(name);
        } catch (Throwable error) {
            Log.w(TAG, "setVoiceByName 失败", error);
            return false;
        }
    }

    /** 按语言/性别/年龄三元组选定音色 */
    public boolean setVoiceByProperties(@NonNull String language, int gender, int age) {
        if (!initialized) {
            return false;
        }
        try {
            return nativeSetVoiceByProperties(language, gender, age);
        } catch (Throwable error) {
            Log.w(TAG, "setVoiceByProperties 失败", error);
            return false;
        }
    }

    // ------------------------------------------------------------------ 参数

    /**
     * 设置合成参数（espeak_PARAMETER）。
     *
     * @param parameter PARAM_* 常量
     * @param value     已按各参数量程换算好的整数值
     */
    public boolean setParameter(int parameter, int value) {
        if (!initialized) {
            return false;
        }
        try {
            return nativeSetParameter(parameter, value);
        } catch (Throwable error) {
            Log.w(TAG, "setParameter 失败", error);
            return false;
        }
    }

    /**
     * 读取合成参数。
     *
     * @param current 0 = 默认值，1 = 当前值
     */
    public int getParameter(int parameter, int current) {
        if (!initialized) {
            return 0;
        }
        try {
            return nativeGetParameter(parameter, current);
        } catch (Throwable error) {
            Log.w(TAG, "getParameter 失败", error);
            return 0;
        }
    }

    public void setPunctuationCharacters(@Nullable String characters) {
        if (!initialized || characters == null) {
            return;
        }
        try {
            nativeSetPunctuationCharacters(characters);
        } catch (Throwable error) {
            Log.w(TAG, "setPunctuationCharacters 失败", error);
        }
    }

    // ------------------------------------------------------------------ 合成

    /**
     * 同步合成：该方法会阻塞直到整段文本合成完毕（原生侧调用了 espeak_Synchronize）。
     * 音频数据通过 {@link SynthReadyCallback} 逐块回调出来，因此必须在后台线程调用。
     */
    public boolean synthesize(@NonNull String text, boolean isSsml) {
        if (!initialized) {
            return false;
        }
        try {
            return nativeSynthesize(text, isSsml);
        } catch (Throwable error) {
            Log.w(TAG, "合成失败", error);
            return false;
        }
    }

    /** 取消当前合成（espeak_Cancel） */
    public boolean stop() {
        if (!initialized) {
            return false;
        }
        try {
            return nativeStop();
        } catch (Throwable error) {
            Log.w(TAG, "停止合成失败", error);
            return false;
        }
    }

    /**
     * 原生回调入口：由 JNI 反射调用（见 nativeClassInit 缓存的方法 ID，签名 ([B)V）。
     * 不能改名，也不能改签名。
     */
    @SuppressWarnings("unused")
    private void nativeSynthCallback(byte[] audioData) {
        if (callback == null) {
            return;
        }
        if (audioData == null) {
            callback.onSynthDataComplete();
        } else {
            callback.onSynthDataReady(audioData);
        }
    }

    // ------------------------------------------------------------------ JNI

    private static native boolean nativeClassInit();

    private static native String nativeGetVersion();

    private native int nativeCreate(String path);

    private native String[] nativeGetAvailableVoices();

    private native boolean nativeSetVoiceByName(String name);

    private native boolean nativeSetVoiceByProperties(String language, int gender, int age);

    private native boolean nativeSetParameter(int parameter, int value);

    private native int nativeGetParameter(int parameter, int current);

    private native boolean nativeSetPunctuationCharacters(String characters);

    private native boolean nativeSynthesize(String text, boolean isSsml);

    private native boolean nativeStop();

    public interface SynthReadyCallback {
        /** 一块 16bit 小端 PCM 数据，可能为空字节数组 */
        void onSynthDataReady(byte[] audioData);

        /** 整段合成结束（包括被 {@link #stop()} 中断的情况） */
        void onSynthDataComplete();
    }

    private static final java.util.Map<String, Locale> sLocaleFixes = new java.util.HashMap<>();

    static {
        // 部分 BCP47 语言代码在 Android 上的 Locale 构造会失败，这里做映射修正
        sLocaleFixes.put("cmn", new Locale("zh"));      // 普通话
        sLocaleFixes.put("yue", new Locale("zh", "HK")); // 粤语
        sLocaleFixes.put("en-029", new Locale("en", "JM"));
        sLocaleFixes.put("es-419", new Locale("es", "MX"));
        sLocaleFixes.put("nan", new Locale("zh", "TW"));
    }
}

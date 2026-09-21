/*
 * Copyright (C) 2012-2013 Reece H. Dunn
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
 * eSpeak-NG 音色描述类。包名必须与 {@link SpeechSynthesis} 保持一致 JNI 约定，不可移动位置。
 */
package com.reecedunn.espeak;

import androidx.annotation.NonNull;

import java.util.Locale;

public class Voice {

    /** espeak 语言名，例如 cmn / yue / en */
    public final String name;
    /** 音色标识，用于 nativeSetVoiceByName，例如 asia/zh-cmn */
    public final String identifier;
    public final int gender;
    public final int age;
    public final Locale locale;

    public Voice(@NonNull String name, @NonNull String identifier, int gender, int age,
                 @NonNull Locale locale) {
        this.name = name;
        this.identifier = identifier;
        this.gender = gender;
        this.age = age;
        this.locale = locale;
    }

    @NonNull
    public String genderLabel() {
        switch (gender) {
            case SpeechSynthesis.GENDER_MALE:
                return "男声";
            case SpeechSynthesis.GENDER_FEMALE:
                return "女声";
            default:
                return "中性";
        }
    }

    @NonNull
    public String ageLabel() {
        if (age <= 0) {
            return "标准";
        }
        if (age < 30) {
            return "青年";
        }
        if (age < 60) {
            return "中年";
        }
        return "长者";
    }

    @NonNull
    @Override
    public String toString() {
        return name + " / " + identifier;
    }
}

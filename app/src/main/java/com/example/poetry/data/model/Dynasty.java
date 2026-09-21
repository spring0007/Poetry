package com.example.poetry.data.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 朝代（分类页「按朝代」宫格）。
 * <p>
 * {@code poetry.db → authors.dynasty} 存放的是小写 code（tang / song / yuan …），
 * 这里负责把 code 翻成中文标签，并按设计稿顺序排列。
 */
public enum Dynasty {

    PREQIN("preqin", "先秦"),
    HAN("han", "汉"),
    TANG("tang", "唐"),
    WUDAI("wudai", "五代"),
    SONG("song", "宋"),
    YUAN("yuan", "元"),
    MING("ming", "明"),
    QING("qing", "清"),
    UNKNOWN("unknown", "未详");

    private final String code;
    private final String label;

    Dynasty(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    @NonNull
    public static Dynasty fromCode(@Nullable String code) {
        if (code != null) {
            for (Dynasty dynasty : values()) {
                if (dynasty.code.equals(code)) {
                    return dynasty;
                }
            }
        }
        return UNKNOWN;
    }

    /** 展示用中文名（未详 -> 「未详」） */
    @NonNull
    public static String labelOf(@Nullable String code) {
        return fromCode(code).label;
    }
}

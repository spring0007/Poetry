package com.example.poetry.data.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 朝代（分类页「按朝代」宫格）。
 * <p>
 * {@code poetry.db → authors.dynasty} 存放的是小写 code（tang / song / yuan …），
 * 这里负责把 code 翻成中文标签，并按设计稿顺序排列。
 * <p>
 * 枚举顺序就是宫格顺序（{@code PoetryDatabase.dynastyCategories()} 按 {@code values()} 遍历），
 * 所以新值插在对应的年代位置上。注意库里除了真正的朝代，还混着
 * {@code chuci}（楚辞）/ {@code nalan}（纳兰词）/ {@code mengxue}（蒙学）/ {@code modern}（近现代）
 * 这几个「不是朝代」的分组——它们在作者表里就是这么标的，照收；{@link #UNKNOWN} 必须留在最后，
 * 它是 {@link #fromCode} 的兜底桶。
 */
public enum Dynasty {

    PREQIN("preqin", "先秦"),
    CHUCI("chuci", "楚辞"),
    HAN("han", "汉"),
    TANG("tang", "唐"),
    WUDAI("wudai", "五代"),
    SONG("song", "宋"),
    YUAN("yuan", "元"),
    MING("ming", "明"),
    QING("qing", "清"),
    NALAN("nalan", "纳兰词"),
    MODERN("modern", "近现代"),
    MENGXUE("mengxue", "蒙学"),
    /** 自建条目：后端的 dynastyLabels 里有这一档，缺了它这些作者会全部显示成「未详」 */
    CUSTOM("custom", "自建"),
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

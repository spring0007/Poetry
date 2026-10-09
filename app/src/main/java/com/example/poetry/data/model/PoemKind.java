package com.example.poetry.data.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.R;

/**
 * 体裁（分类页「按体裁」五种大卡）。
 * <p>
 * 与 UI 设计稿一一对应：诗 / 词 / 曲 / 赋 / 民歌，
 * 配色取自 {@code values/colors.xml} 的中国传统色（朱红 / 黛紫 / 石青 / 竹绿 / 民歌橙）。
 * <p>
 * 数据来源：{@code poetry.db} 的 {@code sources.name}（如 tang-poem、song-ci、yuan-qu）。
 */
public enum PoemKind {

    SHI("shi", "诗", "唐诗宋诗 · 五七言", R.color.zhuhong_500, R.color.zhuhong_100, "诗"),
    CI("ci", "词", "宋词长短句 · 依谱而歌", R.color.daizi_500, R.color.daizi_100, "词"),
    QU("qu", "曲", "元曲杂剧 · 俚俗生动", R.color.shiqing_500, R.color.shiqing_100, "曲"),
    FU("fu", "赋", "铺采摛文 · 体物写志", R.color.zhulv_500, R.color.zhulv_100, "赋"),
    FOLK("folk", "民歌", "汉乐府 · 先秦歌谣", R.color.minge_500, R.color.minge_100, "歌");

    private final String code;
    private final String label;
    private final String desc;
    private final int colorRes;
    private final int lightColorRes;
    private final String mark;

    PoemKind(String code, String label, String desc, int colorRes, int lightColorRes, String mark) {
        this.code = code;
        this.label = label;
        this.desc = desc;
        this.colorRes = colorRes;
        this.lightColorRes = lightColorRes;
        this.mark = mark;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public String getDesc() {
        return desc;
    }

    /** 体裁主色（色条、徽标、水印字） */
    public int getColorRes() {
        return colorRes;
    }

    /** 体裁浅色（卡片柔和底色） */
    public int getLightColorRes() {
        return lightColorRes;
    }

    /** 体裁卡右下角巨型水印字 */
    public String getMark() {
        return mark;
    }

    /**
     * 由 {@code poetry.db → sources.name} 反解体裁。
     */
    @NonNull
    public static PoemKind fromSource(@Nullable String srcName) {
        if (srcName == null || srcName.isEmpty()) {
            return SHI;
        }
        switch (srcName) {
            case "song-ci":
            case "wudai-nantang":
            case "wudai-huajianji":
                return CI;
            case "yuan-qu":
                return QU;
            case "fu":
            case "han-fu":
            case "tang-fu":
            case "song-fu":
                return FU;
            case "han-poem":
            case "preqin-poem":
                return FOLK;
            case "tang-poem":
            case "song-poem":
            case "qing-poetry":
            case "jinxiandai":
            case "caocao":
            case "shuimo-tangshi":
            case "nalanxingde":
            case "shijing":
            case "chuci":
            case "sishuwujing":
            case "lunyu":
            case "mengxue":
            case "youmengying":
            case "preqin-classic":
            case "unknown-classic":
            default:
                return SHI;
        }
    }

    /**
     * 由体裁 code 反解枚举，未命中时返回「诗」。
     */
    @NonNull
    public static PoemKind fromCode(@Nullable String code) {
        if (code == null) {
            return SHI;
        }
        for (PoemKind kind : values()) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        return SHI;
    }

    /**
     * 该体裁对应的 {@code sources.name} 列表，用于 SQL 的 IN 过滤。
     *
     * <p>注意 {@link #FOLK}：它映射的 {@code han-poem} / {@code preqin-poem} 在诗库里
     * **并不存在**（先秦那一批都归在 shijing / chuci / lunyu 底下），所以「民歌」卡片一直是 0 首。
     * 这是历史遗留，不是漏配——要改得先决定民歌到底对应哪些 source。
     */
    @NonNull
    public String[] sourceNames() {
        switch (this) {
            case CI:
                return new String[]{"song-ci", "wudai-nantang", "wudai-huajianji"};
            case QU:
                return new String[]{"yuan-qu"};
            case FU:
                return new String[]{"fu", "han-fu", "tang-fu", "song-fu"};
            case FOLK:
                return new String[]{"han-poem", "preqin-poem"};
            case SHI:
            default:
                return new String[]{
                        "tang-poem", "song-poem", "qing-poetry", "jinxiandai", "caocao",
                        "shuimo-tangshi", "nalanxingde", "shijing", "chuci", "sishuwujing",
                        "lunyu", "mengxue", "youmengying", "preqin-classic", "unknown-classic"
                };
        }
    }
}

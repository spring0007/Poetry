package com.example.poetry.util;

import androidx.annotation.NonNull;

/**
 * 检索词归一化。
 * <p>
 * {@code poetry.db} 的 meta 指明：{@code text_form = simplified-nospace}，即正文是
 * 「简体 + 去空格」的归一化文本，且未开启 FTS。因此查询前必须把用户输入做同样处理，
 * 否则带空格或繁体的关键词永远命中不了。
 */
public final class QueryNormalizer {

    /** 常见繁体字 -> 简体字（覆盖高频字，完整转换建议接入 OpenCC） */
    private static final String TRADITIONAL = "詩詞賦曲漢樂府風雲月華燈夢鄉還見聽讀書寫學國寶萬點無為愛語錄體餘幾";
    private static final String SIMPLIFIED = "诗词赋曲汉乐府风云月华灯梦乡还见听读书写学国宝万点无为爱语录体余几";

    private QueryNormalizer() {
    }

    /**
     * 归一化：去空白、去标点、繁转简。
     */
    @NonNull
    public static String normalize(@NonNull String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (isPunctuation(c)) {
                continue;
            }
            int idx = TRADITIONAL.indexOf(c);
            if (idx >= 0 && idx < SIMPLIFIED.length()) {
                sb.append(SIMPLIFIED.charAt(idx));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isPunctuation(char c) {
        if (c >= '一' && c <= '龥') {
            return false;
        }
        return !Character.isLetterOrDigit(c);
    }

    /**
     * 生成 LIKE 参数：转义 % 与 _，避免用户输入被当作通配符。
     */
    @NonNull
    public static String like(@NonNull String keyword) {
        return "%" + normalize(keyword).replace("%", "\\%").replace("_", "\\_") + "%";
    }
}

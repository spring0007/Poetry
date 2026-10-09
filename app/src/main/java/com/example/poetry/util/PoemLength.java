package com.example.poetry.util;

import android.content.Context;

import androidx.annotation.NonNull;

import com.example.poetry.R;
import com.example.poetry.data.model.Poem;

/**
 * 卡片上那一格「篇幅」标记：本地数得清句数就写「N 句」，
 * 只有服务端给的 nChar 就写「N 字」，两者都没有就留空。
 *
 * <p>存在的理由：远端列表接口**只带 excerpt + nChar、不带 body**（见 {@code JsonMapper}
 * 类注释），而「N 句」是从正文按 '\n' 数出来的。直接拿 {@code getLineCount()} 渲染，
 * 远端那一页的每张卡都会写着「0 句」—— 用户看到的是「这首诗没有内容」，不是「还没拉到正文」。
 * 与其硬凑一个 0，不如换成服务端确实给了的字数。
 */
public final class PoemLength {

    private PoemLength() {
    }

    /**
     * @return 形如 {@code "12 句"} / {@code "120 字"}；无从判断时返回空串（调用方自行决定留白）
     */
    @NonNull
    public static String label(@NonNull Context context, @NonNull Poem poem) {
        int lines = poem.getLineCount();
        if (lines > 0) {
            return context.getString(R.string.poem_line_count, lines);
        }
        int chars = poem.getNChar();
        if (chars > 0) {
            return context.getString(R.string.poem_char_count, chars);
        }
        // 正文和 nChar 都没有：宁可留白也不写「0 句」，那是句谎话
        return "";
    }
}

package com.example.poetry.media;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 把整首诗文切成可逐段合成的片段。
 * <p>
 * 存在的理由有两条：一是单次合成的耗时直接决定「停止」的响应速度——整首长诗一次
 * 合成完，点了停止还得等好几秒才静音；二是任何引擎对单次输入的字符数都有上限，
 * 超了会被静默截断（读一半就没了，而且不报错）。
 * <p>
 * 切点选在句末标点（换行也当作句末），单句本身超长时硬切。
 */
final class TextChunker {

    /**
     * 单段上限。按正常语速约合十几秒音频：既在引擎的输入上限之内，
     * 又短到「停止」不会等太久。
     */
    private static final int CHUNK_LIMIT = 1000;

    private TextChunker() {
    }

    @NonNull
    static List<String> split(@NonNull String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char raw = text.charAt(i);
            char c = (raw == '\n' || raw == '\r') ? '。' : raw;
            current.append(c);
            if (isBoundary(c) || current.length() >= CHUNK_LIMIT) {
                if (hasContent(current)) {
                    chunks.add(current.toString());
                }
                current.setLength(0);
            }
        }
        if (hasContent(current)) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static boolean isBoundary(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' || c == '…';
    }

    /** 全是标点或空白的片段没必要送去合成。 */
    private static boolean hasContent(@NonNull CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}

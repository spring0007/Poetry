package com.example.poetry.media;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 把整首诗文切成可逐段合成的片段。
 * <p>
 * 存在的理由有三条：单次合成的耗时直接决定「出声」有多快与「停止」有多干脆；任何引擎对
 * 单次输入都有字符数上限，超了会被静默截断；以及——诗词有它自己的节奏，按标点机械地切
 * 会把诗读成「一个个字」。
 * <p>
 * 所以切分分两步：
 * <ol>
 *   <li><b>先按「联」组织</b>：诗行两两成对，对内用「，」相连、对末用「。」收尾。
 *       绝句因此变成两个合成单元而不是四行四段，句调才有机会连贯；</li>
 *   <li><b>再把若干单元打包成块</b>：第一块刻意小（{@link #FIRST_BLOCK_LIMIT}），
 *       让第一个音尽快出来；后续块大一些，减少调度开销。</li>
 * </ol>
 * 注意 sherpa-onnx 内部仍会按标点切句逐句合成，所以「合并成块」不会跨句做韵律，
 * 真正影响韵律的是第 1 步里我们对停顿标点的选择。
 */
final class TextChunker {

    /**
     * 第一块的字数上限。合成是「先算完再出声」，所以这个数字直接等于首音延迟：
     * 24 字约合 5 秒音频，中端机上约 1 秒左右能出声。
     */
    private static final int FIRST_BLOCK_LIMIT = 24;

    /**
     * 后续块的上限。引擎内部还会按标点切句，所以这里只影响队列粒度与内存峰值
     * （60 字 ≈ 13 秒音频 ≈ 1.2 MB float），不影响韵律。
     */
    private static final int BLOCK_LIMIT = 60;

    /**
     * 单块的绝对上限。只用于兜底：真出现一整段没有标点的长文本时，硬切到这里为止，
     * 免得一次合成几百秒音频——那会让「出声」等到天荒地老，内存也会炸。
     */
    private static final int HARD_LIMIT = 200;

    private TextChunker() {
    }

    @NonNull
    static List<String> split(@NonNull String text) {
        List<String> units = units(text);
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int limit = FIRST_BLOCK_LIMIT;
        for (String unit : units) {
            if (current.length() > 0 && current.length() + unit.length() > limit) {
                blocks.add(current.toString());
                current.setLength(0);
                limit = BLOCK_LIMIT;
            }
            current.append(unit);
        }
        if (current.length() > 0) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    /**
     * 把正文拆成一个个「联」。没有换行的文本（散文、或正文里没有分行）退化成按句末标点拆。
     */
    @NonNull
    private static List<String> units(@NonNull String text) {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("[\n\r]+")) {
            String line = raw.trim();
            if (hasContent(line)) {
                lines.add(line);
            }
        }
        if (lines.size() <= 1) {
            return capped(sentences(lines.isEmpty() ? text : lines.get(0)));
        }
        List<String> units = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += 2) {
            String first = lines.get(i);
            if (i + 1 >= lines.size()) {
                units.add(terminate(first, '。'));
                continue;
            }
            String second = lines.get(i + 1);
            // 上句没标点就补「，」，下句没标点就补「。」——这是诗词的联内/联末节奏
            units.add(terminate(first, '，') + terminate(second, '。'));
        }
        return capped(units);
    }

    /**
     * 把超过 {@link #HARD_LIMIT} 的单元再按句末标点拆一遍，仍超长的硬切。
     * 正常诗词走不到这里，但正文里混进序、注或没有标点的长段时它是最后一道保险。
     */
    @NonNull
    private static List<String> capped(@NonNull List<String> units) {
        List<String> result = new ArrayList<>();
        for (String unit : units) {
            if (unit.length() <= HARD_LIMIT) {
                result.add(unit);
                continue;
            }
            for (String piece : sentences(unit)) {
                for (int start = 0; start < piece.length(); start += HARD_LIMIT) {
                    result.add(piece.substring(start,
                            Math.min(piece.length(), start + HARD_LIMIT)));
                }
            }
        }
        return result;
    }

    /** 按句末标点切句，标点跟着前一句。用于没有分行的文本。 */
    @NonNull
    private static List<String> sentences(@NonNull String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (isBoundary(c)) {
                if (hasContent(current)) {
                    result.add(current.toString());
                }
                current.setLength(0);
            }
        }
        if (hasContent(current)) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * 保证片段以指定标点收尾：已经有句末标点就原样返回，否则补一个。
     * <p>
     * 补标点是让模型知道「这里要停一下」——不给标点，它会把两行连读成一个长句。
     */
    @NonNull
    private static String terminate(@NonNull String line, char mark) {
        char last = line.charAt(line.length() - 1);
        return isBoundary(last) || last == '，' || last == '、' ? line : line + mark;
    }

    private static boolean isBoundary(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' || c == '…'
                || c == '!' || c == '?' || c == ';';
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

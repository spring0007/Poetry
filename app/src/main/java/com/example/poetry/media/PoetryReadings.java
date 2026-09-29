package com.example.poetry.media;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 诗词的「古读 / 叶韵」读音修正：在送进合成引擎之前，把该读古音的字换成同音字。
 * <p>
 * <b>为什么需要它。</b>引擎是<b>词典注音</b>的（{@code lexicon.txt} + {@code phone.fst}），
 * 走的是普通话读音；{@code new_heteronym.fst} 只覆盖现代汉语的常见异读，不含诗词里的
 * 叶韵与古读。于是「远上寒山石径<b>斜</b>」被读成 xié、「乡音无改鬓毛<b>衰</b>」被读成
 * shuāi、「风吹草低<b>见</b>牛羊」被读成 jiàn——对诗词 App 来说这是最刺耳的错。
 * <p>
 * <b>为什么用同音字替换。</b>词典注音模型的正规改法是改词典或多音字规则 FST，但前者要
 * 维护一份大词典，后者要编译 Kaldi FST（工具链成本高、调试难）。而只要换成一个<b>同音
 * 且词典里读音唯一</b>的字，模型就会照着那个读音发声——「霞」只有 xiá 一个读音，比去改
 * 「斜」的多音字规则可靠得多。
 * <p>
 * <b>只影响发声，不影响界面。</b>替换只作用在传给引擎的字符串上，正文、拼音、注释、
 * 显示的仍是原文。所以即便这里某个条目有争议，用户看到的也不会是错字。
 * <p>
 * <b>两条规则，按风险从低到高：</b>
 * <ol>
 *   <li>{@link #ENDING_READINGS}：<b>句末叶韵</b>。诗词的韵脚绝大多数落在句尾，
 *       所以「斜/衰 出现在行尾」就按古读处理，不需要枚举具体是哪首诗；</li>
 *   <li>{@link #PHRASES}：<b>短语级异读</b>。句中位置无法用位置规则判断
 *       （「悠然见南山」的「见」就得读 jiàn，不能一律改 xiàn），只能逐条列出，
 *       每条都带出处，方便核对与增删。</li>
 * </ol>
 * 这张表是**可以随时扩充的**：加一行、重跑一次即可，不需要动引擎或模型。
 */
final class PoetryReadings {

    /**
     * 句末叶韵表：{@link #ENDING_FROM} 第 n 个字落在行尾时，换成 {@link #ENDING_TO}
     * 第 n 个字。两者必须<b>同音</b>。
     * <p>
     * 例：斜 xiá→霞 xiá（远上寒山石径斜）、衰 cuī→崔 cuī（乡音无改鬓毛衰）。
     */
    private static final String ENDING_FROM = "斜衰";
    private static final String ENDING_TO = "霞崔";

    /**
     * 短语级异读表：{@code {原文, 合成用字}}，两者必须同音。
     * <p>
     * 全部按「整句/固定搭配」匹配，避免误伤——「一骑红尘」要改，但普通的「骑马」不改。
     * 每条后面的注释是出处；标「旧读」的是传统诵读读法，若觉得不合口味可整行删掉。
     */
    private static final String[][] PHRASES = {
            // 见 xiàn：现
            {"风吹草低见牛羊", "风吹草低现牛羊"},   // 敕勒歌
            {"图穷匕见", "图穷匕现"},               // 战国策
            // 骑 jì：寄
            {"一骑红尘", "一寄红尘"},               // 杜牧《过华清宫》
            {"千骑拥高牙", "千寄拥高牙"},           // 柳永《望海潮》
            {"胡骑", "胡寄"},                       // 高适《燕歌行》
            // 重 chóng：崇
            {"万重山", "万崇山"},                   // 李白《早发白帝城》
            {"万重恩怨", "万崇恩怨"},               // 龚自珍
            {"千重", "千崇"},                       // 李商隐《无题》
            {"重霄", "崇霄"},                       // 王勃《滕王阁序》
            {"重九", "崇九"},                       // 重阳
            // 裳 cháng：常
            {"霓裳", "霓常"},                       // 白居易《长恨歌》
            // 燕 yān：烟（地名/国名，不是「燕子」）
            {"燕山", "烟山"},                       // 范仲淹《渔家傲》
            {"燕然", "烟然"},                       // 范仲淹《渔家傲》
            {"燕赵", "烟赵"},                       // 韩愈
            {"幽燕", "幽烟"},                       // 杜甫
            // 将 qiāng：枪
            {"将进酒", "枪进酒"},                   // 李白《将进酒》乐府题
            // 说 shuì：税
            {"游说", "游税"},
            // 度 duó：夺
            {"忖度", "忖夺"},
            // 冠 guàn：灌
            {"弱冠", "弱灌"},                       // 王勃《滕王阁序》
            // 咽 yè：夜
            {"幽咽", "幽夜"},                       // 白居易《琵琶行》
            {"呜咽", "呜夜"},
            // 汗 hán：寒
            {"可汗", "客寒"},                       // 木兰诗
            // 笼 lǒng：拢
            {"笼盖四野", "拢盖四野"},               // 敕勒歌
            // 胜 shēng：声（旧读；「高处不胜寒」等，现代标准为 shèng，可整行删除）
            {"不胜寒", "不声寒"},                   // 苏轼《水调歌头》
    };

    /** 长短语优先，避免「千重」抢先匹配掉「万重山」这种包含关系。 */
    private static final String[][] ORDERED = ordered();

    private PoetryReadings() {
    }

    @NonNull
    private static String[][] ordered() {
        List<String[]> list = new ArrayList<>(Arrays.asList(PHRASES));
        list.sort(Comparator.comparingInt((String[] entry) -> entry[0].length()).reversed());
        return list.toArray(new String[0][]);
    }

    /**
     * 返回「用来发声」的文本。行结构与标点原样保留，只有该读古音的字被换掉，
     * 这样 {@link TextChunker} 仍能按行/联切分。
     */
    @NonNull
    static String forSpeech(@NonNull String text) {
        if (text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            boolean atEnd = i == text.length();
            if (!atEnd && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                continue;
            }
            if (i > start) {
                out.append(readLine(text.substring(start, i)));
            }
            if (!atEnd) {
                out.append(text.charAt(i));
            }
            start = i + 1;
        }
        return out.toString();
    }

    /** 处理一行：先套短语表，再套句末叶韵。 */
    @NonNull
    private static String readLine(@NonNull String line) {
        String result = line;
        for (String[] entry : ORDERED) {
            if (result.contains(entry[0])) {
                result = result.replace(entry[0], entry[1]);
            }
        }
        return applyEnding(result);
    }

    /** 行尾（允许后面跟一个标点）命中叶韵表就替换。 */
    @NonNull
    private static String applyEnding(@NonNull String line) {
        int end = line.length() - 1;
        while (end >= 0 && isTrailingPunctuation(line.charAt(end))) {
            end--;
        }
        if (end < 0) {
            return line;
        }
        int index = ENDING_FROM.indexOf(line.charAt(end));
        if (index < 0) {
            return line;
        }
        return line.substring(0, end) + ENDING_TO.charAt(index) + line.substring(end + 1);
    }

    private static boolean isTrailingPunctuation(char c) {
        return c == '。' || c == '，' || c == '！' || c == '？' || c == '；'
                || c == '、' || c == '…' || c == '.' || c == ',' || c == '!' || c == '?';
    }
}

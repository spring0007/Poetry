package com.example.poetry.util;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 汉字拼音查询。
 * <p>
 * 数据来自 {@code assets/pinyin.txt}：每行「汉字 + 制表符 + 带声调拼音」，
 * 收录了诗库中出现的全部一万多个汉字，因此正文里不会有查不到的字。
 * <p>
 * 首次使用时同步载入（约 96 KB，十几毫秒），之后常驻内存。
 * 想换成更完整的词库（含多音词）只要替换这个文件和 {@link #ASSET} 常量即可。
 */
public final class Pinyin {

    private static final String ASSET = "pinyin.txt";

    private static volatile Map<Character, String> dict;
    private static volatile boolean failed;

    private Pinyin() {
    }

    /**
     * 查询单个汉字的拼音；查不到（标点、生僻字、数据未载入）时返回空串。
     * 返回空串时调用方只需不绘制拼音即可，不会破坏正文排版。
     */
    @NonNull
    public static String of(char ch) {
        Map<Character, String> table = dict;
        if (table == null && !failed) {
            table = load();
        }
        if (table == null) {
            return "";
        }
        String value = table.get(ch);
        return value == null ? "" : value;
    }

    /** 拼音数据是否可用（用于界面提示） */
    public static boolean isReady() {
        return dict != null;
    }

    /** 后台预热，避免第一次打开拼音时卡一下 */
    public static void warm(@Nullable Context context) {
        if (context == null || dict != null || failed) {
            return;
        }
        new Thread(() -> load(context.getApplicationContext())).start();
    }

    private static synchronized Map<Character, String> load(@NonNull Context context) {
        if (dict != null || failed) {
            return dict;
        }
        AssetManager assets = context.getAssets();
        Map<Character, String> table = new HashMap<>(12000);
        try (InputStream in = assets.open(ASSET);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() < 2) {
                    continue;
                }
                char ch = line.charAt(0);
                String py = line.charAt(1) == '\t' ? line.substring(2).trim() : "";
                if (ch >= '\u4e00' && ch <= '\u9fff' && !py.isEmpty()) {
                    table.put(ch, py);
                }
            }
            dict = table;
        } catch (Exception e) {
            failed = true;
            return null;
        }
        return dict;
    }

    /** 无 Context 时的兜底载入（极少发生，失败就静默降级为不显示拼音） */
    @Nullable
    private static Map<Character, String> load() {
        return null;
    }
}

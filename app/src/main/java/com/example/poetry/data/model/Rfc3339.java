package com.example.poetry.data.model;

import androidx.annotation.Nullable;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * 时间字段的统一解析：同一个值既可能是服务端发来的 RFC3339 字符串，也可能是本机缓存里的毫秒数。
 *
 * <p>为什么需要它：{@code UserProfile} 走的是「服务端响应和本地缓存共用一条解析路径」的
 * 设计（见其类注释），但服务端给的时间是 {@code "2026-10-09T12:00:00.123456789+08:00"}，
 * 而 {@link UserProfile#toJson()} 落盘时写的是 {@code long}。只认字符串的解析器读缓存
 * 一定得到 0 —— 表现为「注册时间、会员到期时间在线时正常，杀掉进程再打开就变回未知」，
 * 而且这个错位只在重启后才现形，看代码看不出来。
 *
 * <p>所以这里一个入口吃两种形状：{@link Number}（缓存）与字符串（服务端）。
 *
 * <p>不用 {@code java.time}：minSdk 24 上它要靠 desugaring 才有，而 {@code Jwt}
 * 已经为了同一个原因绕开过一次时间解析。
 */
public final class Rfc3339 {

    /** Go 的时间串会带纳秒（最多 9 位小数秒），{@link SimpleDateFormat} 吃不下。 */
    private static final Pattern FRACTION = Pattern.compile("\\.\\d+");

    private Rfc3339() {
    }

    /**
     * 把 {@code raw} 解析成毫秒时间戳。
     *
     * @param raw 毫秒数（{@link Number}，本机缓存的形状）或 Go 的 RFC3339 字符串；其余一律 0
     * @return 毫秒时间戳；字段缺失、为 null 或格式不认识时返回 0，由调用方按「未知」处理
     */
    public static long parse(@Nullable Object raw) {
        if (raw instanceof Number) {
            long value = ((Number) raw).longValue();
            return value > 0L ? value : 0L;
        }
        if (!(raw instanceof String)) {
            return 0L;   // JSONObject.NULL、布尔、数组……都不是时间
        }
        String text = ((String) raw).trim();
        if (text.isEmpty()) {
            return 0L;
        }
        // 已经被当成数字存过的字符串（例如 {"expireAt":"1735689600000"}）也认，
        // 免得换个写法就整段解析不出来
        if (isAllDigits(text)) {
            try {
                long value = Long.parseLong(text);
                return value > 0L ? value : 0L;
            } catch (NumberFormatException tooBig) {
                return 0L;
            }
        }

        // SimpleDateFormat 比 RFC3339 严，所以先做两步规整：砍掉小数秒，时区去掉冒号
        // （+08:00 → +0800），Z 等价于 +0000。
        String normalized = FRACTION.matcher(text).replaceFirst("");
        if (normalized.endsWith("Z") || normalized.endsWith("z")) {
            normalized = normalized.substring(0, normalized.length() - 1) + "+0000";
        } else if (normalized.length() >= 6 && normalized.charAt(normalized.length() - 3) == ':') {
            normalized = normalized.substring(0, normalized.length() - 3)
                    + normalized.substring(normalized.length() - 2);
        }
        // "yyyy-MM-dd HH:mm:ss"：服务端也有几处不带 T、不带时区的写法（按 UTC 解释）
        SimpleDateFormat format = new SimpleDateFormat(
                normalized.indexOf('T') >= 0 ? "yyyy-MM-dd'T'HH:mm:ssZ" : "yyyy-MM-dd HH:mm:ss",
                Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date parsed = format.parse(normalized);
            return parsed == null ? 0L : parsed.getTime();
        } catch (ParseException notATime) {
            return 0L;
        }
    }

    private static boolean isAllDigits(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}

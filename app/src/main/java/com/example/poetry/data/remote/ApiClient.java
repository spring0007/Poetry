package com.example.poetry.data.remote;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.local.UserStore;
import com.example.poetry.util.LogUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后端 HTTP 客户端。
 *
 * <p>用 {@link HttpURLConnection} 而不是 OkHttp / Retrofit：本工程的依赖表是刻意精简的
 * （见 {@code app/build.gradle.kts}），而这里要的只有「发个 JSON 收个 JSON」，
 * 标准库完全够，为它多引两个 AAR 不划算。真有复杂需求（连接池复用、拦截器链）时再换。
 *
 * <p><b>阻塞式</b>：所有方法都在调用线程上跑完，调用方负责放到
 * {@code AppExecutors.io()} 上。这样仓库层能写成直线代码，
 * 「远程 → 本地 → 内置数据」的三级回退一眼看得明白。
 *
 * <h3>熔断为什么是必需的</h3>
 * 后端不可达时每次请求都要耗掉整个连接超时。没有熔断的话，{@code 5s 超时 × 首屏 4 个并发查询}
 * 会让发现页转十几秒，而其实本地库早就有数据。所以连续 {@link ApiConfig#BREAKER_THRESHOLD}
 * 次「服务端有问题」的失败之后，直接短路一段时间，让本地库无感接管；
 * 冷却结束放一个请求出去探路，通了就自动恢复。
 */
public final class ApiClient {

    /** 单次响应体上限。检索接口正常也就几十 KB，超过这个量级说明对端不是我们预期的服务。 */
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    /** 失败响应体只截这么多字符进异常消息，避免把整页 HTML 错误页塞进 toast */
    private static final int ERROR_BODY_LIMIT = 300;

    private final Context appContext;

    // ------------------------- 熔断状态 -------------------------
    //
    // 用 static：熔断描述的是「后端现在通不通」，这是全局事实，不是某个实例的状态。
    // 用 elapsedRealtime 而不是 currentTimeMillis：后者会被用户改系统时间拨动，
    // 拨到过去就等于熔断永不过期。

    private static final AtomicInteger failures = new AtomicInteger(0);
    private static volatile long breakerOpenUntil = 0L;

    public ApiClient(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    // ------------------------------------------------------------ 状态判断

    /** 后端是否被配置且启用 */
    public boolean isEnabled() {
        return ApiConfig.ENABLED && !ApiConfig.BASE_URL.isEmpty();
    }

    /** 能不能立刻发请求（关掉了 / 正在熔断都返回 false） */
    public boolean isReachableNow() {
        return isEnabled() && !isBreakerOpen();
    }

    /** 是否处于熔断冷却中 */
    public static boolean isBreakerOpen() {
        return SystemClock.elapsedRealtime() < breakerOpenUntil;
    }

    /** 熔断剩余毫秒数（未熔断时返回 0） */
    public static long breakerRemainingMs() {
        long remain = breakerOpenUntil - SystemClock.elapsedRealtime();
        return remain > 0 ? remain : 0;
    }

    /** 人工复位（「重试」按钮、或检测到网络重新连上时调用） */
    public static void resetBreaker() {
        failures.set(0);
        breakerOpenUntil = 0L;
    }

    // ------------------------------------------------------------ 请求

    /**
     * GET 一个接口，返回响应体里的 {@code data}。
     *
     * @return 可能是 {@link JSONObject} / {@link JSONArray} / 字符串 / 数字 / {@code null}
     */
    @Nullable
    public Object get(@NonNull String endpoint) throws ApiException {
        return get(endpoint, null);
    }

    @Nullable
    public Object get(@NonNull String endpoint, @Nullable Map<String, String> query) throws ApiException {
        return execute("GET", endpoint, query, null);
    }

    /** POST 一个 JSON 请求体，返回响应体里的 {@code data}。 */
    @Nullable
    public Object post(@NonNull String endpoint, @Nullable JSONObject body) throws ApiException {
        return execute("POST", endpoint, null, body == null ? "{}" : body.toString());
    }

    /**
     * 把接口返回的相对地址补成绝对地址。
     *
     * <p>后端回的音频地址是 {@code /tts/xxx.mp3} —— 相对根路径，故意不带域名：
     * 域名由客户端配置决定，服务端把自己写死进 URL 只会在换域名时炸掉。
     * 播放器不认相对路径，所以在写进播放列表之前补一次。
     */
    @NonNull
    public String absolute(@Nullable String maybeRelative) {
        String path = maybeRelative == null ? "" : maybeRelative.trim();
        if (path.isEmpty()) {
            return "";
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        return ApiConfig.BASE_URL + (path.startsWith("/") ? path.substring(1) : path);
    }

    // ------------------------------------------------------------ 实际执行

    @Nullable
    private Object execute(@NonNull String method, @NonNull String endpoint,
                           @Nullable Map<String, String> query, @Nullable String jsonBody)
            throws ApiException {

        if (ApiConfig.ENABLED == false || ApiConfig.BASE_URL.isEmpty()) {
            throw new ApiException(ApiException.NOT_IMPLEMENTED, "后端未启用（ApiConfig.ENABLED=false）");
        }
        if (isBreakerOpen()) {
            // 短路：不发真实请求，也不计失败。让调用方立刻走本地兜底。
            throw new ApiException(ApiException.BREAKER_OPEN,
                    "后端暂时不可用，冷却 " + (breakerRemainingMs() / 1000) + " 秒后重试");
        }

        HttpURLConnection conn = null;
        try {
            String url = ApiConfig.url(endpoint) + buildQuery(query);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(ApiConfig.CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(ApiConfig.READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");

            String token = token();
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }

            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(jsonBody.getBytes("UTF-8"));
                }
            }

            int status = conn.getResponseCode();
            String body = readBody(status >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (status < 200 || status >= 300) {
                ApiException e = httpError(status, body);
                noteFailure(e);
                throw e;
            }

            Object data = parseEnvelope(body);
            noteSuccess();
            return data;

        } catch (ApiException e) {
            // 已经在上面计过失败的（HTTP 分支）不再重复计
            if (e.getCode() != ApiException.HTTP && e.getCode() != ApiException.BUSINESS) {
                noteFailure(e);
            }
            throw e;
        } catch (Exception e) {
            ApiException wrapped = new ApiException(ApiException.NETWORK,
                    "网络请求失败：" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : " " + e.getMessage()), e);
            noteFailure(wrapped);
            throw wrapped;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 解析统一响应体 {@code {code, message, data, traceId}}。
     *
     * <p>把 {@code traceId} 一并带进异常消息：用户截图报错时，
     * 运维能直接拿它去服务端日志里定位那一次请求，不必靠时间戳猜。
     */
    @Nullable
    private Object parseEnvelope(@NonNull String body) throws ApiException {
        if (body.trim().isEmpty()) {
            return null;
        }
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (JSONException e) {
            throw new ApiException(ApiException.PARSE, "响应不是合法 JSON：" + head(body), e);
        }

        int code = root.optInt("code", 0);
        if (code == 0) {
            return root.opt("data");
        }

        String message = root.optString("message", "请求失败");
        String traceId = root.optString("traceId", "");
        throw new ApiException(mapBizCode(code), message
                + (traceId.isEmpty() ? "" : "（traceId " + traceId + "）"), null, code);
    }

    /** 业务码 → 客户端异常码。只挑「客户端需要区别对待」的几个，其余统一归 BUSINESS。 */
    private static int mapBizCode(int bizCode) {
        switch (bizCode) {
            case 1002: // CodeUnauthorized
                return ApiException.UNAUTHORIZED;
            case 1005: // CodeTooManyRequests
                return ApiException.RATE_LIMITED;
            case 4001: // CodeTTSUnavailable
                return ApiException.TTS_UNAVAILABLE;
            default:
                return ApiException.BUSINESS;
        }
    }

    /** HTTP 层错误。bizCode 位置放 HTTP 状态码，让 {@link ApiException#tripsBreaker()} 能判断 5xx。 */
    @NonNull
    private static ApiException httpError(int status, @NonNull String body) {
        if (status == 401) {
            return new ApiException(ApiException.UNAUTHORIZED, "登录已过期，请重新登录", null, status);
        }
        if (status == 429) {
            return new ApiException(ApiException.RATE_LIMITED, "请求过于频繁，请稍后再试", null, status);
        }
        // 服务端正常返回了 JSON 错误体的话，里面的 message 比 HTTP 状态码有用得多
        String detail = body;
        try {
            JSONObject root = new JSONObject(body);
            String message = root.optString("message", "");
            if (!message.isEmpty()) {
                detail = message;
            }
        } catch (JSONException ignored) {
            // 不是 JSON（网关返回的 HTML 错误页），原样截断带上
        }
        return new ApiException(ApiException.HTTP,
                "服务端返回 " + status + "：" + head(detail), null, status);
    }

    // ------------------------------------------------------------ 熔断记账

    private static void noteSuccess() {
        failures.set(0);
        breakerOpenUntil = 0L;
    }

    private static void noteFailure(@NonNull ApiException e) {
        if (!e.tripsBreaker()) {
            return;
        }
        int count = failures.incrementAndGet();
        if (count >= ApiConfig.BREAKER_THRESHOLD) {
            breakerOpenUntil = SystemClock.elapsedRealtime() + ApiConfig.BREAKER_COOLDOWN_MS;
            failures.set(0);   // 冷却结束后从零开始重新计
            LogUtil.w("后端连续失败 " + count + " 次，熔断 " + (ApiConfig.BREAKER_COOLDOWN_MS / 1000) + " 秒");
        }
    }

    // ------------------------------------------------------------ 小工具

    /** 取当前 token；未登录或没有后端凭证时返回空串。 */
    @NonNull
    private String token() {
        try {
            UserStore store = UserStore.get(appContext);
            return store.isAuthTokenValid() ? store.getAuthToken() : "";
        } catch (Exception e) {
            return "";
        }
    }

    @NonNull
    private static String buildQuery(@Nullable Map<String, String> query) throws ApiException {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            String value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (!first) {
                sb.append('&');
            }
            first = false;
            try {
                sb.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(value, "UTF-8"));
            } catch (Exception e) {
                throw new ApiException(ApiException.PARSE, "查询参数编码失败：" + entry.getKey(), e);
            }
        }
        return sb.toString();
    }

    @NonNull
    private static String readBody(@Nullable InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[8192];
            int read;
            while ((read = stream.read(buf)) > 0) {
                out.write(buf, 0, read);
                if (out.size() > MAX_BODY_BYTES) {
                    throw new ApiException(ApiException.PARSE, "响应体过大，超过 "
                            + (MAX_BODY_BYTES / 1024 / 1024) + " MB");
                }
            }
            return out.toString("UTF-8");
        }
    }

    @NonNull
    private static String head(@Nullable String text) {
        String value = text == null ? "" : text.trim();
        return value.length() <= ERROR_BODY_LIMIT ? value : value.substring(0, ERROR_BODY_LIMIT) + "…";
    }

    // ------------------------------------------------------------ JSON 小工具

    /** 把 {@code data} 当作对象取；不是对象时返回空对象（调用方不必到处判空）。 */
    @NonNull
    public static JSONObject asObject(@Nullable Object data) {
        return data instanceof JSONObject ? (JSONObject) data : new JSONObject();
    }

    /** 把 {@code data} 当作数组取；不是数组时返回空数组。 */
    @NonNull
    public static JSONArray asArray(@Nullable Object data) {
        return data instanceof JSONArray ? (JSONArray) data : new JSONArray();
    }

    /** 便捷构造查询参数 */
    @NonNull
    public static Map<String, String> query(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }
}

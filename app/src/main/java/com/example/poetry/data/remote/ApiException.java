package com.example.poetry.data.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 后台接口异常。
 */
public class ApiException extends Exception {

    /** 后台尚未接入（{@code ApiConfig.ENABLED} 为 false，或没填地址） */
    public static final int NOT_IMPLEMENTED = 1001;
    /** 网络不可用：连不上、超时、DNS 失败 */
    public static final int NETWORK = 1002;
    /** 服务端返回非 2xx */
    public static final int HTTP = 1003;
    /** 数据解析失败 */
    public static final int PARSE = 1004;
    /** 401：未登录 / token 无效或过期 */
    public static final int UNAUTHORIZED = 1005;
    /** 429：触发限流 */
    public static final int RATE_LIMITED = 1006;
    /** 服务端明确返回的业务错误（响应体里的 code 非 0） */
    public static final int BUSINESS = 1007;
    /** 服务端未配置云端语音合成，客户端应回退本地引擎 */
    public static final int TTS_UNAVAILABLE = 1008;
    /** 熔断中：短时间内连续失败，正在冷却，本次没发出真实请求 */
    public static final int BREAKER_OPEN = 1009;

    // ---- 下载（诗库安装）专用 ----

    /** 用户或代码主动取消 */
    public static final int CANCELLED = 2001;
    /** 安装目录空间不足 */
    public static final int NO_SPACE = 2002;
    /** 字节数与 version.json 里的 sha256 对不上 */
    public static final int CHECKSUM = 2003;
    /** 传到一半连接就断了（长度不足） */
    public static final int SHORT_READ = 2004;
    /** 库结构不认：缺表、schema 主版本不符 */
    public static final int SCHEMA = 2005;

    private final int code;

    /**
     * 服务端响应体里的业务码（{@code {code, message, data, traceId}} 里的 code）。
     * 0 = 没有（本地产生的错误，或者响应压根没解析出来）。排查问题时很有用：
     * {@link #code} 说的是「哪一类失败」，它说的是「服务端具体怎么说的」。
     */
    private final int bizCode;

    public ApiException(int code, @NonNull String message) {
        this(code, message, null);
    }

    public ApiException(int code, @NonNull String message, @Nullable Throwable cause) {
        this(code, message, cause, 0);
    }

    public ApiException(int code, @NonNull String message, @Nullable Throwable cause, int bizCode) {
        super(message, cause);
        this.code = code;
        this.bizCode = bizCode;
    }

    public int getCode() {
        return code;
    }

    public int getBizCode() {
        return bizCode;
    }

    /**
     * 这类失败值不值得计入熔断。
     *
     * <p>只有「服务端本身有问题」才算：连不上、5xx、返回体解析不了。
     * 4xx 是服务端**正常工作**的结果（参数错了、没登录），
     * 把它们也计入熔断，会让一次错误输入把整个后端拉黑 30 秒。
     */
    public boolean tripsBreaker() {
        return code == NETWORK || code == PARSE
                || (code == HTTP && bizCode >= 500);
    }

    /** 是否属于「这次失败但换个时间可以重试」 */
    public boolean isRetryable() {
        return code == NETWORK || code == RATE_LIMITED
                || (code == HTTP && bizCode >= 500);
    }

    @NonNull
    @Override
    public String toString() {
        return "ApiException{code=" + code + ", bizCode=" + bizCode + ", msg=" + getMessage() + "}";
    }
}

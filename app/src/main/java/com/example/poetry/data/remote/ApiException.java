package com.example.poetry.data.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 后台接口异常。
 */
public class ApiException extends Exception {

    /** 后台尚未接入 */
    public static final int NOT_IMPLEMENTED = 1001;
    /** 网络不可用 */
    public static final int NETWORK = 1002;
    /** 服务端返回非 2xx */
    public static final int HTTP = 1003;
    /** 数据解析失败 */
    public static final int PARSE = 1004;

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

    public ApiException(int code, @NonNull String message) {
        this(code, message, null);
    }

    /**
     * @param cause 原始异常，可为 null。留着它，Logcat 里才看得到是 DNS、超时还是 TLS 出错。
     */
    public ApiException(int code, @NonNull String message, @Nullable Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}

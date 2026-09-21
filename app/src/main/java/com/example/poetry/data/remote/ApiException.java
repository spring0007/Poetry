package com.example.poetry.data.remote;

import androidx.annotation.NonNull;

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

    private final int code;

    public ApiException(int code, @NonNull String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}

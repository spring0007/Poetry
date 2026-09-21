package com.example.poetry.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 统一数据回调（始终在主线程回调）。
 *
 * @param <T> 数据类型
 */
public interface Callback<T> {

    /** 成功 */
    void onData(@NonNull T data);

    /** 失败：可能是本地库不可用、后台接口未接入等 */
    void onError(@Nullable Throwable error);
}

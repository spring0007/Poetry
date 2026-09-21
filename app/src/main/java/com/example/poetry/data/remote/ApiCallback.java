package com.example.poetry.data.remote;

import androidx.annotation.NonNull;

/**
 * 后台接口回调（主线程回调）。
 *
 * @param <T> 返回数据类型
 */
public interface ApiCallback<T> {

    void onSuccess(@NonNull T data);

    void onFailure(@NonNull ApiException error);
}

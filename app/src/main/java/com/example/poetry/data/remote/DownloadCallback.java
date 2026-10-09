package com.example.poetry.data.remote;

import androidx.annotation.NonNull;

import java.io.File;

/**
 * 诗库下载的三个回调，**都在主线程**。
 *
 * <p>为什么另起一个接口而不复用 {@link com.example.poetry.data.Callback}：后者是
 * 「一次调用一个结果」的泛型回调（{@code onData} / {@code onError}），而下载是
 * 一条**过程**：进度要报很多次，还要能被调用方主动取消。硬塞进 {@code Callback}
 * 只会让每个实现都长出一堆用不上的方法。下载是另一类操作，进度、取消、校验都只有它有。
 */
public interface DownloadCallback {

    /**
     * @param total 预期总字节数；未知时为 -1，界面此时该走不确定进度。
     *              这个值主要来自 {@code version.json} 的 {@code bytes}——那正是 manifest
     *              存在的理由之一：Content-Length 在分块传输或代理下可能根本没有。
     */
    void onProgress(long done, long total);

    /**
     * 字节流已经完整落在 {@code part} 上，且 sha256 与 manifest 相符——但**还没有安装**。
     * 安装（rename + 重开库）是调用方的事，因为它要在 IO 线程上、与查询串行地做。
     */
    void onFinished(@NonNull File part, @NonNull String sha256);

    void onFailed(@NonNull ApiException error);
}

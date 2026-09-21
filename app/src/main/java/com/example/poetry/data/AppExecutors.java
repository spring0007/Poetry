package com.example.poetry.data;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 线程调度：数据库与网络一律走 IO 单线程池，结果回到主线程。
 */
public final class AppExecutors {

    private static final AppExecutors INSTANCE = new AppExecutors();

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private AppExecutors() {
    }

    public static AppExecutors get() {
        return INSTANCE;
    }

    public void io(@NonNull Runnable task) {
        io.execute(task);
    }

    public void main(@NonNull Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            main.post(task);
        }
    }
}

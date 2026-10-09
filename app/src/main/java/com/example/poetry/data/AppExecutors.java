package com.example.poetry.data;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 线程调度：数据库/本地计算走 {@link #io()}，阻塞 HTTP 走 {@link #net()}，
 * 结果一律回到主线程。
 *
 * <p>两个池分开的原因：后端不可达时一次读超时要等满 {@code ApiConfig.READ_TIMEOUT_MS}（8 秒），
 * 合用一个池的话，这 8 秒会把后面排队的**本地库查询**一起堵死 —— 而本地查询本来
 * 一毫秒就出结果。拆开之后网络怎么慢都不影响本地首屏。
 *
 * <p>两者都是有界小池而不是 {@code newCachedThreadPool}：App 的并发查询是有限个页面，
 * 不需要无限线程，池子小一点也更容易在日志里看清谁在跑。
 */
public final class AppExecutors {

    private static final AppExecutors INSTANCE = new AppExecutors();

    /** 数据库 / 本地计算。SQLite 只读，多线程读是安全的。 */
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    /** 阻塞 HTTP。 */
    private final ExecutorService net = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    private AppExecutors() {
    }

    public static AppExecutors get() {
        return INSTANCE;
    }

    public void io(@NonNull Runnable task) {
        io.execute(task);
    }

    public void net(@NonNull Runnable task) {
        net.execute(task);
    }

    /**
     * 「这一趟大概会碰网络」的任务：猜得准就按网络池排，猜不准也退化成 {@link #io}，
     * 不会丢任务。
     *
     * <p>给的是**推测**而不是事实：任务内部还会自己再判一次（网络可能刚刚断掉），
     * 所以猜错的代价只是白占一个线程，不影响正确性。
     */
    public void call(boolean maybeNetwork, @NonNull Runnable task) {
        if (maybeNetwork) {
            net.execute(task);
        } else {
            io.execute(task);
        }
    }

    public void main(@NonNull Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            main.post(task);
        }
    }
}

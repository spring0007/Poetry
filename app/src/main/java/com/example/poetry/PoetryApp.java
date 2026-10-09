package com.example.poetry;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import com.example.poetry.auth.AuthGuard;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.DbInstaller;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.media.Speaker;
import com.example.poetry.ui.Night;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用入口：初始化数据仓库、朗读引擎，并在启动时恢复夜间模式偏好。
 *
 * <p>磁盘上的重活一律不许放主线程：诗库 poetry.db 约 56 MB，首次启动要先从 assets
 * 拷到内部存储再建立连接。放主线程会让冷启动白屏好几秒，用户往往这时直接划掉 App，
 * 于是系统杀掉进程，日志里只剩 WindowManager 的 {@code DeadObjectException}——
 * 那是进程已死的结果，不是原因。
 */
public class PoetryApp extends Application {

    /**
     * Dedicated warm-up thread. Deliberately not {@code AppExecutors}' IO pool: that one
     * is single threaded and serves queries, so a 56 MB copy there would starve every
     * query until it finishes.
     */
    private final ExecutorService warmUp = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        UserStore store = UserStore.get(this);

        // 鉴权闸门：需登录的页面在创建时统一检查，被保护页面自身零改动
        registerActivityLifecycleCallbacks(new AuthGuard(store));

        // Heavy (copy + SQLite connect) - off the main thread.
        //
        // warmUpLocal() 刻意是同步的：它会在**当前线程**上把库接通，所以这一句就等于
        // 把 56 MB 的拷贝放在 warmUp 这条专用线程上。不能用 AppExecutors.io()——
        // 那是单线程池，同一份池还要服务每一次查询，放进去会把冷启动那几秒里
        // 所有远端查询都堵在拷贝后面（详见 PoetryRepository#warmUpLocal）。
        warmUp.execute(() -> {
            PoetryRepository.get(PoetryApp.this).warmUpLocal();
            // 库接通之后才谈得上「要不要更新」。放在这里而不是 onCreate 的末尾：
            // 判断本地版本要读 meta，而 meta 只有库打开之后才读得到。
            // 测试期策略是 MANUAL_ONLY，所以这一句目前什么都不做。
            DbInstaller.get(PoetryApp.this).onAppStart();
        });

        // 朗读引擎在这里预热：加载模型是异步的，越早开始，用户点开详情页时越可能已经就绪。
        Speaker.get().prepare(this, null);
        Speaker.get().apply(store.getTtsConfig());

        // 拉一次账号资料，把会员权益刷新到最新（云端音色的解锁态由它决定）。
        // 不阻塞启动，也不关心结果：它自己在网络池上跑，失败只是这次按未开通处理。
        // 未登录的用户会在发请求之前就返回（见 PoetryRepository#fetchProfile），
        // 所以匿名冷启动不会多一个请求。
        PoetryRepository.get(this).refreshProfile(null);

        Night.apply(store);
    }

    @NonNull
    @Override
    public String toString() {
        return "PoetryApp";
    }
}

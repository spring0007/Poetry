package com.example.poetry;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.DbInstaller;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.media.Speaker;

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

        // Heavy (copy + SQLite connect) - off the main thread.
        warmUp.execute(() -> {
            PoetryRepository.get(PoetryApp.this);
            // 库接通之后才谈得上「要不要更新」。放在这里而不是 onCreate 的末尾：
            // 判断本地版本要读 meta，而 meta 只有库打开之后才读得到。
            // 测试期策略是 MANUAL_ONLY，所以这一句目前什么都不做。
            DbInstaller.get(PoetryApp.this).onAppStart();
        });

        // 朗读引擎在这里预热：加载模型是异步的，越早开始，用户点开详情页时越可能已经就绪。
        Speaker.get().prepare(this, null);
        Speaker.get().apply(store.getTtsConfig());

        switch (store.getNightMode()) {
            case UserStore.NIGHT_ON:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case UserStore.NIGHT_OFF:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "PoetryApp";
    }
}

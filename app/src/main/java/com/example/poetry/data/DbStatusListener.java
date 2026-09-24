package com.example.poetry.data;

import androidx.annotation.NonNull;

/**
 * 诗库状态变化的回调，**总在主线程**。
 *
 * <p>为什么不用 LiveData / RxJava：这个项目的依赖表是刻意精简的（见 {@code app/build.gradle.kts}），
 * 而且已有的 {@link Callback} + {@code AppExecutors.main} 形状完全够用，加一个响应式库只为
 * 传一个枚举不划算。也没用 {@code LocalBroadcastManager}——那个已经废弃，而且把类型安全的
 * 回调降级成字符串协议。
 *
 * <h3>注册与渲染必须分开</h3>
 * 注册只能放在「只跑一次」的生命周期点（{@code onViewCreated} / {@code onCreate}），
 * <b>绝不能放进 {@code setupX()} 这类会被 {@code onResume} 重复调用的方法里</b>：那样每转一次屏
 * 就会多注册一个监听器，而 {@code onDestroyView} 只摘得掉一个。
 *
 * <p>注册时会被立刻回调一次当前状态，所以界面不需要在 {@code onViewCreated} 里另外查一遍。
 */
public interface DbStatusListener {

    void onDbStatus(@NonNull DbStatus status);

    /**
     * 只关心「本地库能不能用了」这一个布尔量的界面（列表页、分类页、发现页）用它，
     * 免得每次下载进度（每秒好几次）都去重建一遍 RecyclerView。
     *
     * <p><b>首次回调一定算作一次跃迁</b>，这样界面不必再写一遍初始加载：注册 →
     * 立刻拿到当前状态 → 触发第一次查询，一步到位。这也是 {@code last} 用
     * {@link Boolean} 而不是 {@code boolean} 的唯一原因（{@code null} = 还没渲染过）。
     */
    abstract class ReadyWatcher implements DbStatusListener {

        private Boolean last;

        @Override
        public final void onDbStatus(@NonNull DbStatus status) {
            boolean now = status.localReady;
            if (last != null && last == now) {
                return;
            }
            last = now;
            onReadyChanged(now, status);
        }

        /** 只在 {@code localReady} 真的翻面时调用；第一次一定会调用。 */
        protected abstract void onReadyChanged(boolean ready, @NonNull DbStatus status);
    }
}

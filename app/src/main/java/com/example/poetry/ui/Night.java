package com.example.poetry.ui;

import android.app.Activity;
import android.content.res.Configuration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import com.example.poetry.data.local.UserStore;

/**
 * 夜间模式切换。
 *
 * <p>颜色走的是主题属性（{@code ?attr/colorSurface} 之类），只有页面重建才会重新解析，
 * 所以切换后必须让当前这一页重建。{@code AppCompatDelegate.setDefaultNightMode()} 在
 * AppCompat 1.1 之后确实会自动重建已注册的页面，但它只管「已经挂上 delegate 的 Activity」，
 * 而且这一次重建是异步排进主线程的；一旦当前页还没完成 attach，或者 AppCompat 判断
 * 配置没变化，就会静默地什么都不做——表现就是「开关拨了，界面不变」。
 *
 * <p>这里统一收口：先写偏好、再设默认模式，最后比对**当前页面真正套用的模式**，
 * 不一样就亲手 {@code recreate()} 一次。比对而不是无条件重建，是为了躲开 AppCompat
 * 已经重建过的情况下的二次重建（那会让页面闪一下）。
 */
public final class Night {

    private Night() {
    }

    /** 存储里的三态 → AppCompat 的模式常量 */
    public static int delegateMode(int storeMode) {
        if (storeMode == UserStore.NIGHT_ON) {
            return AppCompatDelegate.MODE_NIGHT_YES;
        }
        if (storeMode == UserStore.NIGHT_OFF) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        }
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    /** AppCompat 模式常量 → 存储里的三态 */
    public static int storeMode(int delegateMode) {
        if (delegateMode == AppCompatDelegate.MODE_NIGHT_YES) {
            return UserStore.NIGHT_ON;
        }
        if (delegateMode == AppCompatDelegate.MODE_NIGHT_NO) {
            return UserStore.NIGHT_OFF;
        }
        return UserStore.NIGHT_FOLLOW_SYSTEM;
    }

    /** 冷启动时恢复上次的选择；只设默认值，不碰任何页面 */
    public static void apply(@NonNull UserStore store) {
        AppCompatDelegate.setDefaultNightMode(delegateMode(store.getNightMode()));
    }

    /** 当前页面实际套用的是不是夜间（读的是配置，不是偏好） */
    public static boolean isNight(@NonNull Activity activity) {
        int mask = activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 打开 / 关闭夜间模式并让当前页面立刻生效。
     *
     * @return true 表示已触发重建；false 表示页面本来就是这个模式，无需重建
     */
    public static boolean setEnabled(@Nullable Activity activity, @NonNull UserStore store,
                                     boolean on) {
        store.setNightMode(on ? UserStore.NIGHT_ON : UserStore.NIGHT_OFF);
        AppCompatDelegate.setDefaultNightMode(on
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return false;
        }
        if (isNight(activity) == on) {
            // AppCompat 已经把配置套上去了（它内部会自己重建一次），别再重建一遍
            return false;
        }
        activity.recreate();
        return true;
    }
}

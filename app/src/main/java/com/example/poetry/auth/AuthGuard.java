package com.example.poetry.auth;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.HistoryActivity;
import com.example.poetry.LoginActivity;
import com.example.poetry.ProfileActivity;
import com.example.poetry.data.local.UserStore;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 鉴权闸门：需登录的页面在创建时统一检查，未登录直接改道登录页。
 *
 * <p>挂在 {@code Application} 的生命周期回调上，<b>被保护页面一行代码都不用改</b>。
 * 想增删要登录的页面，改下面的 {@link #PROTECTED} 就行。
 *
 * <p>登录完成后由 {@link LoginActivity} 带着「原始 Intent」把用户送回原页面，
 * 页面参数（ extras ）原样保留。
 */
public final class AuthGuard implements Application.ActivityLifecycleCallbacks {

    /**
     * 需要登录才能进入的页面。目前是「个人信息」与「朗读历史」——两者都是
     * 用户个人数据，也是将来云同步的最小集。浏览、搜索、朗读等核心体验
     * 故意不设门槛，登录不该挡住一个刚打开 App 的人。
     */
    private static final Set<Class<?>> PROTECTED = Collections.unmodifiableSet(
            new HashSet<>(java.util.Arrays.asList(
                    ProfileActivity.class,
                    HistoryActivity.class)));

    /** 传给登录页的原始 Intent 键 */
    public static final String EXTRA_PENDING = "pending_intent";

    private final UserStore store;

    public AuthGuard(@NonNull UserStore store) {
        this.store = store;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        if (!PROTECTED.contains(activity.getClass()) || store.isLoggedIn()) {
            return;
        }
        Intent login = new Intent(activity, LoginActivity.class);
        // 把原页面（含 extras）捎给登录页，登录成功后原路送回
        Intent original = activity.getIntent();
        if (original != null) {
            login.putExtra(EXTRA_PENDING, original);
        }
        activity.startActivity(login);
        activity.finish();
    }

    // 以下回调本闸门不关心，保持空实现
    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }
}

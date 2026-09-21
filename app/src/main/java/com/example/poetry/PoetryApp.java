package com.example.poetry;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.media.Speaker;

/**
 * 应用入口：初始化数据仓库、朗读引擎，并在启动时恢复夜间模式偏好。
 */
public class PoetryApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        UserStore store = UserStore.get(this);
        // 预热仓库（内部会尝试打开 poetry.db）
        PoetryRepository.get(this);
        // 预热朗读引擎：异步释放 espeak-ng-data 并初始化原生库，同时恢复上次的语音配置
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

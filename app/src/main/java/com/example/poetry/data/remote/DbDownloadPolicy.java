package com.example.poetry.data.remote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;

/**
 * 诗库下载的触发策略与地址常量。三个行为各是一个常量，调策略只改这里。
 *
 * <p>地址来自 {@link ApiConfig#BASE_URL}，也就是 {@code local.properties} 里的
 * {@code API_BASE_URL}。清单文件挂后端的 {@code /static} 下，
 * 所以是 {@code <根地址>/static/version.json} —— 少写那个 {@code static/} 前缀会拿到 404，
 * 而这在界面上只表现为「检查更新失败」，很难一眼看出是路径错了。
 */
public final class DbDownloadPolicy {

    public enum Trigger {
        /** 不管什么网络，启动就自动下（流量用户会不高兴） */
        AUTO_ANY,
        /** 自动检查；在移动网络上先问一句 */
        AUTO_WIFI_PROMPT_CELLULAR,
        /** 只在用户手动点「检查更新 / 开始下载」时才动。测试期用这个。 */
        MANUAL_ONLY
    }

    /**
     * 当前策略。测试期固定 {@code MANUAL_ONLY}：正在验证链路，手动触发能让每一种失败模式
     * 随时复现，不用等启动时机或改系统网络状态。链路验证通过后再改成
     * {@code AUTO_WIFI_PROMPT_CELLULAR}。
     */
    public static final Trigger TRIGGER = Trigger.MANUAL_ONLY;

    /** 服务端 version.json 地址。**全工程只此一处要填。** */
    public static final String MANIFEST_URL = ApiConfig.BASE_URL + ApiConfig.API_MANIFEST;

    /** 自动模式下两次检查的最小间隔（节流）。纯离线冷启动一次检查要耗掉整个连接超时。 */
    public static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    public static final int CONNECT_TIMEOUT_MS = 15_000;

    /**
     * 读超时。**绝不能是 0**——{@code HttpURLConnection} 的默认值就是 0，意思是永不超时，
     * 服务器接上连接却不发数据时，界面会永远钉在「下载中 40%」而且没有重试入口。
     */
    public static final int READ_TIMEOUT_MS = 30_000;

    /** version.json 很小，单独给一个更短的读超时 */
    public static final int MANIFEST_READ_TIMEOUT_MS = 10_000;

    private DbDownloadPolicy() {
    }

    /**
     * 当前是不是 Wi-Fi（或以太网之类的不计流量网络）。
     *
     * <p>需要 {@code ACCESS_NETWORK_STATE} 权限，缺了会抛 {@code SecurityException}。
     * 所以这里包了 try/catch 并且**默认返回 true**：判定不了就当作可以下，让用户自己去点，
     * 总好过因为一个权限声明漏了就静默地永远不下载（那种 bug 极难发现）。
     */
    public static boolean isUnmetered(@NonNull Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            Network network = cm.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (Exception e) {
            return true;
        }
    }

    /** 有没有网。断网时不该去拉 version.json，否则白白等 15 秒超时。 */
    public static boolean isOnline(@NonNull Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            Network network = cm.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }
}

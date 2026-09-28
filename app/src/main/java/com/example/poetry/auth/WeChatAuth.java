package com.example.poetry.auth;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 微信 OAuth 桥接（<b>可选依赖</b>）。
 *
 * <p>编译期完全不依赖微信 SDK：对 {@code com.tencent.mm.opensdk} 的所有调用都走反射，
 * 所以现在就能编译打包。将来接入真实微信登录只需两步，业务代码一行不用改：
 * <ol>
 *   <li>{@code app/build.gradle.kts} 加
 *       {@code implementation("com.tencent.mm.opensdk:wechat-sdk-android:6.8.30")}；</li>
 *   <li>{@code AndroidManifest.xml} 里给 {@code com.example.poetry.WX_APP_ID} 填上真实 AppID。</li>
 * </ol>
 *
 * <p>两步都齐了，{@link #isConfigured} 返回 true，登录页走真实授权；
 * 缺任何一个（没装 SDK / 没填 AppID / 手机没装微信），登录页自动落到「模拟登录」，
 * 这条降级链路不依赖任何服务器。
 */
public final class WeChatAuth {

    /** 微信授权回调监听 */
    public interface Callback {
        /**
         * @param ok      是否拿到有效授权码
         * @param code    授权码（真实登录后应由后端用 AppSecret 换 openid / token）
         * @param message 失败原因，成功时为 null
         */
        void onResult(boolean ok, @Nullable String code, @Nullable String message);
    }

    /** AppID 放在 Manifest meta-data 里，改配置不用动代码 */
    public static final String META_APP_ID = "com.example.poetry.WX_APP_ID";

    /** 一次性防 CSRF 串：发起授权时生成，回调时比对 */
    private static volatile String pendingState = "";

    private WeChatAuth() {
    }

    /** 运行环境里有没有微信 SDK（gradle 依赖加了才有） */
    public static boolean isSdkAvailable() {
        try {
            Class.forName("com.tencent.mm.opensdk.openapi.WXAPIFactory");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Manifest 里配置的 AppID，没配返回空串 */
    @NonNull
    public static String appId(@NonNull Context context) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle meta = info.metaData;
            if (meta == null) {
                return "";
            }
            return meta.getString(META_APP_ID, "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 真实微信登录的前置条件：SDK 在 + AppID 配了 */
    public static boolean isConfigured(@NonNull Context context) {
        return isSdkAvailable() && !appId(context).isEmpty();
    }

    /**
     * 拉起微信授权页。
     *
     * @return false 表示拉不起来（未配置 / 未装微信 / 反射异常），调用方应改走模拟登录
     */
    public static boolean sendAuth(@NonNull Activity activity) {
        if (!isConfigured(activity)) {
            return false;
        }
        try {
            ClassLoader cl = activity.getClassLoader();
            Class<?> apiIface = Class.forName(
                    "com.tencent.mm.opensdk.openapi.IWXAPI", true, cl);
            Object api = createApi(activity, cl);
            Method register = apiIface.getMethod("registerApp", String.class);
            register.invoke(api, appId(activity));

            Class<?> reqClass = Class.forName(
                    "com.tencent.mm.opensdk.modelmsg.SendAuth$Req", true, cl);
            Object req = reqClass.getDeclaredConstructor().newInstance();
            reqClass.getField("scope").set(req, "snsapi_userinfo");
            pendingState = "poetry-" + Long.toHexString(System.currentTimeMillis());
            reqClass.getField("state").set(req, pendingState);

            Class<?> baseReq = Class.forName(
                    "com.tencent.mm.opensdk.modelbase.BaseReq", true, cl);
            apiIface.getMethod("sendReq", baseReq).invoke(api, req);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 在 {@code wxapi/WXEntryActivity} 里调用：把微信回调 Intent 交给 SDK 解析，
     * 结果回给 {@code callback}。没有 SDK 时直接报「未配置」，不会崩。
     */
    public static void handleIntent(@NonNull Activity activity, @Nullable Intent intent,
                                    @NonNull Callback callback) {
        if (!isConfigured(activity) || intent == null) {
            callback.onResult(false, null, "wechat not configured");
            return;
        }
        try {
            ClassLoader cl = activity.getClassLoader();
            Class<?> apiIface = Class.forName(
                    "com.tencent.mm.opensdk.openapi.IWXAPI", true, cl);
            Class<?> handlerIface = Class.forName(
                    "com.tencent.mm.opensdk.openapi.IWXAPIEventHandler", true, cl);
            Object api = createApi(activity, cl);

            // SDK 不在编译期，用动态代理实现 IWXAPIEventHandler
            Object handler = Proxy.newProxyInstance(cl, new Class<?>[]{handlerIface},
                    (proxy, method, args) -> {
                        if (args != null && args.length == 1 && "onResp".equals(method.getName())
                                && args[0] != null) {
                            resolveResp(activity, args[0], callback);
                        }
                        return null;
                    });
            apiIface.getMethod("handleIntent", Intent.class, handlerIface)
                    .invoke(api, intent, handler);
        } catch (Throwable t) {
            callback.onResult(false, null, t.getClass().getSimpleName());
        }
    }

    private static void resolveResp(@NonNull Activity activity, @NonNull Object resp,
                                    @NonNull Callback callback) {
        try {
            String cls = resp.getClass().getName();
            if (!cls.endsWith("SendAuth$Resp")) {
                callback.onResult(false, null, "unexpected resp: " + cls);
                return;
            }
            int errCode = resp.getClass().getField("errCode").getInt(resp);
            String code = (String) resp.getClass().getField("code").get(resp);
            String state = (String) resp.getClass().getField("state").get(resp);
            if (errCode == 0 && code != null && !code.isEmpty()
                    && pendingState.equals(state)) {
                callback.onResult(true, code, null);
            } else {
                callback.onResult(false, null, "err=" + errCode);
            }
        } catch (Throwable t) {
            callback.onResult(false, null, t.getClass().getSimpleName());
        } finally {
            activity.finish();
        }
    }

    private static Object createApi(@NonNull Activity activity, @NonNull ClassLoader cl)
            throws Exception {
        Class<?> factory = Class.forName(
                "com.tencent.mm.opensdk.openapi.WXAPIFactory", true, cl);
        return factory
                .getMethod("createWXAPI", Context.class, String.class, boolean.class)
                .invoke(null, activity.getApplicationContext(), appId(activity), Boolean.TRUE);
    }
}

package com.example.poetry.data.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * 一条账号绑定（{@code GET /v1/user/auth}）。
 *
 * <p>手机号登录时本机只存了「登录方式 = phone」这件事——{@code UserStore} 的 session 分区
 * 里没有手机号字段（见 {@code UserStore.setLogin} 的签名），也**不该有**：
 * 号码是账号的标识，多一份副本就多一处泄漏面，而且用户换号之后两份还会对不上。
 * 所以「账号」卡片要显示脱敏手机号时，只能问服务端。
 *
 * <p>同一个用户可能绑多条（手机号 + 微信 + QQ），{@code authType} 区分，{@code appId}
 * 是第三方平台的 AppID（手机号那条为空）。{@code identifier} 在手机号这条就是号码原文，
 * 界面上必须走 {@link #maskedIdentifier()}。
 *
 * @see UserProfile
 */
public class UserAuth {

    /** {@code authType} 的取值，与后端 {@code user_auth.auth_type} 一致。 */
    public static final String TYPE_PHONE = "phone";
    public static final String TYPE_WECHAT = "wechat";
    public static final String TYPE_QQ = "qq";

    private static final String KEY_ID = "id";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_AUTH_TYPE = "authType";
    private static final String KEY_APP_ID = "appId";
    private static final String KEY_IDENTIFIER = "identifier";
    private static final String KEY_IS_VERIFIED = "isVerified";

    private long id;
    private long userId;
    private String authType = "";
    /** 第三方平台的 AppID；手机号这条为空。 */
    private String appId = "";
    /** 标识原文：手机号这条就是号码。**不要直接显示**，用 {@link #maskedIdentifier()}。 */
    private String identifier = "";
    private int isVerified;

    public long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    @NonNull
    public String getAuthType() {
        return authType;
    }

    @NonNull
    public String getAppId() {
        return appId;
    }

    @NonNull
    public String getIdentifier() {
        return identifier;
    }

    public int getIsVerified() {
        return isVerified;
    }

    /** 是否手机号绑定。界面只对手机号做「绑定手机」这一行的展示。 */
    public boolean isPhone() {
        return TYPE_PHONE.equals(authType);
    }

    /**
     * 脱敏后的标识，供界面显示。
     *
     * <p>手机号按「前 3 + 后 4」遮（{@code 138****8000}），其它类型（微信 openId、
     * QQ openId 这类长串）按「前 4 + 后 4」遮。太短的一律整体遮掉——
     * 长度不够时露头露尾等于没遮。
     */
    @NonNull
    public String maskedIdentifier() {
        String raw = identifier.trim();
        if (raw.isEmpty()) {
            return "";
        }
        int head = isPhone() ? 3 : 4;
        int tail = isPhone() ? 4 : 4;
        if (raw.length() <= 4 || raw.length() <= head + tail) {
            return head >= raw.length() ? "****" : raw.substring(0, head) + "****";
        }
        return raw.substring(0, head) + "****" + raw.substring(raw.length() - tail);
    }

    @NonNull
    public static UserAuth fromJson(@NonNull JSONObject json) {
        UserAuth auth = new UserAuth();
        auth.id = json.optLong(KEY_ID, 0L);
        auth.userId = json.optLong(KEY_USER_ID, 0L);
        auth.authType = json.optString(KEY_AUTH_TYPE, "");
        auth.appId = json.optString(KEY_APP_ID, "");
        auth.identifier = json.optString(KEY_IDENTIFIER, "");
        auth.isVerified = json.optInt(KEY_IS_VERIFIED, 0);
        return auth;
    }

    /** 从服务端返回的数组里挑出手机号那条；没有就返回 {@code null}。 */
    @Nullable
    public static UserAuth phoneOf(@NonNull Iterable<UserAuth> auths) {
        for (UserAuth auth : auths) {
            if (auth.isPhone()) {
                return auth;
            }
        }
        return null;
    }
}

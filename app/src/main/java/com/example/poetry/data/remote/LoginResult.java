package com.example.poetry.data.remote;

import androidx.annotation.NonNull;

/**
 * 后端登录 / 注册的返回值（{@code POST /v1/auth/login}）。
 *
 * <p>后端返回体里还有 {@code auth}（绑定关系）等字段，客户端用不上就不映射了 ——
 * 映射层只搬界面真正会读的字段，多搬一个就多一处将来要跟着后端改的地方。
 */
public final class LoginResult {

    private final String token;
    private final long userId;
    private final String nickname;
    private final boolean created;
    private final long expireAtMillis;

    public LoginResult(@NonNull String token, long userId, @NonNull String nickname,
                       boolean created, long expireAtMillis) {
        this.token = token;
        this.userId = userId;
        this.nickname = nickname;
        this.created = created;
        this.expireAtMillis = expireAtMillis;
    }

    @NonNull
    public String getToken() {
        return token;
    }

    public long getUserId() {
        return userId;
    }

    @NonNull
    public String getNickname() {
        return nickname;
    }

    /** true = 这次请求顺手把账号建出来了（手机号此前没注册过） */
    public boolean isCreated() {
        return created;
    }

    /** 票据到期时间（毫秒时间戳，0 = 解析不出来） */
    public long getExpireAtMillis() {
        return expireAtMillis;
    }
}

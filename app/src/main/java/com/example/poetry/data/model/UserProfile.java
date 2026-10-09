package com.example.poetry.data.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 服务端账号资料快照（{@code GET /v1/user/profile}）。
 * <p>
 * 字段名与后端 {@code model.User} 的 JSON 保持一致，本地缓存也照这个形状落盘
 * （见 {@code UserStore.setAccount()}），所以这里只有一个 {@link #fromJson}，
 * 服务端响应和本地缓存走同一条解析路径——少一套「缓存格式」，就少一处
 * 「服务端改了字段名、缓存还在按老名字读」的静默错位。
 * <p>
 * <b>会员权益的权威在服务端，这里只是缓存。</b>缓存的作用是让「我的」页在
 * 断网时也有东西可显示；能不能用云端音色最终由 {@code POST /v1/tts/synthesize}
 * 的 401/4003 说了算，客户端判定的结果随时可能被服务端否掉。
 *
 * @see Member
 */
public class UserProfile {

    /** {@code status} 的正常值；其它值表示账号被禁用。 */
    public static final int STATUS_NORMAL = 1;

    private static final String KEY_UID = "id";
    private static final String KEY_NICKNAME = "nickname";
    private static final String KEY_AVATAR = "avatar";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_VIP_EXPIRE_AT = "vipExpireAt";
    private static final String KEY_REGISTER_TIME = "registerTime";
    private static final String KEY_STATUS = "status";

    private long uid;
    private String nickname = "";
    /** 头像地址（URL），与 {@code UserStore} 里那个「一个汉字或 emoji」的头像不是一回事。 */
    private String avatar = "";
    private int level;
    /** 会员到期时间戳（毫秒）；0 = 长期有效。 */
    private long vipExpireAt;
    /** 注册时间戳（毫秒）；0 = 服务端没给（老账号）。 */
    private long registerTime;
    private int status;

    public long getUid() {
        return uid;
    }

    public void setUid(long uid) {
        this.uid = uid;
    }

    @NonNull
    public String getNickname() {
        return nickname;
    }

    public void setNickname(@Nullable String nickname) {
        this.nickname = nickname == null ? "" : nickname;
    }

    @NonNull
    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(@Nullable String avatar) {
        this.avatar = avatar == null ? "" : avatar;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public long getVipExpireAt() {
        return vipExpireAt;
    }

    public void setVipExpireAt(long vipExpireAt) {
        this.vipExpireAt = vipExpireAt > 0L ? vipExpireAt : 0L;
    }

    public long getRegisterTime() {
        return registerTime;
    }

    public void setRegisterTime(long registerTime) {
        this.registerTime = registerTime > 0L ? registerTime : 0L;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    /** 账号是否正常（未被禁用）。 */
    public boolean isNormal() {
        return status == STATUS_NORMAL;
    }

    /**
     * 映射成客户端缓存的会员状态。
     * <p>
     * 口径与后端 {@code vipDecision} 一致：账号正常 <b>且</b> 等级达到会员
     * <b>且</b> 没过期。被禁用的账号即使 {@code level} 还挂着，也按普通用户算——
     * 服务端在合成入口就是这么判的，客户端跟着来才不会出现「界面显示会员、
     * 点下去却 403」这种自相矛盾。
     */
    @NonNull
    public Member toMember() {
        if (status != STATUS_NORMAL || level < Member.LEVEL_VIP) {
            return Member.free();
        }
        return Member.vip(vipExpireAt, Member.SOURCE_SERVER);
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put(KEY_UID, uid);
            json.put(KEY_NICKNAME, nickname);
            json.put(KEY_AVATAR, avatar);
            json.put(KEY_LEVEL, level);
            json.put(KEY_VIP_EXPIRE_AT, vipExpireAt);
            json.put(KEY_REGISTER_TIME, registerTime);
            json.put(KEY_STATUS, status);
        } catch (JSONException ignored) {
            // JSONObject.put 基本类型不会抛异常
        }
        return json;
    }

    /**
     * 解析服务端资料。时间字段解析不出来时按「未知」（0）处理，
     * 而不是让整份资料作废——昵称、等级这些照旧能用。
     *
     * <p>时间走 {@link Rfc3339#parse}：服务端给的是 RFC3339 字符串，
     * 而 {@link #toJson()} 落盘写的是毫秒数，只认字符串的话读缓存永远是 0
     * （「在线正常、重启后变未知」那个 bug）。
     */
    @NonNull
    public static UserProfile fromJson(@NonNull JSONObject json) {
        UserProfile profile = new UserProfile();
        profile.uid = json.optLong(KEY_UID, 0L);
        profile.nickname = json.optString(KEY_NICKNAME, "");
        profile.avatar = json.optString(KEY_AVATAR, "");
        profile.level = json.optInt(KEY_LEVEL, 0);
        profile.vipExpireAt = Rfc3339.parse(json.opt(KEY_VIP_EXPIRE_AT));
        profile.registerTime = Rfc3339.parse(json.opt(KEY_REGISTER_TIME));
        profile.status = json.optInt(KEY_STATUS, 0);
        return profile;
    }
}

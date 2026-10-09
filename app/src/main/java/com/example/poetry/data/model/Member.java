package com.example.poetry.data.model;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 会员资格。
 * <p>
 * 会员解锁的是<b>云端音色</b>：那些音色由后端代理腾讯云合成，不在 App 里，
 * 每次朗读都产生真实费用，所以只有开通的用户能选。
 * <p>
 * <b>判定基准在服务端</b>（{@code user.level} + {@code vip_expire_at}，
 * 见 {@code GET /v1/user/profile}），这个类只是本地缓存：开机和登录后各刷新一次
 * （{@code PoetryRepository.refreshProfile()}），刷新不到就按未开通处理。
 * 换句话说，这里的值判宽了也没用——合成接口在服务端照样会拒。
 * <p>
 * 内置离线音色全部免费，会员与否不影响它们——会员到期后最多是音色回到离线默认，
 * 不会出现「点了朗读没有声音」这种结果。
 * <p>
 * 存进 {@code UserStore} 的 {@code member} 分区，和朗读偏好同一份 JSON 快照。
 *
 * @see com.example.poetry.data.local.UserStore#getMember()
 */
public class Member {

    /** 未开通 */
    public static final int LEVEL_FREE = 0;
    /** 已开通：可以使用云端（服务端代理的腾讯云）发音人 */
    public static final int LEVEL_VIP = 1;

    /**
     * 开通来源：服务端资料（{@code GET /v1/user/profile}）。
     * <p>
     * 曾经还有一个「本地放行开关」（{@code GRANT_BY_DEFAULT_FOR_TEST} + {@code SOURCE_TEST}），
     * 已经删掉：本机开出来的会员换不来一次成功的合成——音色的锁定态由服务端按请求里的
     * token 现算（见 {@code /v1/tts/voices}），本机这个布尔值连措辞都改不动，
     * 留着只会让人以为会员能在客户端开关。
     */
    public static final String SOURCE_SERVER = "server";

    private static final String KEY_LEVEL = "level";
    private static final String KEY_EXPIRE_AT = "expireAt";
    private static final String KEY_SOURCE = "source";

    private int level = LEVEL_FREE;
    /** 到期时间戳（毫秒）；0 表示长期有效。 */
    private long expireAt = 0L;
    private String source = "";

    public Member() {
    }

    public Member(int level, long expireAt, @NonNull String source) {
        this.level = level;
        this.expireAt = expireAt;
        this.source = source;
    }

    /** 未开通会员。 */
    @NonNull
    public static Member free() {
        return new Member(LEVEL_FREE, 0L, "");
    }

    /**
     * 开通会员。
     *
     * @param expireAt 到期时间戳；传 0 表示长期有效
     */
    @NonNull
    public static Member vip(long expireAt, @NonNull String source) {
        return new Member(LEVEL_VIP, expireAt, source);
    }

    /** 会员当前是否有效。 */
    public boolean isActive() {
        return isActive(System.currentTimeMillis());
    }

    /** 指定时刻会员是否有效，便于测试过期逻辑。 */
    public boolean isActive(long nowMillis) {
        if (level < LEVEL_VIP) {
            return false;
        }
        return expireAt <= 0L || nowMillis < expireAt;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level >= LEVEL_VIP ? LEVEL_VIP : LEVEL_FREE;
    }

    public long getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(long expireAt) {
        this.expireAt = expireAt > 0L ? expireAt : 0L;
    }

    @NonNull
    public String getSource() {
        return source;
    }

    public void setSource(@NonNull String source) {
        this.source = source;
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put(KEY_LEVEL, level);
            json.put(KEY_EXPIRE_AT, expireAt);
            json.put(KEY_SOURCE, source);
        } catch (JSONException ignored) {
            // ignore
        }
        return json;
    }

    /** 解析失败时按「未开通」处理——宁可让用户少用一个音色，也不能错放权益。 */
    @NonNull
    public static Member fromJson(@NonNull String raw) {
        Member member = new Member();
        try {
            JSONObject json = new JSONObject(raw);
            member.level = json.optInt(KEY_LEVEL, LEVEL_FREE) >= LEVEL_VIP ? LEVEL_VIP : LEVEL_FREE;
            member.expireAt = json.optLong(KEY_EXPIRE_AT, 0L);
            String source = json.optString(KEY_SOURCE, "");
            member.source = source == null ? "" : source;
        } catch (JSONException ignored) {
            return free();
        }
        return member;
    }
}

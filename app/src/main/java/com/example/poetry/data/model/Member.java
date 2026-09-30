package com.example.poetry.data.model;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 会员资格。
 * <p>
 * 眼下会员只解锁一件事：腾讯云语音合成的在线发音人（见 {@code media.QCloudTts}）。
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
    /** 已开通：可以使用腾讯（云端）发音人 */
    public static final int LEVEL_VIP = 1;

    /**
     * 测试期开关：暂定默认给所有人开通会员，方便验证云端音色这条链路。
     * <p>
     * 它只在「从来没写过会员记录」时起作用——用户一旦在语音设置页手动切换过状态，
     * 之后就以存下来的记录为准，不再受这个常量影响。
     * <p>
     * 正式收费上线时把它改成 {@code false}：改完没有会员记录的用户就是普通用户，
     * 云端音色一律显示成锁定态。
     */
    public static final boolean GRANT_BY_DEFAULT_FOR_TEST = true;

    /** 开通来源标记，只用于排查问题，不参与判定。 */
    public static final String SOURCE_TEST = "test";

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

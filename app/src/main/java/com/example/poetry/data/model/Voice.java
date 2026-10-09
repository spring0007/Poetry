package com.example.poetry.data.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 朗读音色，来源有两个：内置的离线引擎（{@code SherpaTts}）从语音包里枚举出来，
 * 或者后台（{@code GET /v1/tts/voices}）下发。
 * <p>
 * {@code id} 也跟着分两族：离线音色形如 {@code sherpa-<语音包>-<sid>}，
 * 云端音色形如 {@code cloud-<音色 ID>}（见 {@link #CLOUD_PREFIX}）。
 * 它会被原样存进 {@link TtsConfig}，所以两族 id 都必须在各自那一侧保持稳定：
 * 离线音色换了模型文件就可能对不上，那时按「没匹配上就用默认」处理
 * （见 {@code SherpaTts.resolve}）。
 * {@code desc} 是音色气质的一句话，{@code tag1/tag2} 放性别与语种标签供列表展示。
 */
public class Voice {

    private String id;
    private String name;
    private String desc;
    private String tag1;
    private String tag2;
    /** 语速倍率 0.5–2.0 */
    private float rate = 1.0f;
    /** 音调 0.5–2.0 */
    private float pitch = 1.0f;
    private String locale;
    private boolean selected;
    private boolean cloud;
    /** 是否锁定（例如云端音色需会员且当前未开通） */
    private boolean locked;

    public Voice() {
        this.id = "";
        this.name = "";
        this.desc = "";
        this.tag1 = "";
        this.tag2 = "";
        this.locale = "zh-CN";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id == null ? "" : id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc == null ? "" : desc;
    }

    public String getTag1() {
        return tag1;
    }

    public void setTag1(String tag1) {
        this.tag1 = tag1 == null ? "" : tag1;
    }

    public String getTag2() {
        return tag2;
    }

    public void setTag2(String tag2) {
        this.tag2 = tag2 == null ? "" : tag2;
    }

    public float getRate() {
        return rate;
    }

    public void setRate(float rate) {
        this.rate = rate;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale == null ? "zh-CN" : locale;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    /** true = 云端音色（后台接口），false = 系统内置 */
    public boolean isCloud() {
        return cloud;
    }

    public void setCloud(boolean cloud) {
        this.cloud = cloud;
    }

    /** 云端音色被锁（需会员）时界面用；离线音色恒为 false */
    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    /** 音色头像首字 */
    @NonNull
    public String getInitial() {
        if (name.isEmpty()) {
            return "声";
        }
        return name.substring(0, 1);
    }

    @Override
    public String toString() {
        return name;
    }

    // ------------------------------------------------------------------ 云端音色 id

    /**
     * 云端音色的 id 前缀，形如 {@code cloud-101001}。
     * <p>
     * 后半段是服务端的音色标识，整张音色表由服务端下发（{@code GET /v1/tts/voices}），
     * 客户端不再自己维护一份——两边各存一张表迟早会漂移，而音色是服务侧的资产，
     * 加一个音色不该需要发版。
     */
    public static final String CLOUD_PREFIX = "cloud-";

    /**
     * 从前的云端音色前缀 {@code qcloud-}，只在读历史配置时会遇到。
     * <p>
     * 直连腾讯云那会儿 id 是客户端自己拼的（{@code qcloud-101001}），改走服务端代理后
     * 统一成了 {@code cloud-}。用户存下来的选择不会跟着改，所以读到旧前缀要就地把前缀换掉
     * （见 {@link #normalizeId}）——否则「上次选的云端音色」永远匹配不上任何一条音色，
     * 界面会把它当成一个不认识的 id 一直挂着。
     */
    public static final String LEGACY_CLOUD_PREFIX = "qcloud-";

    /**
     * 是不是云端音色 id。含历史前缀，所以存了很久的旧配置也认。
     * <p>
     * 与 {@link #isCloud()} 不是一回事：那个看的是**这一条音色记录**从哪来（服务端下发 vs
     * 语音包枚举），这里只认 id 的写法，用来判断「用户存的那个 id 是不是云端的」。
     */
    public static boolean isCloudId(@Nullable String id) {
        return id != null
                && (id.startsWith(CLOUD_PREFIX) || id.startsWith(LEGACY_CLOUD_PREFIX));
    }

    /**
     * 把历史 id 规整成当前写法（{@code qcloud-101001} → {@code cloud-101001}），
     * 其余原样返回；null 与空串都规整成空串，调用方永远拿到一个字符串。
     */
    @NonNull
    public static String normalizeId(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        if (id.startsWith(LEGACY_CLOUD_PREFIX)) {
            return CLOUD_PREFIX + id.substring(LEGACY_CLOUD_PREFIX.length());
        }
        return id;
    }
}

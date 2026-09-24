package com.example.poetry.data.model;

import androidx.annotation.NonNull;

/**
 * 朗读音色，由内置的离线引擎（{@code SherpaTts}）从语音包里枚举出来。
 * <p>
 * {@code id} 形如 {@code sherpa-<语音包>-<sid>}，会原样存进 {@link TtsConfig}，
 * 所以它必须在同一个语音包下保持稳定——换了模型文件就可能对不上，
 * 那时按「没匹配上就用默认」处理（见 {@code SherpaTts.resolve}）。
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
}

package com.example.poetry.data.model;

import androidx.annotation.NonNull;

/**
 * 分类项：体裁 / 朝代 / 主题 三类入口的统一模型
 * （分类页四个分区共用一套 RecyclerView 数据）。
 */
public class Category {

    /** 过滤用的 code：体裁 code / 朝代 code / 主题关键词 */
    private final String code;
    private final String name;
    private final String desc;
    private long count;
    private int colorRes;
    private int lightColorRes;
    /** 体裁卡的巨型水印字 */
    private String mark;

    public Category(@NonNull String code, @NonNull String name, String desc) {
        this.code = code;
        this.name = name;
        this.desc = desc == null ? "" : desc;
        this.mark = "";
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDesc() {
        return desc;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public int getColorRes() {
        return colorRes;
    }

    public void setColorRes(int colorRes) {
        this.colorRes = colorRes;
    }

    public int getLightColorRes() {
        return lightColorRes;
    }

    public void setLightColorRes(int lightColorRes) {
        this.lightColorRes = lightColorRes;
    }

    public String getMark() {
        return mark;
    }

    public void setMark(String mark) {
        this.mark = mark == null ? "" : mark;
    }

    @Override
    public String toString() {
        return name;
    }
}

package com.example.poetry.data.model;

/**
 * 主题标签（分类页「按主题」）。
 * <p>
 * 数据库未内置主题表，这里用关键词在正文里检索（{@code poetry.db} 为简体无空格文本，
 * 检索前需先用 {@link com.example.poetry.util.QueryNormalizer} 归一化）。
 */
public final class Theme {

    /** 主题关键词 */
    public final String keyword;
    /** 主题展示名 */
    public final String label;

    public Theme(String keyword, String label) {
        this.keyword = keyword;
        this.label = label;
    }

    /** 设计稿固定 12 个主题 */
    public static Theme[] defaults() {
        return new Theme[]{
                new Theme("月", "明月"),
                new Theme("山", "山水"),
                new Theme("江", "江河"),
                new Theme("花", "花木"),
                new Theme("酒", "饮酒"),
                new Theme("春", "春色"),
                new Theme("秋", "秋思"),
                new Theme("雪", "雨雪"),
                new Theme("归", "思乡"),
                new Theme("别", "送别"),
                new Theme("塞", "边塞"),
                new Theme("田", "田园")
        };
    }
}

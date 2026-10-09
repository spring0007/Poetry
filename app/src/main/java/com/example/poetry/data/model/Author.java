package com.example.poetry.data.model;

import androidx.annotation.NonNull;

/**
 * 作者：映射 {@code poetry.db → authors} 表
 * （id / name / dynasty / desc / n_poems）。
 */
public class Author {

    private long id;
    /** 内容派生的稳定键（authors.uid）。本地库与后端都有这一列，换库不变，接口一律按它查。 */
    private String uid;
    private String name;
    private String dynasty;
    private String desc;
    private int nPoems;

    public Author() {
        this.uid = "";
        this.name = "";
        this.dynasty = "";
        this.desc = "";
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    /** 稳定键：本地库与后端都以它关联作者，{@code id} 反而会随库版本整体错位 */
    @NonNull
    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid == null ? "" : uid;
    }

    /**
     * 拿去向后端 / 本地库查这位作者时用的引用。
     *
     * <p>优先 uid：它是内容派生的，后端换成别的库也认得。
     * 没有 uid（内置示例数据）时退化成数字 id，本地库按 id 查照样能用。
     */
    @NonNull
    public String getLookupRef() {
        return uid.isEmpty() ? String.valueOf(id) : uid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public String getDynasty() {
        return dynasty;
    }

    public void setDynasty(String dynasty) {
        this.dynasty = dynasty == null ? "" : dynasty;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc == null ? "" : desc;
    }

    public int getNPoems() {
        return nPoems;
    }

    public void setNPoems(int nPoems) {
        this.nPoems = nPoems;
    }

    /** 头像用首字（取姓名最后一个字，复姓/长名更好看） */
    @NonNull
    public String getInitial() {
        if (name.isEmpty()) {
            return "佚";
        }
        return name.substring(name.length() - 1);
    }

    /** 「唐 · 李白」 */
    @NonNull
    public String getLabel() {
        return Dynasty.labelOf(dynasty) + " · " + name;
    }

    /** 作者简介的一句话摘要（去掉换行） */
    @NonNull
    public String getBrief() {
        String text = desc.replace("\n", "").trim();
        if (text.length() > 60) {
            return text.substring(0, 60) + "…";
        }
        return text.isEmpty() ? "暂无简介" : text;
    }

    @Override
    public String toString() {
        return name;
    }
}

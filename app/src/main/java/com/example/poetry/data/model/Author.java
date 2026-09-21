package com.example.poetry.data.model;

import androidx.annotation.NonNull;

/**
 * 作者：映射 {@code poetry.db → authors} 表
 * （id / name / dynasty / desc / n_poems）。
 */
public class Author {

    private long id;
    private String name;
    private String dynasty;
    private String desc;
    private int nPoems;

    public Author() {
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

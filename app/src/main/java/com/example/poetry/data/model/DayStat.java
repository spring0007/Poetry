package com.example.poetry.data.model;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 某一天的朗读 / 收藏统计。
 *
 * <p>由 {@code UserStore} 的 {@code daily} 分区反序列化而来，按日期分组，
 * 一份 JSON 就能直接交给后台做日活与内容偏好分析，不需要后台再去反推。
 */
public final class DayStat {

    /** 一天里的一条记录：哪首诗、几次、最后一次是什么时候 */
    public static final class Item {
        public final long id;
        @NonNull
        public final String title;
        @NonNull
        public final String author;
        public final int count;
        public final long lastAt;

        public Item(long id, @NonNull String title, @NonNull String author,
                    int count, long lastAt) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.count = count;
            this.lastAt = lastAt;
        }
    }

    /** 日期串，yyyy-MM-dd */
    @NonNull
    public final String date;
    /** 这一天朗读的总次数（同一首反复读会累加） */
    public int playCount;
    /** 这一天新增的收藏数 */
    public int favoriteCount;
    /** 这一天取消的收藏数 */
    public int unfavoriteCount;
    /** 这一天读过的诗 */
    @NonNull
    public final List<Item> plays = new ArrayList<>();
    /** 这一天收藏的诗 */
    @NonNull
    public final List<Item> favorites = new ArrayList<>();
    /** 这一天取消收藏的诗 */
    @NonNull
    public final List<Item> unfavorites = new ArrayList<>();

    public DayStat(@NonNull String date) {
        this.date = date;
    }

    /** 这一天读过的不同篇数 */
    public int playTitles() {
        return plays.size();
    }
}

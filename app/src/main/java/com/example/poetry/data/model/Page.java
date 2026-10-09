package com.example.poetry.data.model;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

/**
 * 一页数据。
 *
 * <p>形状直接对齐后端的 {@code {total,page,size,items}}，本地库也给同一种东西：
 * 列表页要判断「还有没有下一页」，只有拿到 {@code total} 才不必靠「本页是否满」去猜。
 *
 * @param <T> 元素类型
 */
public final class Page<T> {

    /** 总数未知时的取值。本地库懒得分页、远端接口没回 total，都会落到这个值。 */
    public static final long TOTAL_UNKNOWN = -1L;

    private final List<T> items;
    private final long total;
    private final int page;
    private final int size;

    public Page(@NonNull List<T> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    /** 只有一页、且不知道总数（种子数据、联想结果这类）。 */
    @NonNull
    public static <T> Page<T> single(@NonNull List<T> items) {
        return new Page<>(items, TOTAL_UNKNOWN, 1, items.size());
    }

    @NonNull
    public static <T> Page<T> empty(int page, int size) {
        return new Page<>(Collections.<T>emptyList(), 0L, page, size);
    }

    @NonNull
    public List<T> getItems() {
        return items;
    }

    /** 总条数；{@link #TOTAL_UNKNOWN} 表示服务端没给。 */
    public long getTotal() {
        return total;
    }

    /** 从 1 开始 */
    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * 还有没有下一页。
     *
     * <p>知道总数就精确算；不知道就退化成「这一页装满了没有」——满页说明后面可能还有，
     * 让用户多滑一次总比少给数据强（多滑一次顶多多发一个空请求）。
     */
    public boolean hasMore() {
        if (items.isEmpty()) {
            return false;
        }
        if (total >= 0L) {
            return (long) page * size < total;
        }
        return size > 0 && items.size() >= size;
    }
}

package com.example.poetry.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * 「按什么筛作品」的描述：一个模式 + 一个取值。
 *
 * <p>存在的理由：列表页有六种入口（体裁 / 朝代 / 主题 / 作者 / 检索 / 热度），
 * 它们在仓库层要做的事完全一样（取本地一页、取远端一页），只有「拿什么去查」不同。
 * 不抽这一个值类型的话，仓库就得为每种模式各写两遍分页方法，一共十二个几乎一样的函数。
 *
 * <p>模式和取值的语义按 {@link #getMode()} 分：
 * <ul>
 *   <li>{@link #MODE_KIND} —— 体裁 code（见 {@code PoemKind}）</li>
 *   <li>{@link #MODE_DYNASTY} —— 朝代 code（见 {@code Dynasty}）</li>
 *   <li>{@link #MODE_THEME} —— 主题关键词（本地按正文 LIKE 检索）</li>
 *   <li>{@link #MODE_AUTHOR} —— 作者引用，**uid 优先**（见 {@code Author#getLookupRef()}）</li>
 *   <li>{@link #MODE_SEARCH} —— 检索关键词</li>
 *   <li>{@link #MODE_HOT} —— 无取值，按热度降序</li>
 * </ul>
 *
 * <p>这些字符串同时是 {@code PoemListActivity} 的 Intent extra 取值，
 * 两处共用同一批常量，别再各写一份字面量。
 */
public final class PoemQuery {

    public static final String MODE_KIND = "kind";
    public static final String MODE_DYNASTY = "dynasty";
    public static final String MODE_THEME = "theme";
    public static final String MODE_AUTHOR = "author";
    public static final String MODE_SEARCH = "search";
    /** 热点诗词：按热度（score）降序 */
    public static final String MODE_HOT = "hot";

    private final String mode;
    private final String value;

    private PoemQuery(@NonNull String mode, @Nullable String value) {
        this.mode = mode;
        this.value = value == null ? "" : value;
    }

    @NonNull
    public static PoemQuery of(@Nullable String mode, @Nullable String value) {
        return new PoemQuery(mode == null || mode.isEmpty() ? MODE_KIND : mode, value);
    }

    @NonNull
    public static PoemQuery kind(@NonNull String kindCode) {
        return new PoemQuery(MODE_KIND, kindCode);
    }

    @NonNull
    public static PoemQuery dynasty(@NonNull String dynastyCode) {
        return new PoemQuery(MODE_DYNASTY, dynastyCode);
    }

    @NonNull
    public static PoemQuery theme(@NonNull String keyword) {
        return new PoemQuery(MODE_THEME, keyword);
    }

    @NonNull
    public static PoemQuery author(@NonNull String authorRef) {
        return new PoemQuery(MODE_AUTHOR, authorRef);
    }

    @NonNull
    public static PoemQuery search(@NonNull String keyword) {
        return new PoemQuery(MODE_SEARCH, keyword);
    }

    /** 热点榜：取值为空 */
    @NonNull
    public static PoemQuery hot() {
        return new PoemQuery(MODE_HOT, "");
    }

    @NonNull
    public String getMode() {
        return mode;
    }

    /** 取值；{@link #MODE_HOT} 下恒为空串 */
    @NonNull
    public String getValue() {
        return value;
    }

    /**
     * 本地这条路支不支持按 offset 翻页。
     *
     * <p>检索与主题是**不支持**的：本地检索是「作者→标题→正文」三段并集
     * （见 {@code PoetryDatabase.search}），结果带并集去重、也没有稳定的整体排序，
     * 硬加 OFFSET 会漏条目。它们因此只有第一页来自本地，往后都要靠远端。
     */
    public boolean isLocallyPaged() {
        return !MODE_SEARCH.equals(mode) && !MODE_THEME.equals(mode);
    }

    @NonNull
    @Override
    public String toString() {
        return value.isEmpty() ? mode : mode + ":" + value;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PoemQuery)) {
            return false;
        }
        PoemQuery other = (PoemQuery) o;
        return mode.equals(other.mode) && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, value);
    }
}

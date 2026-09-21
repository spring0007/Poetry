package com.example.poetry.data.local;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.model.Author;
import com.example.poetry.data.model.Category;
import com.example.poetry.data.model.Dynasty;
import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.PoemKind;
import com.example.poetry.data.model.Theme;
import com.example.poetry.util.QueryNormalizer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地诗库（{@code poetry.db}）只读封装。
 * <p>
 * 表结构（schema_version = 3.0）：
 * <pre>
 *   poems(id, author_id, title, rhythmic_id, src_id, body, tags, notes, score, n_char)
 *   authors(id, name, dynasty, desc, n_poems)
 *   rhythmics(id, name)         -- 词牌 / 曲牌
 *   sources(id, name)           -- tang-poem / song-ci / yuan-qu …
 *   meta(k, v)
 *   poetry-strains.db → poem_strains(id, data BLOB, len)   -- 平仄（可选包）
 * </pre>
 * 注意：正文为「简体 + 无空格」的归一化文本，且未开启 FTS，
 * 因此所有关键词检索都要先经过 {@link QueryNormalizer}。
 */
public final class PoetryDatabase {

    private static final String TAG = "PoetryDatabase";
    private static final int OPEN_FLAG = SQLiteDatabase.OPEN_READONLY;

    /** 设计稿「热门搜索」固定词 */
    public static final String[] HOT_KEYWORDS = {
            "中秋", "明月", "李白", "苏轼", "思乡", "边塞", "田园", "宋词", "元曲", "离骚"
    };

    private static final String POEM_COLUMNS =
            "p.id AS _id, p.author_id, p.title, p.rhythmic_id, p.src_id, p.body, p.tags, p.notes,"
                    + " p.score, p.n_char, a.name AS author_name, a.dynasty AS author_dynasty,"
                    + " r.name AS rhythmic_name, s.name AS src_name";

    private static final String POEM_FROM =
            " FROM poems p"
                    + " LEFT JOIN authors a ON a.id = p.author_id"
                    + " LEFT JOIN rhythmics r ON r.id = p.rhythmic_id"
                    + " LEFT JOIN sources s ON s.id = p.src_id";

    private static volatile PoetryDatabase instance;

    private SQLiteDatabase db;
    private SQLiteDatabase strainDb;
    private boolean ready;

    /** 分类计数缓存（首屏只需算一次） */
    private final Map<String, Long> kindCountCache = new HashMap<>();
    private final Map<String, Long> dynastyCountCache = new HashMap<>();
    private long totalCache = -1L;

    private PoetryDatabase(@NonNull Context context) {
        open(context);
    }

    public static PoetryDatabase get(@NonNull Context context) {
        if (instance == null) {
            synchronized (PoetryDatabase.class) {
                if (instance == null) {
                    instance = new PoetryDatabase(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    // ------------------------------------------------------------ 生命周期

    private void open(@NonNull Context context) {
        File mainFile = DatabaseProvider.locate(context, DatabaseProvider.POETRY_DB);
        if (mainFile == null) {
            ready = false;
            Log.w(TAG, "poetry.db not found, app will fall back to seed data");
            return;
        }
        try {
            db = SQLiteDatabase.openDatabase(mainFile.getAbsolutePath(), null, OPEN_FLAG);
            ready = true;
            Log.i(TAG, "poetry.db opened: " + mainFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "open poetry.db failed", e);
            ready = false;
            return;
        }
        File strainFile = DatabaseProvider.locate(context, DatabaseProvider.STRAIN_DB);
        if (strainFile != null) {
            try {
                strainDb = SQLiteDatabase.openDatabase(strainFile.getAbsolutePath(), null, OPEN_FLAG);
            } catch (Exception e) {
                Log.w(TAG, "open poetry-strains.db failed", e);
            }
        }
    }

    public boolean isReady() {
        return ready && db != null && db.isOpen();
    }

    /** 平仄库是否可用 */
    public boolean hasStrains() {
        return strainDb != null && strainDb.isOpen();
    }

    public synchronized void close() {
        if (db != null) {
            db.close();
            db = null;
        }
        if (strainDb != null) {
            strainDb.close();
            strainDb = null;
        }
        ready = false;
    }

    // ------------------------------------------------------------ 查询：作品

    /**
     * 精选：按热度分取前 N 首（发现页「精选诗词」与 Hero 推荐共用）。
     */
    @NonNull
    public List<Poem> featured(int limit) {
        List<Poem> list = new ArrayList<>();
        if (!isReady()) {
            return list;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT " + POEM_COLUMNS + POEM_FROM
                    + " ORDER BY p.score DESC, p.id ASC LIMIT " + Math.max(1, limit), null);
            while (c.moveToNext()) {
                list.add(readPoem(c));
            }
        } catch (Exception e) {
            Log.e(TAG, "featured failed", e);
        } finally {
            closeQuietly(c);
        }
        return list;
    }

    /**
     * 随机推荐（每日一诗）：从高分池里偏移取一首。
     */
    @Nullable
    public Poem randomFeatured() {
        List<Poem> pool = featured(60);
        if (pool.isEmpty()) {
            return null;
        }
        int index = (int) (System.currentTimeMillis() / 86_400_000L % pool.size());
        return pool.get(index);
    }

    @Nullable
    public Poem poemById(long id) {
        if (!isReady()) {
            return null;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT " + POEM_COLUMNS + POEM_FROM + " WHERE p.id = ?",
                    new String[]{String.valueOf(id)});
            if (c.moveToFirst()) {
                return readPoem(c);
            }
        } catch (Exception e) {
            Log.e(TAG, "poemById failed", e);
        } finally {
            closeQuietly(c);
        }
        return null;
    }

    /**
     * 综合检索：作者 → 标题 → 正文，三段式并去重。
     * <p>
     * 不加 ORDER BY，让 SQLite 在凑够 LIMIT 后立即停止扫描（库有 34 万首，避免全表排序）。
     */
    @NonNull
    public List<Poem> search(@NonNull String keyword, int limit) {
        List<Poem> out = new ArrayList<>();
        if (!isReady()) {
            return out;
        }
        String keywordLike = QueryNormalizer.like(keyword);
        String plain = QueryNormalizer.normalize(keyword);
        if (plain.isEmpty()) {
            return out;
        }
        Set<Long> seen = new HashSet<>();

        // 1) 作者命中（authors 仅 1.3 万行，最快）
        append(out, seen, "SELECT " + POEM_COLUMNS + POEM_FROM
                        + " WHERE p.author_id IN (SELECT id FROM authors WHERE name LIKE ? ESCAPE '\\')"
                        + " LIMIT " + limit,
                new String[]{keywordLike}, limit);

        // 2) 标题命中
        if (out.size() < limit) {
            append(out, seen, "SELECT " + POEM_COLUMNS + POEM_FROM
                            + " WHERE p.title LIKE ? ESCAPE '\\' LIMIT " + limit,
                    new String[]{keywordLike}, limit);
        }

        // 3) 正文命中
        if (out.size() < limit) {
            append(out, seen, "SELECT " + POEM_COLUMNS + POEM_FROM
                            + " WHERE p.body LIKE ? ESCAPE '\\' LIMIT " + limit,
                    new String[]{keywordLike}, limit);
        }
        return out;
    }

    private void append(List<Poem> out, Set<Long> seen, String sql, String[] args, int limit) {
        Cursor c = null;
        try {
            c = db.rawQuery(sql, args);
            while (c.moveToNext() && out.size() < limit) {
                Poem poem = readPoem(c);
                if (seen.add(poem.getId())) {
                    out.add(poem);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "query failed: " + sql, e);
        } finally {
            closeQuietly(c);
        }
    }

    // ------------------------------------------------------------ 查询：分类过滤

    @NonNull
    public List<Poem> listByKind(@NonNull PoemKind kind, int limit) {
        List<Poem> out = new ArrayList<>();
        if (!isReady()) {
            return out;
        }
        String[] sources = kind.sourceNames();
        String placeholders = placeholders(sources.length);
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT " + POEM_COLUMNS + POEM_FROM
                            + " WHERE s.name IN (" + placeholders + ")"
                            + " ORDER BY p.score DESC LIMIT " + Math.max(1, limit),
                    sources);
            while (c.moveToNext()) {
                out.add(readPoem(c));
            }
        } catch (Exception e) {
            Log.e(TAG, "listByKind failed", e);
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    @NonNull
    public List<Poem> listByDynasty(@NonNull String dynastyCode, int limit) {
        List<Poem> out = new ArrayList<>();
        if (!isReady()) {
            return out;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT " + POEM_COLUMNS + POEM_FROM
                            + " WHERE a.dynasty = ? ORDER BY p.score DESC LIMIT " + Math.max(1, limit),
                    new String[]{dynastyCode});
            while (c.moveToNext()) {
                out.add(readPoem(c));
            }
        } catch (Exception e) {
            Log.e(TAG, "listByDynasty failed", e);
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    @NonNull
    public List<Poem> listByAuthor(long authorId, int limit) {
        List<Poem> out = new ArrayList<>();
        if (!isReady()) {
            return out;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT " + POEM_COLUMNS + POEM_FROM
                            + " WHERE p.author_id = ? ORDER BY p.score DESC LIMIT " + Math.max(1, limit),
                    new String[]{String.valueOf(authorId)});
            while (c.moveToNext()) {
                out.add(readPoem(c));
            }
        } catch (Exception e) {
            Log.e(TAG, "listByAuthor failed", e);
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    /** 主题检索：按关键词在正文里找 */
    @NonNull
    public List<Poem> listByTheme(@NonNull String keyword, int limit) {
        return search(keyword, limit);
    }

    // ------------------------------------------------------------ 查询：统计

    public long totalCount() {
        if (!isReady()) {
            return 0;
        }
        if (totalCache >= 0) {
            return totalCache;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM poems", null);
            if (c.moveToFirst()) {
                totalCache = c.getLong(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "totalCount failed", e);
        } finally {
            closeQuietly(c);
        }
        return Math.max(0, totalCache);
    }

    /** 体裁 -> 篇数 */
    public long countOfKind(@NonNull PoemKind kind) {
        if (!isReady()) {
            return 0;
        }
        Long cached = kindCountCache.get(kind.getCode());
        if (cached != null) {
            return cached;
        }
        long total = 0;
        String[] sources = kind.sourceNames();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM poems p LEFT JOIN sources s ON s.id = p.src_id"
                    + " WHERE s.name IN (" + placeholders(sources.length) + ")", sources);
            if (c.moveToFirst()) {
                total = c.getLong(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "countOfKind failed", e);
        } finally {
            closeQuietly(c);
        }
        kindCountCache.put(kind.getCode(), total);
        return total;
    }

    /** 分类页「按体裁」五种大卡 */
    @NonNull
    public List<Category> kindCategories() {
        List<Category> list = new ArrayList<>();
        for (PoemKind kind : PoemKind.values()) {
            Category category = new Category(kind.getCode(), kind.getLabel(), kind.getDesc());
            category.setCount(countOfKind(kind));
            category.setColorRes(kind.getColorRes());
            category.setLightColorRes(kind.getLightColorRes());
            category.setMark(kind.getMark());
            list.add(category);
        }
        return list;
    }

    /** 分类页「按朝代」宫格 */
    @NonNull
    public List<Category> dynastyCategories() {
        List<Category> list = new ArrayList<>();
        if (!isReady()) {
            return list;
        }
        Map<String, Long> counts = new HashMap<>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT dynasty, SUM(n_poems) FROM authors GROUP BY dynasty", null);
            while (c.moveToNext()) {
                counts.put(c.getString(0), c.getLong(1));
            }
        } catch (Exception e) {
            Log.e(TAG, "dynastyCategories failed", e);
        } finally {
            closeQuietly(c);
        }
        dynastyCountCache.clear();
        dynastyCountCache.putAll(counts);
        for (Dynasty dynasty : Dynasty.values()) {
            Long count = counts.get(dynasty.getCode());
            if (count == null || count <= 0) {
                continue;
            }
            Category category = new Category(dynasty.getCode(), dynasty.getLabel(), "");
            category.setCount(count);
            list.add(category);
        }
        return list;
    }

    /** 分类页「按主题」 */
    @NonNull
    public List<Category> themeCategories() {
        List<Category> list = new ArrayList<>();
        for (Theme theme : Theme.defaults()) {
            Category category = new Category(theme.keyword, theme.label, "");
            // 主题篇数用 LIKE 计数代价过高，这里用「高分池命中数」做近似展示
            category.setCount(approxThemeCount(theme.keyword));
            list.add(category);
        }
        return list;
    }

    private long approxThemeCount(@NonNull String keyword) {
        if (!isReady()) {
            return 0;
        }
        long count = 0;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM (SELECT p.id FROM poems p"
                            + " WHERE p.body LIKE ? ESCAPE '\\' LIMIT 2000)",
                    new String[]{QueryNormalizer.like(keyword)});
            if (c.moveToFirst()) {
                count = c.getLong(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "approxThemeCount failed", e);
        } finally {
            closeQuietly(c);
        }
        return count;
    }

    /** 热门作者（按作品数） */
    @NonNull
    public List<Author> topAuthors(int limit) {
        List<Author> out = new ArrayList<>();
        if (!isReady()) {
            return out;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT id, name, dynasty, desc, n_poems FROM authors"
                    + " ORDER BY n_poems DESC LIMIT " + Math.max(1, limit), null);
            while (c.moveToNext()) {
                Author author = new Author();
                author.setId(c.getLong(0));
                author.setName(c.getString(1));
                author.setDynasty(c.getString(2));
                author.setDesc(c.getString(3));
                author.setNPoems(c.getInt(4));
                out.add(author);
            }
        } catch (Exception e) {
            Log.e(TAG, "topAuthors failed", e);
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    // ------------------------------------------------------------ 查询：平仄

    /**
     * 取平仄串（来自 poetry-strains.db）。
     * <p>
     * data 为 ASCII 串，字符含义：1=平 0=仄 2=可平可仄 3=平韵 4=仄韵。
     */
    @NonNull
    public String strainOf(long poemId) {
        if (!hasStrains()) {
            return "";
        }
        Cursor c = null;
        try {
            c = strainDb.rawQuery("SELECT data FROM poem_strains WHERE id = ?",
                    new String[]{String.valueOf(poemId)});
            if (c.moveToFirst()) {
                byte[] data = c.getBlob(0);
                return new String(data, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.w(TAG, "strainOf failed", e);
        } finally {
            closeQuietly(c);
        }
        return "";
    }

    // ------------------------------------------------------------ 工具

    @NonNull
    private static Poem readPoem(@NonNull Cursor c) {
        Poem poem = new Poem();
        poem.setId(c.getLong(c.getColumnIndexOrThrow("_id")));
        poem.setAuthorId(c.getLong(c.getColumnIndexOrThrow("author_id")));
        poem.setTitle(c.getString(c.getColumnIndexOrThrow("title")));
        poem.setSrcName(c.getString(c.getColumnIndexOrThrow("src_name")));
        poem.setBody(c.getString(c.getColumnIndexOrThrow("body")));
        poem.setTags(c.getString(c.getColumnIndexOrThrow("tags")));
        poem.setNotes(c.getString(c.getColumnIndexOrThrow("notes")));
        poem.setScore(c.getInt(c.getColumnIndexOrThrow("score")));
        poem.setNChar(c.getInt(c.getColumnIndexOrThrow("n_char")));
        poem.setAuthorName(c.getString(c.getColumnIndexOrThrow("author_name")));
        poem.setDynasty(c.getString(c.getColumnIndexOrThrow("author_dynasty")));
        poem.setRhythmic(c.getString(c.getColumnIndexOrThrow("rhythmic_name")));
        return poem;
    }

    @NonNull
    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('?');
        }
        return sb.toString();
    }

    private static void closeQuietly(@Nullable Cursor c) {
        if (c != null && !c.isClosed()) {
            c.close();
        }
    }
}

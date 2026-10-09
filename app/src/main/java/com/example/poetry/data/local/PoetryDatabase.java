package com.example.poetry.data.local;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.model.Author;
import com.example.poetry.data.model.Category;
import com.example.poetry.data.model.Dynasty;
import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.PoemKind;
import com.example.poetry.data.model.Theme;
import com.example.poetry.util.LogUtil;
import com.example.poetry.util.QueryNormalizer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地诗库（{@code poetry.db}）只读封装。
 * <p>
 * 表结构（schema_version = 3.3）：
 * <pre>
 *   poems(id, uid, author_id, author_uid, title, rhythmic_id, src_id, body, tags, score, n_char)
 *   authors(id, uid, name, dynasty, desc, n_poems)
 *   rhythmics(id, name)         -- 词牌 / 曲牌
 *   sources(id, name)           -- tang-poem / song-ci / yuan-qu …
 *   poem_extras(uid PK, translation, notes, introduction, creative_background, appreciation, images)
 *   meta(k, v)
 *   poetry-strains.db → poem_strains(id, data BLOB, len)   -- 平仄（可选包）
 * </pre>
 * 注意：
 * <ul>
 *   <li>正文为「简体 + 无空格」的归一化文本，且未开启 FTS，
 *       因此所有关键词检索都要先经过 {@link QueryNormalizer}；</li>
 *   <li><b>poems 已没有 notes 列</b>，注释/译文/赏析/背景/简介都在 {@code poem_extras} 里，
 *       按 {@code poems.uid} 关联（不是按 id）；</li>
 *   <li>{@code authors.n_poems} 记的是**母库**篇数（陆游标着 9416 而本库只有 7 首），
 *       任何「有多少首」的显示都必须现算 {@code COUNT(*)}，不能直接用它。</li>
 * </ul>
 */
public final class PoetryDatabase {

    private static final int OPEN_FLAG = SQLiteDatabase.OPEN_READONLY;

    /**
     * 本 App 认得的 schema 版本。主版本号（点号前面那段）必须与库里的
     * {@code meta.schema_version} 一致，否则下载下来的库会被拒绝安装——
     * 一个 4.0 的库配 3.0 的 App 会在三个界面之后才抛 {@code SQLiteException}，
     * 不如在安装前就说清楚。
     */
    public static final String SCHEMA_VERSION = "3.3";

    /** 设计稿「热门搜索」固定词 */
    public static final String[] HOT_KEYWORDS = {
            "中秋", "明月", "李白", "苏轼", "思乡", "边塞", "田园", "宋词", "元曲", "离骚"
    };

    /**
     * 「随机换一首」的字数上限。
     *
     * <p>库里有 5 首超过 1 万字的合集（「古文观止」141,014 字、「千家诗」10,020 字），
     * 它们是母库里按整本收录的一条记录，抽中后正文要铺几千行。
     */
    private static final int MAX_RANDOM_N_CHAR = 2000;

    /**
     * 作品查询的公共列。富文本一律从 {@code poem_extras} 取（左连接，缺行时为 NULL，
     * 由 {@link #readPoem} 统一折成空串）。
     *
     * <p>列表查询也带上这些列是有意的：富文本每行不到 1 KB，而这样列表页塞进 Intent 的
     * {@code Poem} 与详情页滑动时重新查出来的就是**同一个形状**，详情页不需要再多一次查询。
     */
    private static final String POEM_COLUMNS =
            "p.id AS _id, p.uid, p.author_id, p.title, p.rhythmic_id, p.src_id, p.body, p.tags,"
                    + " p.score, p.n_char, a.name AS author_name, a.dynasty AS author_dynasty,"
                    + " r.name AS rhythmic_name, s.name AS src_name,"
                    + " e.translation, e.notes, e.introduction, e.creative_background, e.appreciation";

    private static final String POEM_FROM =
            " FROM poems p"
                    + " LEFT JOIN authors a ON a.id = p.author_id"
                    + " LEFT JOIN rhythmics r ON r.id = p.rhythmic_id"
                    + " LEFT JOIN sources s ON s.id = p.src_id"
                    + " LEFT JOIN poem_extras e ON e.uid = p.uid";

    private static volatile PoetryDatabase instance;

    private final Context context;

    private SQLiteDatabase db;
    private SQLiteDatabase strainDb;
    private boolean ready;

    /**
     * 上次 {@link #open} 时磁盘上到底有没有那个文件。
     *
     * <p>和 {@link #isReady()} 不是一回事：文件在但打开失败（下坏了、被截断）时它是 true
     * 而 ready 是 false，界面据此说「诗库损坏，请重新下载」而不是「还没下载」。
     * <b>只在这里记录、不在别处查磁盘</b>——{@code locate()} 在极端情况下会去拷贝 assets，
     * 那种开销不能出现在每次查询的路径上。
     */
    private volatile boolean filePresent;

    /**
     * 安装代数。{@link #markStale()} 在替换文件**之前**递增，{@link #openedGeneration}
     * 记录当前句柄是哪一代打开的。
     *
     * <p>为什么需要它：Linux 的 {@code rename(2)} 只是把新 inode 挂到那个名字上，
     * 已经在跑的 {@link SQLiteDatabase} 还攥着旧 inode 的 fd，会继续正常返回**旧数据**且
     * 永不报错；而 {@code ensureOpen()} 看到「ready 且 db 还开着」就直接返回，也不会察觉。
     *
     * <p><b>真正干活的是显式调用 {@link #reload()} 的那一次</b>，代数的主要作用是自愈：
     * 万一有线程抢在前面看到新文件，下一次 {@code ensureOpen()} 能自己纠正过来。
     * 看到这里觉得「代数没被用到」时别删——它兜的就是那个竞态。
     */
    private long installGeneration;
    private long openedGeneration = -1L;

    /**
     * 分类计数缓存（首屏只需算一次）。
     *
     * <p>用 {@link ConcurrentHashMap} 而不是 {@code HashMap}：读诗的池子有 2 个线程
     * （见 {@code AppExecutors.io()}），分类页和发现页可能同时命中同一个缓存。
     * 并发 {@code put} 一个普通 HashMap 会把它内部结构改坏 —— 症状是查询偶发地
     * 死循环或返回错值，而不是抛异常，极难查。
     */
    private final Map<String, Long> kindCountCache = new ConcurrentHashMap<>();
    private final Map<String, Long> dynastyCountCache = new ConcurrentHashMap<>();
    /**
     * 主题近似计数。{@link #approxThemeCount} 是一条 {@code body LIKE} 全表扫描，
     * 12 个主题就是 12 次；诗库是静态的，算一次就够一辈子。
     */
    private final Map<String, Long> themeCountCache = new ConcurrentHashMap<>();
    private volatile long totalCache = -1L;

    /**
     * Construction stays cheap on purpose: a background thread warms the repository up,
     * and nothing here touches the disk.
     */
    private PoetryDatabase(@NonNull Context context) {
        this.context = context.getApplicationContext();
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

    /**
     * Connects to the database if it is not connected yet, picking up the files once a
     * background copy has finished.
     *
     * Must be called off the main thread - the first successful call copies about 56 MB
     * out of the assets before opening SQLite. Queries go through it so the app keeps
     * working while the warm-up is still running (falling back to the seed data).
     */
    public synchronized void ensureOpen() {
        if (ready && db != null && db.isOpen() && openedGeneration == installGeneration) {
            return;
        }
        if (db != null && openedGeneration != installGeneration) {
            // 文件被换过而句柄还是老的：不关掉的话下面这次 open() 只会把新旧句柄叠在一起。
            closeLocked();
        }
        open(context);
        openedGeneration = installGeneration;
    }

    // ------------------------------------------------------------ 生命周期

    /**
     * 标记「磁盘上的文件换过了」，不关句柄。
     *
     * <p>必须在 {@code rename} 之前调用：之后才调用的话，中间那段时间里
     * {@code ensureOpen()} 会看到代数是新的就直接重新打开，可能读到一个还没挂好的名字。
     */
    public synchronized void markStale() {
        installGeneration++;
    }

    /**
     * 关掉再打开，让新装好的文件真正生效。**必须在 IO 线程调用。**
     *
     * <p>关掉重开是 {@link com.example.poetry.data.AppExecutors} 单线程上的一个任务，
     * 和所有查询排在同一条队里：如果在下载线程上关，而 IO 线程正在 {@code rawQuery}，
     * {@code SQLiteClosable} 的引用计数会推迟真正的关闭、但 {@code isOpen()} 立刻变 false，
     * 后续查询会抛 {@code IllegalStateException}（现有查询都吞异常返回空列表，所以不会崩，
     * 但界面会闪一下空状态）。
     *
     * @return 重开之后库是否可用
     */
    public synchronized boolean reload() {
        closeLocked();
        open(context);
        openedGeneration = installGeneration;
        // 缓存描述的是旧文件里的数字，必须一起清掉——否则换了库之后「共 N 首」还是老的。
        kindCountCache.clear();
        dynastyCountCache.clear();
        themeCountCache.clear();
        totalCache = -1L;
        return isReady();
    }

    /** 释放句柄但不递增代数。调用方负责同步。 */
    private void closeLocked() {
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

    private void open(@NonNull Context context) {
        File mainFile = DatabaseProvider.locate(context, DatabaseProvider.POETRY_DB);
        filePresent = mainFile != null;
        if (mainFile == null) {
            ready = false;
            LogUtil.w("poetry.db not found, app will fall back to seed data");
            return;
        }
        try {
            db = SQLiteDatabase.openDatabase(mainFile.getAbsolutePath(), null, OPEN_FLAG);
            ready = true;
            LogUtil.i("poetry.db opened: " + mainFile.getAbsolutePath());
        } catch (Exception e) {
            LogUtil.e("open poetry.db failed", e);
            ready = false;
            return;
        }
        File strainFile = DatabaseProvider.locate(context, DatabaseProvider.STRAIN_DB);
        if (strainFile != null) {
            try {
                strainDb = SQLiteDatabase.openDatabase(strainFile.getAbsolutePath(), null, OPEN_FLAG);
            } catch (Exception e) {
                LogUtil.w("open poetry-strains.db failed", e);
            }
        }
    }

    public boolean isReady() {
        return ready && db != null && db.isOpen();
    }

    /** 磁盘上有没有 {@code poetry.db}。见 {@link #filePresent} 的说明。 */
    public boolean filePresent() {
        return filePresent;
    }

    /** 平仄库是否可用 */
    public boolean hasStrains() {
        return strainDb != null && strainDb.isOpen();
    }

    public synchronized void close() {
        closeLocked();
    }

    // ------------------------------------------------------------ meta

    /** {@code meta} 表里 App 关心的那几行。字段都是 {@code meta} 的原始值，没做解释。 */
    public static final class Meta {
        @Nullable
        public final String schemaVersion;
        @Nullable
        public final String builtAt;

        /**
         * 是不是被裁过的样本库。
         *
         * <p>两个键都算：老管线写 {@code subset='1'}，新管线（3.3）改成 {@code sample='1'}。
         * 完整库里两个键都不存在。
         */
        public final boolean subset;

        /** 库自己声称的篇数，用来和 {@code COUNT(*)} 对照。 */
        public final long nPoems;

        Meta(@Nullable String schemaVersion, @Nullable String builtAt, boolean subset, long nPoems) {
            this.schemaVersion = schemaVersion;
            this.builtAt = builtAt;
            this.subset = subset;
            this.nPoems = nPoems;
        }

        /** 与 {@link #SCHEMA_VERSION} 的主版本号是否一致。 */
        public boolean schemaCompatible() {
            return schemaVersion != null
                    && majorOf(schemaVersion) == majorOf(SCHEMA_VERSION);
        }

        private static int majorOf(@NonNull String version) {
            int dot = version.indexOf('.');
            String head = dot < 0 ? version : version.substring(0, dot);
            try {
                return Integer.parseInt(head.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        @NonNull
        @Override
        public String toString() {
            return "Meta{schema=" + schemaVersion + " builtAt=" + builtAt
                    + " subset=" + subset + " nPoems=" + nPoems + "}";
        }
    }

    /**
     * 从**活着的句柄**读 {@code meta}，读不到返回 null。
     *
     * <p>刻意不复用 {@link #readMetaFrom} 之外另开一个 {@link SQLiteDatabase}：多一个句柄就多一份
     * 「文件被换掉时它握着旧 inode」的机会，而这里读出来的信息正是用来判断该不该换文件的。
     *
     * <p>调用前请确保 {@code ensureOpen()} 已经跑过（在 IO 线程上）。
     */
    @Nullable
    public synchronized Meta readMeta() {
        if (!isReady()) {
            return null;
        }
        return readMetaFrom(db);
    }

    /**
     * 从任意一个 {@code poetry.db} 句柄读 meta。给 {@code DbValidator} 校验尚未安装的
     * {@code .part} 文件用——那时候还不能动活着的那个句柄。
     */
    @Nullable
    public static Meta readMetaFrom(@NonNull SQLiteDatabase handle) {
        String schema = null;
        String builtAt = null;
        // subset 与 sample 是两种子集标记：老管线写 subset=1，新管线改成 sample=1
        // （并额外带 sample_parent / sample_per_group）。对 App 来说它们是同一件事——
        // 「这不是母库，是被裁过的样本」——所以放在同一个布尔里。
        boolean subset = false;
        long nPoems = 0L;
        Cursor c = null;
        try {
            c = handle.rawQuery("SELECT k, v FROM meta", null);
            while (c.moveToNext()) {
                String k = c.getString(0);
                String v = c.getString(1);
                if ("schema_version".equals(k)) {
                    schema = v;
                } else if ("built_at".equals(k)) {
                    builtAt = v;
                } else if ("subset".equals(k) || "sample".equals(k)) {
                    subset = "1".equals(v) || subset;
                } else if ("n_poems".equals(k)) {
                    nPoems = parseLongOrZero(v);
                }
            }
        } catch (Exception e) {
            LogUtil.e("read meta failed", e);
            return null;
        } finally {
            closeQuietly(c);
        }
        return new Meta(schema, builtAt, subset, nPoems);
    }

    private static long parseLongOrZero(@Nullable String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------ 查询：作品

    /**
     * 精选：按热度分取前 N 首（发现页「精选诗词」与 Hero 推荐共用）。
     */
    @NonNull
    public List<Poem> featured(int limit) {
        return featured(limit, 0);
    }

    /** @param offset 跳过的条数，热点榜翻页用（排序带 {@code p.id} 兜底，翻页不会错位） */
    @NonNull
    public List<Poem> featured(int limit, int offset) {
        List<Poem> list = new ArrayList<>();
        if (!isReady()) {
            return list;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT " + POEM_COLUMNS + POEM_FROM
                    + " ORDER BY p.score DESC, p.id ASC LIMIT " + Math.max(1, limit)
                    + " OFFSET " + Math.max(0, offset), null);
            while (c.moveToNext()) {
                list.add(readPoem(c));
            }
        } catch (Exception e) {
            LogUtil.e("featured failed", e);
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
            LogUtil.e("poemById failed", e);
        } finally {
            closeQuietly(c);
        }
        return null;
    }

    /**
     * 按 uid 取一首（本地库侧）。
     *
     * <p>用途是「先联网看了列表、中途断网，再点进详情」这条路径：这时手上只有远端给的
     * uid，而本库的作品表是按 {@code id} 组织的，需要一次换算。
     *
     * <p>注意这**不是**索引查找：导出的诗库只建了主键，没有 {@code uid} 的二级索引，
     * 所以这是一次全表扫描。放在这条兜底路径上可以接受（用户点一次详情才走一次），
     * 但别把它挪到列表或检索里去用。真要高频按 uid 查，得让导出脚本补上
     * {@code CREATE UNIQUE INDEX ... ON poems(uid)}。
     */
    @Nullable
    public Poem poemByUid(@NonNull String uid) {
        if (!isReady() || uid.isEmpty()) {
            return null;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT " + POEM_COLUMNS + POEM_FROM + " WHERE p.uid = ? LIMIT 1",
                    new String[]{uid});
            if (c.moveToFirst()) {
                return readPoem(c);
            }
        } catch (Exception e) {
            LogUtil.e("poemByUid failed", e);
        } finally {
            closeQuietly(c);
        }
        return null;
    }

    /**
     * 全库随机取一首。
     *
     * <p>不能写 {@code ORDER BY RANDOM()}：34 万行会全表扫描再排序，慢到不能用。
     * {@code poems.id} 是 rowid 别名，于是先在 {@code [1, MAX(id)]} 里随机取一个起点，
     * 再取「id 不小于这个起点的第一条」——两次都是索引查找，O(log n)。
     * id 有空洞时各首被抽中的概率会有偏移（空洞后面那首更容易中），
     * 对「换一首看看」这个用途足够了。
     *
     * <p>只从 {@link #MAX_RANDOM_N_CHAR} 字以内的作品里抽：「古文观止」（14 万字）、
     * 「唐诗三百首」这类蒙学/选集整本也算一首，点开就是一屏几千行，不适合当随机推荐。
     * 它们的 score 都是 0，{@link #featured} 天然把它们排在后面，只有随机这条路会撞上。
     *
     * @param excludeId 尽量避开的那首（≤ 0 表示不排除）
     */
    @Nullable
    public Poem randomPoem(long excludeId) {
        if (!isReady()) {
            return null;
        }
        Poem poem = randomPoemOnce();
        // 撞上同一首就再抽一次；全库只剩一首时第二次还是它，认了
        if (poem != null && poem.getId() == excludeId) {
            Poem again = randomPoemOnce();
            if (again != null) {
                poem = again;
            }
        }
        if (poem != null && hasStrains()) {
            poem.setStrain(strainOf(poem.getId()));
        }
        return poem;
    }

    /**
     * 随机取一首的裸查询，不做任何排除。
     *
     * <p>{@code RANDOM() % MAX(id)} 而不是 {@code ABS(RANDOM()) % MAX(id)}：
     * {@code ABS(-9223372036854775808)} 在 SQLite 里会原样返回负数，
     * 先取模再取绝对值就没有这个坑（模数最大 34 万，结果不可能是 int64 最小值）。
     */
    @Nullable
    private Poem randomPoemOnce() {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT " + POEM_COLUMNS + POEM_FROM
                    + " WHERE p.id >= ABS(RANDOM() % (SELECT MAX(id) FROM poems)) + 1"
                    + " AND p.n_char <= " + MAX_RANDOM_N_CHAR
                    + " ORDER BY p.id ASC LIMIT 1", null);
            if (c.moveToFirst()) {
                return readPoem(c);
            }
            // 两个兜底合成一个：MAX(id) 是 NULL（空表）时上面的比较恒为 NULL，
            // 随机起点之后又恰好没有短篇时也会空手而归，都退到「第一首短篇」。
            closeQuietly(c);
            c = db.rawQuery("SELECT " + POEM_COLUMNS + POEM_FROM
                    + " WHERE p.n_char <= " + MAX_RANDOM_N_CHAR
                    + " ORDER BY p.id ASC LIMIT 1", null);
            if (c.moveToFirst()) {
                return readPoem(c);
            }
        } catch (Exception e) {
            LogUtil.e("randomPoem failed", e);
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
            LogUtil.e("query failed: " + sql, e);
        } finally {
            closeQuietly(c);
        }
    }

    // ------------------------------------------------------------ 查询：分类过滤

    @NonNull
    public List<Poem> listByKind(@NonNull PoemKind kind, int limit) {
        return listByKind(kind, limit, 0);
    }

    /**
     * @param offset 跳过的条数。翻页靠 {@code LIMIT/OFFSET}，前提是排序**全序**：
     *               只按 {@code p.score DESC} 排的话，同分的条目在两次查询里可能
     *               换位置，翻页就会重复或漏掉。所以后面补了 {@code p.id ASC} 兜底
     *               —— 它唯一，整条 ORDER BY 因此没有平局。
     */
    @NonNull
    public List<Poem> listByKind(@NonNull PoemKind kind, int limit, int offset) {
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
                            + " ORDER BY p.score DESC, p.id ASC LIMIT " + Math.max(1, limit)
                            + " OFFSET " + Math.max(0, offset),
                    sources);
            while (c.moveToNext()) {
                out.add(readPoem(c));
            }
        } catch (Exception e) {
            LogUtil.e("listByKind failed", e);
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    @NonNull
    public List<Poem> listByDynasty(@NonNull String dynastyCode, int limit) {
        return listByDynasty(dynastyCode, limit, 0);
    }

    @NonNull
    public List<Poem> listByDynasty(@NonNull String dynastyCode, int limit, int offset) {
        List<Poem> out = new ArrayList<>();
        if (!isReady()) {
            return out;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT " + POEM_COLUMNS + POEM_FROM
                            + " WHERE a.dynasty = ? ORDER BY p.score DESC, p.id ASC LIMIT "
                            + Math.max(1, limit) + " OFFSET " + Math.max(0, offset),
                    new String[]{dynastyCode});
            while (c.moveToNext()) {
                out.add(readPoem(c));
            }
        } catch (Exception e) {
            LogUtil.e("listByDynasty failed", e);
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    @NonNull
    public List<Poem> listByAuthor(long authorId, int limit) {
        return listByAuthor(authorId, limit, 0);
    }

    @NonNull
    public List<Poem> listByAuthor(long authorId, int limit, int offset) {
        List<Poem> out = new ArrayList<>();
        if (!isReady()) {
            return out;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT " + POEM_COLUMNS + POEM_FROM
                            + " WHERE p.author_id = ? ORDER BY p.score DESC, p.id ASC LIMIT "
                            + Math.max(1, limit) + " OFFSET " + Math.max(0, offset),
                    new String[]{String.valueOf(authorId)});
            while (c.moveToNext()) {
                out.add(readPoem(c));
            }
        } catch (Exception e) {
            LogUtil.e("listByAuthor failed", e);
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
            LogUtil.e("totalCount failed", e);
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
            LogUtil.e("countOfKind failed", e);
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
            // 现算 COUNT(*) 而不是 SUM(authors.n_poems)：后者记的是母库篇数，会大出两个数量级。
            // 空朝代（佚名，poems.author_id = 0，authors 里没有这一行）归到 unknown，
            // 否则这 169 首会在宫格里凭空消失。
            c = db.rawQuery("SELECT COALESCE(NULLIF(a.dynasty, ''), 'unknown') AS dynasty,"
                    + " COUNT(*) FROM poems p LEFT JOIN authors a ON a.id = p.author_id"
                    + " GROUP BY 1", null);
            while (c.moveToNext()) {
                counts.put(c.getString(0), c.getLong(1));
            }
        } catch (Exception e) {
            LogUtil.e("dynastyCategories failed", e);
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
        Long cached = themeCountCache.get(keyword);
        if (cached != null) {
            return cached;
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
            LogUtil.e("approxThemeCount failed", e);
        } finally {
            closeQuietly(c);
        }
        themeCountCache.put(keyword, count);
        return count;
    }

    /**
     * 热门作者（按作品数）。
     *
     * <p>篇数一律现算：{@code authors.n_poems} 存的是母库篇数（全库 SUM 13 万，而本库只有 978 首），
     * 直接拿来排序会让「陆游 9416 首」这种数字出现在只有 7 首的列表上。
     * 内连接顺带把一首作品都没有的作者排除掉。
     */
    @NonNull
    public List<Author> topAuthors(int limit) {
        List<Author> out = new ArrayList<>();
        if (!isReady()) {
            return out;
        }
        Cursor c = null;
        try {
            // uid 一并选出来：作者详情页要拿它去后端查作品，
            // 而 poems.id 是母库 ROWID，换一次库就错位，不能当跨端标识用。
            c = db.rawQuery("SELECT a.id, a.uid, a.name, a.dynasty, a.desc, COUNT(p.id) AS n_poems"
                    + " FROM authors a JOIN poems p ON p.author_id = a.id"
                    + " GROUP BY a.id, a.uid ORDER BY n_poems DESC, a.id ASC LIMIT " + Math.max(1, limit), null);
            while (c.moveToNext()) {
                Author author = new Author();
                author.setId(c.getLong(0));
                author.setUid(str(c, "uid"));
                author.setName(str(c, "name"));
                author.setDynasty(str(c, "dynasty"));
                author.setDesc(str(c, "desc"));
                author.setNPoems(c.getInt(5));
                out.add(author);
            }
        } catch (Exception e) {
            LogUtil.e("topAuthors failed", e);
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    /**
     * 某作者在本库的实际篇数。
     *
     * <p>给作者详情页兜底用：从「热门作者」进来时篇数由 {@link #topAuthors} 透传，
     * 但从其它入口（列表按作者筛选）进来时没带，得现查一次。
     */
    public int countByAuthor(long authorId) {
        if (!isReady()) {
            return 0;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM poems WHERE author_id = ?",
                    new String[]{String.valueOf(authorId)});
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Exception e) {
            LogUtil.e("countByAuthor failed", e);
        } finally {
            closeQuietly(c);
        }
        return 0;
    }

    /**
     * 按 uid 取作者（本地库侧）。
     *
     * <p>给「后端不可用、但手上只有 uid」的场景兜底：接口一律按 uid 传作者标识，
     * 而本库的作品表是按数字 {@code author_id} 关联的，中间需要这一步换算。
     * 篇数口径与 {@link #topAuthors} 保持一致——现算 {@code COUNT(*)}，
     * 绝不读 {@code authors.n_poems}（那是母库篇数）。
     */
    @Nullable
    public Author authorByUid(@NonNull String uid) {
        if (!isReady() || uid.isEmpty()) {
            return null;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT a.id, a.uid, a.name, a.dynasty, a.desc,"
                    + " (SELECT COUNT(*) FROM poems p WHERE p.author_id = a.id) AS n_poems"
                    + " FROM authors a WHERE a.uid = ? LIMIT 1", new String[]{uid});
            if (c.moveToFirst()) {
                Author author = new Author();
                author.setId(c.getLong(0));
                author.setUid(str(c, "uid"));
                author.setName(str(c, "name"));
                author.setDynasty(str(c, "dynasty"));
                author.setDesc(str(c, "desc"));
                author.setNPoems(c.getInt(5));
                return author;
            }
        } catch (Exception e) {
            LogUtil.e("authorByUid failed", e);
        } finally {
            closeQuietly(c);
        }
        return null;
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
            LogUtil.w("strainOf failed", e);
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
        poem.setUid(str(c, "uid"));
        poem.setAuthorId(c.getLong(c.getColumnIndexOrThrow("author_id")));
        poem.setTitle(str(c, "title"));
        poem.setSrcName(str(c, "src_name"));
        poem.setBody(str(c, "body"));
        poem.setTags(str(c, "tags"));
        poem.setScore(c.getInt(c.getColumnIndexOrThrow("score")));
        poem.setNChar(c.getInt(c.getColumnIndexOrThrow("n_char")));
        poem.setAuthorName(str(c, "author_name"));
        poem.setDynasty(str(c, "author_dynasty"));
        poem.setRhythmic(str(c, "rhythmic_name"));
        // 富文本来自 poem_extras：没有对应行时左连接给的是 NULL，统一折成空串，
        // 免得调用方（详情页各处都是 xxx.isEmpty()）碰见 null。
        poem.setTranslation(str(c, "translation"));
        poem.setNotes(str(c, "notes"));
        poem.setIntroduction(str(c, "introduction"));
        poem.setCreativeBackground(str(c, "creative_background"));
        poem.setAppreciation(str(c, "appreciation"));
        return poem;
    }

    /** 取字符串列，NULL 折成空串。 */
    @NonNull
    private static String str(@NonNull Cursor c, @NonNull String column) {
        String value = c.getString(c.getColumnIndexOrThrow(column));
        return value == null ? "" : value;
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

package com.example.poetry.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.local.DatabaseProvider;
import com.example.poetry.data.local.DbInstaller;
import com.example.poetry.data.local.PoetryDatabase;
import com.example.poetry.data.local.SeedDataSource;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.Author;
import com.example.poetry.data.model.Category;
import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.PoemKind;
import com.example.poetry.data.remote.ApiConfig;
import com.example.poetry.data.remote.ApiException;
import com.example.poetry.data.remote.PoetryApi;
import com.example.poetry.data.remote.RemotePoetrySource;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 统一数据入口（离线优先）。
 * <p>
 * 读取优先级：
 * <ol>
 *   <li>本地诗库 {@code poetry.db}（可用时）；</li>
 *   <li>内置占位数据 {@link SeedDataSource}（库缺失时兜底，保证 UI 永远有内容）；</li>
 *   <li>后台接口 {@link PoetryApi}（{@code ApiConfig.ENABLED} 打开后才参与，
 *       目前只用于译文 / 赏析 / 每日推荐等本地库没有的富文本内容）。</li>
 * </ol>
 * 所有查询都在 IO 线程执行，回调回到主线程。
 */
public final class PoetryRepository {

    private static volatile PoetryRepository instance;

    private final Context context;
    private final PoetryDatabase database;
    private final UserStore store;
    private final PoetryApi remote;

    /** 诗库状态的订阅者。CopyOnWrite 是因为投递时可能有人正在 onDestroyView 里摘自己。 */
    private final CopyOnWriteArrayList<DbStatusListener> dbListeners = new CopyOnWriteArrayList<>();

    /** 最近一次发布的快照。注册时会立刻拿到它，所以它必须在任何一次注册之前就有值。 */
    private volatile DbStatus dbStatus = DbStatus.idle(false, false);

    /** 下载安装状态机。应用级单例，这里只是把它的事件转成 {@link DbStatus} 往外发。 */
    private final DbInstaller installer;

    /**
     * 把 {@code DbInstaller} 的事件转给本仓库的订阅者。
     *
     * <p>转发是**直接转**：{@code DbInstaller} 造快照时已经问过库了，这里再包一层
     * 只会多一次字段读取和一层可能出错的翻译。
     */
    private final DbInstaller.Listener installerBridge = this::publish;

    private PoetryRepository(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.database = PoetryDatabase.get(this.context);
        this.store = UserStore.get(this.context);
        this.remote = new RemotePoetrySource();
        this.installer = DbInstaller.get(this.context);
        // 注册即回调一次当前状态，所以 dbStatus 在这之后就反映真实情况了，
        // 不必等第一次查询的 publishReadiness。
        this.installer.addListener(installerBridge);
    }

    public static PoetryRepository get(@NonNull Context context) {
        if (instance == null) {
            synchronized (PoetryRepository.class) {
                if (instance == null) {
                    instance = new PoetryRepository(context);
                }
            }
        }
        return instance;
    }

    // ------------------------------------------------------------ 状态

    /**
     * 本地全量诗库是否可用。
     *
     * <p>只读判断，不会去打开数据库：打开可能要先把 56 MB 从 assets 拷到内部存储，
     * 而这方法会在主线程被调用。真正接通数据库的事交给 {@code ensureOpen()}，
     * 它在每次查询前的 IO 线程里执行。
     *
     * <p>注意这是「此刻开着吗」而不是「该不该显示本地库」——界面想知道后者，
     * 应该订阅 {@link #addDbStatusListener}，因为库是下载下来之后才可用的。
     */
    public boolean isLocalReady() {
        return database.isReady();
    }

    /**
     * 同上，但会先尝试接通数据库，结果在主线程返回——供「我的」页显示真实数据状态用。
     */
    public void localState(@NonNull Callback<Boolean> callback) {
        AppExecutors.get().io(() -> {
            database.ensureOpen();
            boolean localReady = database.isReady();
            AppExecutors.get().main(() -> callback.onData(localReady));
        });
    }

    // ------------------------------------------------------------ 诗库状态订阅

    /**
     * 订阅诗库状态变化。**注册时会立刻收到一次当前状态**，界面因此不需要在
     * {@code onViewCreated} 里额外查一遍。
     *
     * <p>只放在 {@code onViewCreated} / {@code onCreate} 里，别放进会被 {@code onResume}
     * 重复调用的 {@code setupX()}——那样每转一次屏泄漏一个监听器。
     */
    public void addDbStatusListener(@NonNull DbStatusListener listener) {
        dbListeners.addIfAbsent(listener);
        publish(listener, dbStatus);
    }

    /** 与 {@link #addDbStatusListener} 严格配对，放 {@code onDestroyView} / {@code onDestroy}。 */
    public void removeDbStatusListener(@NonNull DbStatusListener listener) {
        dbListeners.remove(listener);
    }

    /** 当前状态快照。不碰磁盘，可以在主线程随便调。 */
    @NonNull
    public DbStatus dbStatus() {
        return dbStatus;
    }

    /**
     * 去服务器问一下有没有新诗库。
     *
     * @param force  true = 绕过节流（「关于」页那个按钮）
     * @param result 可空。{@code onData(true)} = 已是最新，{@code onData(false)} = 有新版可下。
     *               无论哪种，界面都会先通过 {@link #addDbStatusListener} 收到状态变化。
     */
    public void checkForDbUpdate(boolean force, @Nullable Callback<Boolean> result) {
        installer.checkForUpdate(force, result);
    }

    /**
     * 开始下载并安装诗库。
     *
     * <p>不受界面生命周期影响——这是应用级操作，切个标签页不该把它掐掉。
     * 只有 {@link #cancelDbDownload()} 或用户点「取消」能停下它。
     */
    public void startDbDownload() {
        installer.startDownload();
    }

    /** 取消下载。碎片保留在磁盘上，下次续传。 */
    public void cancelDbDownload() {
        installer.cancelDownload();
    }

    /**
     * 发布新状态：先记下来（后注册的人立刻能拿到），再投给所有订阅者。
     *
     * <p>投递统一走 {@code AppExecutors.main}：状态可能来自下载线程，而订阅者全是界面。
     * 已经在主线程时它是内联执行，不会多绕一圈消息队列。
     */
    private void publish(@NonNull final DbStatus status) {
        dbStatus = status;
        for (DbStatusListener listener : dbListeners) {
            publish(listener, status);
        }
    }

    private void publish(@NonNull final DbStatusListener listener, @NonNull final DbStatus status) {
        AppExecutors.get().main(() -> listener.onDbStatus(status));
    }

    /**
     * 由 IO 线程调用：库刚被打开（或发现打不开）时把「能用了」这件事广播出去。
     *
     * <p>下载/安装进行中时直接跳过，那种时候状态由 {@code DbInstaller} 说了算——它会带着
     * 更新后的标志位再发一次。不跳过的话，安装途中的一次查询就可能把 DOWNLOADING 冲成 IDLE，
     * 界面上的进度条会莫名其妙消失。
     */
    private void publishReadiness(boolean localReady, boolean hasFile) {
        DbStatus current = dbStatus;
        if (current.busy()) {
            return;
        }
        if (current.localReady == localReady && current.hasFile == hasFile) {
            return;   // 没变化就别打扰界面，否则每次查询都会触发一轮重绘
        }
        publish(DbStatus.idle(localReady, hasFile));
    }

    /** 平仄库是否可用 */
    public boolean hasStrains() {
        return database.hasStrains();
    }

    /** 数据来源说明（展示在「我的」页） */
    @NonNull
    public String sourceLabel() {
        return database.isReady() ? "本地诗库 poetry.db" : "内置示例数据";
    }

    /** 诗库推荐投放目录 */
    @NonNull
    public String recommendDbDir() {
        File dir = DatabaseProvider.recommendDir(context);
        return dir == null ? "" : dir.getAbsolutePath();
    }

    public long totalCount() {
        return database.isReady() ? database.totalCount() : SeedDataSource.all().size();
    }

    @NonNull
    public UserStore store() {
        return store;
    }

    @NonNull
    public PoetryApi remote() {
        return remote;
    }

    // ------------------------------------------------------------ 作品

    public void featured(int limit, @NonNull Callback<List<Poem>> callback) {
        run(() -> database.isReady()
                ? database.featured(limit)
                : SeedDataSource.featured(limit), callback);
    }

    /** 每日推荐（Hero 卡） */
    public void daily(@NonNull Callback<Poem> callback) {
        run(() -> {
            if (database.isReady()) {
                Poem poem = database.randomFeatured();
                if (poem != null) {
                    return poem;
                }
            }
            List<Poem> seed = SeedDataSource.featured(1);
            return seed.isEmpty() ? null : seed.get(0);
        }, callback);
    }

    public void poemById(long id, @NonNull Callback<Poem> callback) {
        run(() -> {
            Poem poem = database.isReady() ? database.poemById(id) : null;
            if (poem == null) {
                poem = SeedDataSource.poemById(id);
            }
            if (poem != null && database.hasStrains()) {
                poem.setStrain(database.strainOf(poem.getId()));
            }
            return poem;
        }, callback);
    }

    /**
     * 全库随机取一首，尽量避开 {@code excludeId}（详情页「随机换一首」用）。
     *
     * <p>种子库回退必须写在 Callable 里：{@link #run} 把 null 结果当成错误
     * （转成 {@code onError(IllegalStateException)}），在外面兜就太晚了。
     */
    public void randomPoem(long excludeId, @NonNull Callback<Poem> callback) {
        run(() -> {
            Poem poem = database.isReady() ? database.randomPoem(excludeId) : null;
            if (poem == null) {
                poem = randomSeed(excludeId);
            }
            if (poem != null && database.hasStrains()) {
                poem.setStrain(database.strainOf(poem.getId()));
            }
            return poem;
        }, callback);
    }

    /** 种子库里的随机兜底（本地库还没搬好时用）。 */
    @Nullable
    private static Poem randomSeed(long excludeId) {
        List<Poem> all = SeedDataSource.all();
        if (all.isEmpty()) {
            return null;
        }
        int start = ThreadLocalRandom.current().nextInt(all.size());
        for (int i = 0; i < all.size(); i++) {
            Poem poem = all.get((start + i) % all.size());
            // 从头绕一圈找第一首不是 excludeId 的；只有一首时它就是 excludeId，认了
            if (poem.getId() != excludeId) {
                return poem;
            }
        }
        return all.get(start);
    }

    public void search(@NonNull String keyword, int limit, @NonNull Callback<List<Poem>> callback) {
        run(() -> database.isReady()
                ? database.search(keyword, limit)
                : SeedDataSource.search(keyword, limit), callback);
    }

    // ------------------------------------------------------------ 分类

    public void kindCategories(@NonNull Callback<List<Category>> callback) {
        run(() -> database.isReady()
                ? database.kindCategories()
                : SeedDataSource.kindCategories(), callback);
    }

    public void dynastyCategories(@NonNull Callback<List<Category>> callback) {
        run(() -> database.isReady()
                ? database.dynastyCategories()
                : SeedDataSource.dynastyCategories(), callback);
    }

    public void themeCategories(@NonNull Callback<List<Category>> callback) {
        run(() -> database.isReady()
                ? database.themeCategories()
                : SeedDataSource.themeCategories(), callback);
    }

    public void topAuthors(int limit, @NonNull Callback<List<Author>> callback) {
        run(() -> database.isReady()
                ? database.topAuthors(limit)
                : SeedDataSource.topAuthors(limit), callback);
    }

    public void listByKind(@NonNull String kindCode, int limit, @NonNull Callback<List<Poem>> callback) {
        run(() -> {
            PoemKind kind = PoemKind.fromCode(kindCode);
            return database.isReady()
                    ? database.listByKind(kind, limit)
                    : SeedDataSource.listByKind(kind, limit);
        }, callback);
    }

    public void listByDynasty(@NonNull String dynastyCode, int limit, @NonNull Callback<List<Poem>> callback) {
        run(() -> database.isReady()
                ? database.listByDynasty(dynastyCode, limit)
                : SeedDataSource.listByDynasty(dynastyCode, limit), callback);
    }

    public void listByAuthor(long authorId, int limit, @NonNull Callback<List<Poem>> callback) {
        run(() -> database.isReady()
                ? database.listByAuthor(authorId, limit)
                : SeedDataSource.listByAuthor(authorId, limit), callback);
    }

    public void listByTheme(@NonNull String keyword, int limit, @NonNull Callback<List<Poem>> callback) {
        run(() -> database.isReady()
                ? database.listByTheme(keyword, limit)
                : SeedDataSource.search(keyword, limit), callback);
    }

    // ------------------------------------------------------------ 搜索词 / 平仄

    public void hotKeywords(@NonNull Callback<List<String>> callback) {
        if (ApiConfig.ENABLED) {
            AppExecutors.get().io(() -> remote.getHotKeywords(
                    new com.example.poetry.data.remote.ApiCallback<List<String>>() {
                        @Override
                        public void onSuccess(@NonNull List<String> data) {
                            AppExecutors.get().main(() -> callback.onData(data));
                        }

                        @Override
                        public void onFailure(@NonNull ApiException error) {
                            fallbackHotKeywords(callback);
                        }
                    }));
            return;
        }
        fallbackHotKeywords(callback);
    }

    private void fallbackHotKeywords(@NonNull Callback<List<String>> callback) {
        List<String> words = new ArrayList<>(
                Arrays.asList(PoetryDatabase.HOT_KEYWORDS));
        callback.onData(words);
    }

    /** 平仄串：本地库没有时返回空串 */
    public void strain(long poemId, @NonNull Callback<String> callback) {
        run(() -> database.hasStrains() ? database.strainOf(poemId) : "", callback);
    }

    // ------------------------------------------------------------ 富文本（后台预留）

    /**
     * 译文：后台可用时走接口，否则取本地 notes / 内置译文，都没有则返回空串。
     */
    public void translation(long poemId, @NonNull Callback<String> callback) {
        if (ApiConfig.ENABLED) {
            AppExecutors.get().io(() -> remote.getTranslation(poemId,
                    new com.example.poetry.data.remote.ApiCallback<String>() {
                        @Override
                        public void onSuccess(@NonNull String data) {
                            AppExecutors.get().main(() -> callback.onData(data));
                        }

                        @Override
                        public void onFailure(@NonNull ApiException error) {
                            localTranslation(poemId, callback);
                        }
                    }));
            return;
        }
        localTranslation(poemId, callback);
    }

    private void localTranslation(long poemId, @NonNull Callback<String> callback) {
        run(() -> {
            Poem poem = resolve(poemId);
            return poem == null ? "" : poem.getTranslation();
        }, callback);
    }

    /** 赏析：同上 */
    public void appreciation(long poemId, @NonNull Callback<String> callback) {
        run(() -> {
            Poem poem = resolve(poemId);
            return poem == null ? "" : poem.getAppreciation();
        }, callback);
    }

    @Nullable
    private Poem resolve(long poemId) {
        Poem poem = database.isReady() ? database.poemById(poemId) : null;
        if (poem == null) {
            poem = SeedDataSource.poemById(poemId);
        }
        return poem;
    }

    // ------------------------------------------------------------ 用户数据（同步）

    @NonNull
    public List<Poem> favorites() {
        return store.getFavorites();
    }

    public boolean isFavorite(long poemId) {
        return store.isFavorite(poemId);
    }

    public boolean toggleFavorite(@NonNull Poem poem) {
        return store.toggleFavorite(poem);
    }

    public void removeFavorite(long poemId) {
        store.removeFavorite(poemId);
    }

    public void recordHistory(@NonNull Poem poem) {
        store.recordHistory(poem);
    }

    @NonNull
    public List<Poem> history() {
        return store.getHistory();
    }

    public void removeHistory(long poemId) {
        store.removeHistory(poemId);
    }

    // ------------------------------------------------------------ 线程封装

    private <T> void run(@NonNull Callable<T> task, @NonNull Callback<T> callback) {
        AppExecutors.get().io(() -> {
            try {
                // Pick up the database once the background warm-up has copied it out of
                // the assets. Safe here: this is the IO thread, never the main one.
                database.ensureOpen();
                // 每一次查询都是一次「库现在能用了吗」的顺带检查：首次查询会把库打开，
                // 下载装好后的一次 reload() 也会在这里被下一轮查询确认。状态真的变了才广播。
                publishReadiness(database.isReady(), database.filePresent());
                T data = task.call();
                AppExecutors.get().main(() -> {
                    if (data != null) {
                        callback.onData(data);
                    } else {
                        callback.onError(new IllegalStateException("empty result"));
                    }
                });
            } catch (Exception e) {
                AppExecutors.get().main(() -> callback.onError(e));
            }
        });
    }
}

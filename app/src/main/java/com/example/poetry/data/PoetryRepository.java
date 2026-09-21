package com.example.poetry.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.local.DatabaseProvider;
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

    private PoetryRepository(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.database = PoetryDatabase.get(this.context);
        this.store = UserStore.get(this.context);
        this.remote = new RemotePoetrySource();
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

    /** 本地全量诗库是否可用 */
    public boolean isLocalReady() {
        return database.isReady();
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

    // ------------------------------------------------------------ 线程封装

    private <T> void run(@NonNull Callable<T> task, @NonNull Callback<T> callback) {
        AppExecutors.get().io(() -> {
            try {
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

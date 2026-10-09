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
import com.example.poetry.data.model.Voice;
import com.example.poetry.data.remote.ApiException;
import com.example.poetry.data.remote.HttpPoetryApi;
import com.example.poetry.data.remote.LoginResult;
import com.example.poetry.data.remote.PoetryApi;
import com.example.poetry.util.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 统一数据入口。
 *
 * <h3>取数顺序：远端 → 本地库 → 内置示例</h3>
 *
 * <ol>
 *   <li><b>后端接口</b>（{@link PoetryApi}）—— 有网、未被熔断时的首选。
 *       内容永远是最新的，用户不必先下 56 MB 的诗库才能用；</li>
 *   <li><b>本地诗库</b> {@code poetry.db} —— 无网、后端故障、或正在熔断冷却时接管。
 *       这是「离线也能正常读」的保障，也是后端还没部署时唯一的真实数据源；</li>
 *   <li><b>内置占位数据</b> {@link SeedDataSource} —— 连诗库都没有时的最后一层，
 *       只为保证界面上永远有东西，而不是一片空白。</li>
 * </ol>
 *
 * <p>回退是**逐次**的，不是一次性的：这一次查询失败了不代表下一次也失败，
 * 所以每一层都会重新判断。真正让「后端挂了」不至于拖慢界面的机制在
 * {@code ApiClient} 的熔断里（连续失败几次后直接短路，不再等超时）。
 *
 * <h3>threading</h3>
 * 所有查询都在 IO 线程执行，回调回到主线程。远端调用是阻塞的
 * （见 {@link PoetryApi} 的说明），正因如此这里的回退才写得出直线代码。
 */
public final class PoetryRepository {

    /** 一次查询实际用的是哪一层数据 */
    public enum Source {
        /** 后端接口 */
        REMOTE,
        /** 本地诗库 poetry.db */
        LOCAL,
        /** 内置示例数据 */
        SEED,
        /** 还没查过任何东西 */
        NONE
    }

    /** 书架一次最多上行多少条。与后端 {@code SyncLibrary} 的上限一致。 */
    private static final int SYNC_LIMIT = 200;

    private static volatile PoetryRepository instance;

    private final Context context;
    private final PoetryDatabase database;
    private final UserStore store;
    private final PoetryApi remote;

    /** 最近一次查询用的数据源。只用于「关于」页展示，读写都在 IO/主线之间，加了 volatile。 */
    private volatile Source lastSource = Source.NONE;

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
        this.remote = new HttpPoetryApi(this.context);
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
     * 而这方法会在主线程被调用。真正接通数据库的事交给 {@link #warmUpLocal()} 与
     * 各查询的本地分支，它们都在后台线程。
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

    /**
     * 接通本地诗库 —— **在调用线程上同步做完**，不返回回调。
     *
     * <p>为什么要单独留这么一个入口：查询现在优先走后端，只有真的回退才需要库，
     * 于是「没有任何一次查询回退」时库就永远不会被打开，「我的」页的诗库状态
     * 会一直停在「未就绪」。让启动时无条件接通它，就与走哪条取数路径无关了。
     *
     * <p>为什么是同步而不是丢给 {@link AppExecutors#io}：那个池是**单线程**的，
     * 同一份池还要服务每一次查询。首次接通要先把 56 MB 从 assets 拷进内部存储，
     * 放进池里就等于让冷启动那几秒里所有远端查询都排在拷贝后面。
     * 调用方（{@code PoetryApp} 的预热线程）自己安排线程，这里只管干活。
     */
    public void warmUpLocal() {
        database.ensureOpen();
        publishReadiness(database.isReady(), database.filePresent());
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
     */
    public void checkForDbUpdate(boolean force, @Nullable Callback<Boolean> result) {
        installer.checkForUpdate(force, result);
    }

    /**
     * 开始下载并安装诗库。
     *
     * <p>不受界面生命周期影响——这是应用级操作，切个标签页不该把它掐掉。
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

    /** 平仄库是否可用（只有本地包才有；后端的平仄是随详情一起回来的） */
    public boolean hasStrains() {
        return database.hasStrains();
    }

    // ------------------------------------------------------------ 来源说明

    /** 最近一次查询实际用的数据源 */
    @NonNull
    public Source lastSource() {
        return lastSource;
    }

    /** 数据来源说明（展示在「我的」/「关于」页） */
    @NonNull
    public String sourceLabel() {
        switch (lastSource) {
            case REMOTE:
                return "云端诗库";
            case LOCAL:
                return "本地诗库 poetry.db";
            case SEED:
                return "内置示例数据";
            default:
                break;
        }
        // 还没查过：按当前能用的东西给一个说法
        if (remote.isReachableNow()) {
            return "云端诗库";
        }
        return database.isReady() ? "本地诗库 poetry.db" : "内置示例数据";
    }

    /** 后端链路的当前状态，给人看的（「关于」页那一行） */
    @NonNull
    public String apiStatusLabel() {
        return remote.statusLabel();
    }

    /** 诗库推荐投放目录 */
    @NonNull
    public String recommendDbDir() {
        File dir = DatabaseProvider.recommendDir(context);
        return dir == null ? "" : dir.getAbsolutePath();
    }

    /**
     * 本地库的篇数。
     *
     * <p>刻意**不**走后端：这个方法返回 {@code long} 而不是回调，说明它是给主线程直接读的，
     * 而远端调用是阻塞的。正因如此它只反映本机库的规模 —— 想知道**服务端**有多少篇，
     * 走 {@link #remote()} 的 {@code totalCount()}。
     */
    public long totalCount() {
        return database.isReady() ? database.totalCount() : SeedDataSource.all().size();
    }

    @NonNull
    public UserStore store() {
        return store;
    }

    /** 后端接口（音色、合成、登录等直接调用，见 {@link PoetryApi}） */
    @NonNull
    public PoetryApi remote() {
        return remote;
    }

    // ------------------------------------------------------------ 作品

    /** 热门榜（发现页 / 「热门」入口） */
    public void featured(int limit, @NonNull Callback<List<Poem>> callback) {
        run(() -> fetch("featured",
                () -> remote.featured(1, limit),
                () -> database.isReady()
                        ? database.featured(limit)
                        : SeedDataSource.featured(limit)), callback);
    }

    /**
     * 每日推荐（Hero 卡）。
     *
     * <p>{@code exclude} 传的是当前这首的 uid —— 后端的 daily 是按 uid 排除的
     * （见 {@link PoetryApi#daily}）。本地路径按 id 排除，两者互不影响。
     */
    public void daily(@Nullable String excludeRef, @NonNull Callback<Poem> callback) {
        run(() -> fetch("daily",
                () -> remote.daily(excludeRef),
                () -> {
                    if (database.isReady()) {
                        Poem poem = database.randomFeatured();
                        if (poem != null) {
                            return poem;
                        }
                    }
                    List<Poem> seed = SeedDataSource.featured(1);
                    return seed.isEmpty() ? null : seed.get(0);
                }), callback);
    }

    /** @deprecated 用 {@link #daily(String, Callback)}，带上要排除的那首 */
    @Deprecated
    public void daily(@NonNull Callback<Poem> callback) {
        daily(null, callback);
    }

    /**
     * 按引用取完整详情。
     *
     * <p>{@code ref} 用 {@link Poem#getLookupRef()}：有 uid 就是 uid，没有就是数字 id。
     * 后端两者都认，所以换库、换设备之后同一个 ref 依然指向同一首。
     */
    public void poemByRef(@NonNull String ref, @NonNull Callback<Poem> callback) {
        run(() -> fetch("poem:" + ref,
                () -> remote.detail(ref),
                () -> localPoem(ref)), callback);
    }

    /**
     * 全库随机取一首，尽量避开 {@code excludeRef}（详情页「随机换一首」用）。
     *
     * <p>种子库回退必须写在 Callable 里：{@link #run} 把 null 结果当成错误
     * （转成 {@code onError(IllegalStateException)}），在外面兜就太晚了。
     */
    public void randomPoem(@Nullable String excludeRef, @NonNull Callback<Poem> callback) {
        run(() -> fetch("random",
                () -> remote.random(excludeRef),
                () -> {
                    // 排除键必须换算成**本地**的 id：excludeRef 多半是 uid，
                    // 直接 idOf() 会折成 0，于是「换一首」可能抽回同一首
                    long excludeId = localId(excludeRef);
                    Poem poem = database.isReady() ? database.randomPoem(excludeId) : null;
                    return poem != null ? poem : randomSeed(excludeId);
                }), callback);
    }

    /** 种子库里的随机兜底（本地库还没搬好、又没有后端时用）。 */
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
        run(() -> fetch("search",
                () -> remote.search(keyword, 1, limit),
                () -> database.isReady()
                        ? database.search(keyword, limit)
                        : SeedDataSource.search(keyword, limit)), callback);
    }

    // ------------------------------------------------------------ 分类

    public void kindCategories(@NonNull Callback<List<Category>> callback) {
        run(() -> fetch("kindCategories",
                () -> remote.kindCategories(),
                () -> database.kindCategories()), callback);
    }

    public void dynastyCategories(@NonNull Callback<List<Category>> callback) {
        run(() -> fetch("dynastyCategories",
                () -> remote.dynastyCategories(),
                () -> database.dynastyCategories()), callback);
    }

    /**
     * 主题宫格。
     *
     * <p>**只走本地**：这 12 个主题是设计稿写死的（见 {@code Theme.defaults()}），
     * 靠关键词在正文里 LIKE 检索。后端有真实的标签体系（{@code /v1/tags}），
     * 但那是另一套分类，两边混用会让同一张宫格在线和离线时长得不一样，
     * 而「同一个界面在两台设备上不同」是很难向用户解释的。
     * 等确定要以后端标签为准时，改这一个方法即可。
     */
    public void themeCategories(@NonNull Callback<List<Category>> callback) {
        run(() -> {
            database.ensureOpen();
            markSource(database.isReady() ? Source.LOCAL : Source.SEED);
            return database.isReady()
                    ? database.themeCategories()
                    : SeedDataSource.themeCategories();
        }, callback);
    }

    public void topAuthors(int limit, @NonNull Callback<List<Author>> callback) {
        run(() -> fetch("topAuthors",
                () -> remote.topAuthors(limit),
                () -> database.isReady()
                        ? database.topAuthors(limit)
                        : SeedDataSource.topAuthors(limit)), callback);
    }

    public void listByKind(@NonNull String kindCode, int limit, @NonNull Callback<List<Poem>> callback) {
        run(() -> fetch("kind:" + kindCode,
                () -> remote.listByKind(kindCode, 1, limit),
                () -> {
                    PoemKind kind = PoemKind.fromCode(kindCode);
                    return database.isReady()
                            ? database.listByKind(kind, limit)
                            : SeedDataSource.listByKind(kind, limit);
                }), callback);
    }

    public void listByDynasty(@NonNull String dynastyCode, int limit, @NonNull Callback<List<Poem>> callback) {
        run(() -> fetch("dynasty:" + dynastyCode,
                () -> remote.listByDynasty(dynastyCode, 1, limit),
                () -> database.isReady()
                        ? database.listByDynasty(dynastyCode, limit)
                        : SeedDataSource.listByDynasty(dynastyCode, limit)), callback);
    }

    /**
     * 某作者的作品。
     *
     * <p>{@code authorRef} 用 {@link Author#getLookupRef()}（uid 优先）。
     * 本地库的作品表是按数字 {@code author_id} 关联的，所以本地图里要先换算一次。
     */
    public void listByAuthor(@NonNull String authorRef, int limit,
                             @NonNull Callback<List<Poem>> callback) {
        run(() -> fetch("authorPoems:" + authorRef,
                () -> remote.authorPoems(authorRef, limit),
                () -> {
                    long localAuthorId = localAuthorId(authorRef);
                    if (localAuthorId <= 0) {
                        // 本库里没这位作者（或库还没就位）：交给种子数据兜底
                        return SeedDataSource.listByAuthor(idOf(authorRef), limit);
                    }
                    return database.listByAuthor(localAuthorId, limit);
                }), callback);
    }

    /**
     * 作者在**本库 / 后端**的作品数。
     *
     * <p>{@code authors.n_poems} 记的是母库篇数（陆游标着 9416，本库只有 7 首），不能用来显示，
     * 凡「存世多少首」都得现算。作者详情页从列表页作者筛选等入口进来时 Intent 里没有这个数，
     * 就到这里补一次。
     */
    public void countByAuthor(@NonNull String authorRef, @NonNull Callback<Integer> callback) {
        run(() -> fetch("authorCount:" + authorRef,
                () -> {
                    Author author = remote.author(authorRef);
                    return author == null ? null : author.getNPoems();
                },
                () -> countLocalAuthorPoems(authorRef)), callback);
    }

    public void listByTheme(@NonNull String keyword, int limit, @NonNull Callback<List<Poem>> callback) {
        // 主题本身就是「关键词检索」，在线时走远端能拿到更全的结果
        search(keyword, limit, callback);
    }

    // ------------------------------------------------------------ 搜索词 / 平仄

    public void hotKeywords(@NonNull Callback<List<String>> callback) {
        run(() -> fetch("hotKeywords",
                () -> {
                    List<String> words = remote.hotKeywords(10);
                    // 后端在日志表为空时也有兜底，但真返回空就别把界面搞空
                    return words.isEmpty() ? localHotKeywords() : words;
                },
                () -> localHotKeywords()), callback);
    }

    @NonNull
    private static List<String> localHotKeywords() {
        return new ArrayList<>(Arrays.asList(PoetryDatabase.HOT_KEYWORDS));
    }

    /**
     * 平仄串。
     *
     * <p>通常用不上：详情接口已经把它并进 {@code PoemDetail}（见 {@code Poem.getStrain()}），
     * 免得详情页为了这一行再闪一次。留着是给「只想校验平仄、不渲染全文」的场景用。
     */
    public void strain(@NonNull String ref, @NonNull Callback<String> callback) {
        run(() -> fetch("strain:" + ref,
                () -> remote.strain(ref),
                () -> {
                    if (!database.hasStrains()) {
                        return "";
                    }
                    long id = localId(ref);
                    return id <= 0 ? "" : database.strainOf(id);
                }), callback);
    }

    // ------------------------------------------------------------ 富文本

    /** 译文：远端优先，其次取本地 poem_extras，都没有则返回空串。 */
    public void translation(@NonNull String ref, @NonNull Callback<String> callback) {
        run(() -> fetch("translation:" + ref,
                () -> remote.translation(ref),
                () -> {
                    Poem poem = localPoem(ref);
                    return poem == null ? "" : poem.getTranslation();
                }), callback);
    }

    /** 赏析：同上 */
    public void appreciation(@NonNull String ref, @NonNull Callback<String> callback) {
        run(() -> fetch("appreciation:" + ref,
                () -> remote.appreciation(ref),
                () -> {
                    Poem poem = localPoem(ref);
                    return poem == null ? "" : poem.getAppreciation();
                }), callback);
    }

    // ------------------------------------------------------------ 账号

    /**
     * 下发登录验证码。
     *
     * @return 回调里给的是 dev 模式下服务端回显的固定码（生产环境为空串），
     *         登录页可以据此自动填上。
     */
    public void sendCode(@NonNull String phone, @NonNull Callback<String> callback) {
        run(() -> remote.sendCode(phone), callback);
    }

    /**
     * 手机号登录（不存在则自动注册）。
     *
     * <p>成功后做两件事，都在 IO 线程里：
     * <ol>
     *   <li>把 token 与用户 id 存进 {@link UserStore}，从此所有请求自动带上
     *       {@code Authorization: Bearer ...}；</li>
     *   <li>把本地攒下的收藏 / 阅读记录批量推上去（best-effort，失败不影响登录）。</li>
     * </ol>
     * 本地身份（{@code loggedIn/provider}）也一并写上，{@code AuthGuard} 认的就是它。
     */
    public void login(@NonNull String phone, @NonNull String code,
                      @Nullable String nickname, @NonNull Callback<LoginResult> callback) {
        run(() -> {
            LoginResult result = remote.login(phone, code, nickname);
            store.setLogin("phone", String.valueOf(result.getUserId()),
                    result.getNickname().isEmpty() ? nickname : result.getNickname());
            store.setAuthToken(result.getToken(), result.getUserId(), result.getExpireAtMillis());
            if (store.getNickname().isEmpty() && !result.getNickname().isEmpty()) {
                store.setNickname(result.getNickname());
            }
            pushLibraryQuietly();
            return result;
        }, callback);
    }

    /**
     * 把本地书架推到服务端。
     *
     * <p>**单向（上行）且尽力而为**：不做出行合并，也不用服务端数据覆盖本地。
     * 理由是这样最不容易出事——两个方向都做就需要一套冲突消解规则，
     * 而规则写错的代价是「用户的收藏凭空少了」，那比「多设备还没完全同步」严重得多。
     * 后端的 {@code /v1/user/library/sync} 已经支持带 cursor 的增量下发，
     * 等真正要做双向时在那一侧扩展即可。
     *
     * @param result 可空，只用来知道成没成
     */
    public void pushLibrary(@Nullable Callback<Boolean> result) {
        AppExecutors.get().io(() -> {
            boolean ok = pushLibraryQuietly();
            if (result != null) {
                AppExecutors.get().main(() -> result.onData(ok));
            }
        });
    }

    /** 同 {@link #pushLibrary}，但同步执行且吞掉所有异常（供登录流程内联调用）。 */
    private boolean pushLibraryQuietly() {
        if (!store.isAuthTokenValid()) {
            return false;
        }
        try {
            JSONArray items = new JSONArray();
            for (Poem poem : store.getFavorites()) {
                appendSyncItem(items, poem, 1, "", 0);
            }
            for (Poem poem : store.getHistory()) {
                appendSyncItem(items, poem, 2, "", store.getProgress(poem));
            }
            if (items.length() == 0) {
                return true;   // 本地没东西可推，不算失败
            }
            return remote.syncLibrary(items);
        } catch (Exception e) {
            LogUtil.w("书架上行失败（不影响本地使用）", e);
            return false;
        }
    }

    /**
     * 往同步列表里塞一条。
     *
     * <p>没有 uid 的一律跳过：后端是按 {@code poem_uid} 关联的，
     * 而 uid 是内容派生的稳定键，内置示例数据没有它 —— 报了也是脏数据。
     */
    private static void appendSyncItem(@NonNull JSONArray items, @NonNull Poem poem,
                                       int relType, @NonNull String content, int progress) {
        if (items.length() >= SYNC_LIMIT || poem.getUid().isEmpty()) {
            return;
        }
        try {
            JSONObject item = new JSONObject();
            item.put("poemUid", poem.getUid());
            item.put("relType", relType);
            item.put("content", content);
            item.put("progress", Math.max(0, Math.min(100, progress)));
            items.put(item);
        } catch (Exception ignored) {
            // 单条拼不出来就跳过，不带崩整次同步
        }
    }

    /**
     * 朗读埋点。匿名也能报（后端用 AuthOptional），失败静默 ——
     * 埋点丢了不影响用户听诗。
     */
    public void reportPlay(@Nullable Poem poem, @Nullable String voiceId, int durationMs) {
        if (poem == null || poem.getUid().isEmpty()) {
            return;
        }
        final String uid = poem.getUid();
        AppExecutors.get().io(() -> {
            try {
                remote.reportPlay(uid, voiceId, durationMs);
            } catch (Exception e) {
                LogUtil.d("朗读埋点未上报：" + e);
            }
        });
    }

    // ------------------------------------------------------------ 语音

    /** 云端音色列表。失败时回调 onError，界面照常用本地音色。 */
    public void voices(@NonNull Callback<List<Voice>> callback) {
        run(() -> remote.voices(), callback);
    }

    /**
     * 合成朗读音频，回调里给的是**可直接播放的绝对地址**。
     *
     * <p>失败时回调 onError —— 调用方据此回退本地引擎。
     * 服务端没配密钥时给的是 {@code ApiException.TTS_UNAVAILABLE}，
     * 那是「本部署没有这个能力」，重试没有意义，直接换引擎。
     */
    public void synthesize(@NonNull String poemRef, @NonNull String voiceId, float speed,
                           @NonNull Callback<String> callback) {
        run(() -> remote.synthesize(poemRef, voiceId, speed), callback);
    }

    // ------------------------------------------------------------ 用户数据（本地）

    @NonNull
    public List<Poem> favorites() {
        return store.getFavorites();
    }

    public boolean isFavorite(@NonNull Poem poem) {
        return store.isFavorite(poem);
    }

    public boolean toggleFavorite(@NonNull Poem poem) {
        return store.toggleFavorite(poem);
    }

    public void removeFavorite(@NonNull Poem poem) {
        store.removeFavorite(poem);
    }

    public void recordHistory(@NonNull Poem poem) {
        store.recordHistory(poem);
    }

    @NonNull
    public List<Poem> history() {
        return store.getHistory();
    }

    public void removeHistory(@NonNull Poem poem) {
        store.removeHistory(poem);
    }

    // ------------------------------------------------------------ 三级取数

    /** 远端那一步。允许抛 {@link ApiException}。 */
    private interface RemoteCall<T> {
        @Nullable
        T call() throws ApiException;
    }

    /** 本地那一步（本地库或内置数据）。 */
    private interface LocalCall<T> {
        @Nullable
        T call() throws Exception;
    }

    /**
     * 三级取数的核心：先试远端，失败就落到本地。
     *
     * <p>远端失败**一律**吞掉并回退，不做二次抛出。理由：对用户来说
     * 「这首诗没搜到」和「网络抖了一下」是同一件事——他要的是内容，
     * 不是一份错误报告。真要排查，日志里那条 warn 里带着原因。
     *
     * @param what 日志里用的短标签，出事时能一眼看出是哪个查询回退了
     */
    @Nullable
    private <T> T fetch(@NonNull String what,
                        @Nullable RemoteCall<T> remoteCall,
                        @NonNull LocalCall<T> localCall) throws Exception {

        if (remoteCall != null && canUseRemote()) {
            try {
                T data = remoteCall.call();
                if (data != null) {
                    markSource(Source.REMOTE);
                    return data;
                }
                // 远端「成功但没有数据」（比如这首歌恰好没平仄）：
                // 不必再查本地，本地也不会有。但也不该记成 REMOTE —— 交给本地兜一层。
            } catch (ApiException e) {
                LogUtil.w("远端取数失败（" + what + "），回退本地：" + e);
            } catch (Exception e) {
                LogUtil.w("远端取数异常（" + what + "），回退本地", e);
            }
        }

        // 只有真的要读本地库时才把它打开：冷启动时那是一次 56 MB 的拷贝，
        // 而远端能答上的话，这次等待本来是可以省掉的。
        database.ensureOpen();
        T data = localCall.call();
        markSource(database.isReady() ? Source.LOCAL : Source.SEED);
        return data;
    }

    /** 能不能走远端：接口启用 + 设备有网 + 没在熔断冷却里。 */
    private boolean canUseRemote() {
        return remote.isReachableNow();
    }

    private void markSource(@NonNull Source source) {
        lastSource = source;
    }

    // ------------------------------------------------------------ 本地查询工具

    @Nullable
    private Poem localPoem(@NonNull String ref) {
        Poem poem = null;
        if (isDigits(ref)) {
            poem = database.poemById(Long.parseLong(ref));
        } else {
            poem = database.poemByUid(ref);
        }
        if (poem == null) {
            poem = SeedDataSource.poemById(idOf(ref));
        }
        if (poem != null && database.hasStrains()) {
            poem.setStrain(database.strainOf(poem.getId()));
        }
        return poem;
    }

    /**
     * 把作品引用换成**本地库**的 id。
     *
     * <p>{@code ref} 是 uid 时本库得扫一次才能换算（{@code poems.uid} 没建索引，
     * 见 {@code PoetryDatabase#poemByUid}），所以只用在「一次点击走一次」的兜底路径上。
     *
     * @return 本地 id；不是数字又查不到时返回 0
     */
    private long localId(@Nullable String ref) {
        if (isDigits(ref)) {
            return Long.parseLong(ref);
        }
        if (ref == null || ref.isEmpty() || !database.isReady()) {
            return 0L;
        }
        Poem poem = database.poemByUid(ref);
        return poem == null ? 0L : poem.getId();
    }

    /**
     * 把作者引用换成**本地库**的 {@code authors.id}。
     *
     * @return {@code authors.id}；不是数字又查不到时返回 0
     */
    private long localAuthorId(@Nullable String ref) {
        if (isDigits(ref)) {
            return Long.parseLong(ref);
        }
        if (ref == null || ref.isEmpty() || !database.isReady()) {
            return 0L;
        }
        Author author = database.authorByUid(ref);
        return author == null ? 0L : author.getId();
    }

    private int countLocalAuthorPoems(@NonNull String authorRef) {
        if (!database.isReady()) {
            return SeedDataSource.listByAuthor(idOf(authorRef), Integer.MAX_VALUE).size();
        }
        long authorId = localAuthorId(authorRef);
        return authorId <= 0 ? 0 : database.countByAuthor(authorId);
    }

    /** {@code ref} 是不是一个纯数字 id（后端与本地库都按这个约定分流）。 */
    private static boolean isDigits(@Nullable String ref) {
        if (ref == null || ref.isEmpty()) {
            return false;
        }
        for (int i = 0; i < ref.length(); i++) {
            if (!Character.isDigit(ref.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 把 ref 折成数字 id；不是数字就返回 0（本地库查不到会自然回退到种子数据）。 */
    private static long idOf(@Nullable String ref) {
        return isDigits(ref) ? Long.parseLong(ref) : 0L;
    }

    // ------------------------------------------------------------ 线程封装

    private <T> void run(@NonNull Callable<T> task, @NonNull Callback<T> callback) {
        AppExecutors.get().io(() -> {
            try {
                T data = task.call();
                // 每次查询都是一次「库现在能用了吗」的顺带检查：下载装好后的一次
                // reload() 会在下一轮查询被这里确认。状态真的变了才广播。
                publishReadiness(database.isReady(), database.filePresent());
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

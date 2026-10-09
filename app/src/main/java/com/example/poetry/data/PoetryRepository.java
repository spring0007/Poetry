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
import com.example.poetry.data.model.Page;
import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.PoemKind;
import com.example.poetry.data.model.UserAuth;
import com.example.poetry.data.model.UserProfile;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

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
    private synchronized void publishReadiness(boolean localReady, boolean hasFile) {
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

    /**
     * 热门榜（发现页 / 「热门」入口）。
     *
     * <p>**本地优先**：先把本地库（或内置示例）的头几条回调出去，界面立刻有东西，
     * 远端回来之后再回调一次。用这个方法的界面都是「替换」语义
     * （{@code adapter.submit()}），收到两次不会叠成两份。
     */
    public void featured(int limit, @NonNull Callback<List<Poem>> callback) {
        listFirstScreen(PoemQuery.hot(), limit, listing(callback));
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

    /** 综合检索。本地优先，远端回来再刷一次（同 {@link #featured}）。 */
    public void search(@NonNull String keyword, int limit, @NonNull Callback<List<Poem>> callback) {
        listFirstScreen(PoemQuery.search(keyword), limit, listing(callback));
    }

    // ------------------------------------------------------------ 分类

    /** 体裁宫格。本地优先 —— 宫格是分类页的第一屏，不该等一个网络往返。 */
    public void kindCategories(@NonNull Callback<List<Category>> callback) {
        localFirst("kindCategories",
                () -> database.isReady()
                        ? database.kindCategories()
                        : SeedDataSource.kindCategories(),
                () -> remote.kindCategories(), callback);
    }

    /** 朝代宫格。本地优先，同 {@link #kindCategories}。 */
    public void dynastyCategories(@NonNull Callback<List<Category>> callback) {
        localFirst("dynastyCategories",
                () -> database.isReady()
                        ? database.dynastyCategories()
                        : SeedDataSource.dynastyCategories(),
                () -> remote.dynastyCategories(), callback);
    }

    /**
     * 主题宫格。
     *
     * <p>**只走本地**：这 12 个主题是设计稿写死的（见 {@code Theme.defaults()}），
     * 靠关键词在正文里 LIKE 检索。后端有真实的标签体系（{@code /v1/tags}），
     * 但那是另一套分类，两边混用会让同一张宫格在线和离线时长得不一样，
     * 而「同一个界面在两台设备上不同」是很难向用户解释的。
     * 等确定要以后端标签为准时，改这一个方法即可。
     *
     * <p>**首次慢、之后免费**：这 12 个篇数各是一次 {@code body LIKE} 全表扫描
     * （{@link PoetryDatabase#themeCategories}），但诗库是静态的，结果在数据库里
     * 缓存住（{@code themeCountCache}，换库时清空），第二次调用只剩遍历 12 个主题的开销。
     * 所以这里不额外做线程调度取巧，走 {@link #runLocal} 直送 io 池——
     * 唯一要避免的是让这段纯本地 SQL 去占网络池的线程。
     */
    public void themeCategories(@NonNull Callback<List<Category>> callback) {
        runLocal(() -> {
            database.ensureOpen();
            markSource(database.isReady() ? Source.LOCAL : Source.SEED);
            return database.isReady()
                    ? database.themeCategories()
                    : SeedDataSource.themeCategories();
        }, callback);
    }

    /** 热门作者横排。本地优先，同 {@link #kindCategories}。 */
    public void topAuthors(final int limit, @NonNull Callback<List<Author>> callback) {
        localFirst("topAuthors",
                () -> database.isReady()
                        ? database.topAuthors(limit)
                        : SeedDataSource.topAuthors(limit),
                () -> remote.topAuthors(limit), callback);
    }

    /** 按体裁列作品。本地优先，远端回来再刷一次（同 {@link #featured}）。 */
    public void listByKind(@NonNull String kindCode, int limit,
                           @NonNull Callback<List<Poem>> callback) {
        listFirstScreen(PoemQuery.kind(kindCode), limit, listing(callback));
    }

    /** 按朝代列作品。本地优先，远端回来再刷一次（同 {@link #featured}）。 */
    public void listByDynasty(@NonNull String dynastyCode, int limit,
                              @NonNull Callback<List<Poem>> callback) {
        listFirstScreen(PoemQuery.dynasty(dynastyCode), limit, listing(callback));
    }

    /**
     * 某作者的作品。
     *
     * <p>{@code authorRef} 用 {@link Author#getLookupRef()}（uid 优先）。
     * 本地库的作品表是按数字 {@code author_id} 关联的，换算这一步在
     * {@link #localPage} 里（那里才知道库到底有没有就位）。
     *
     * <p>本地优先，远端回来再刷一次（同 {@link #featured}）。
     */
    public void listByAuthor(@NonNull String authorRef, int limit,
                             @NonNull Callback<List<Poem>> callback) {
        listFirstScreen(PoemQuery.author(authorRef), limit, listing(callback));
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
            // 登录响应里也带了 user 对象，但资料接口才是权威口径 ——
            // 它反映的是「此刻库里的样子」（含 status 被禁用这种登录之后才变的字段）。
            // 这里再拉一次，让刚登录的用户立刻拿到正确的会员权益；
            // 拉不到也不抛出：登录已经成功，下次 App 启动还会再刷。
            try {
                fetchProfile();
            } catch (ApiException e) {
                LogUtil.d("登录后刷新资料失败（下次启动会重试）：" + e);
            }
            pushLibraryQuietly();
            return result;
        }, callback);
    }

    /**
     * 刷新服务端资料缓存（会员权益 + 昵称）。
     *
     * <p>调用时机：App 启动、登录成功之后。将来「我的」页下拉刷新也可以用它。
     *
     * @param callback 可空。{@code onError} 只说明「这次没刷上」，不代表账号有问题
     */
    public void refreshProfile(@Nullable Callback<UserProfile> callback) {
        AppExecutors.get().net(() -> {
            UserProfile profile;
            try {
                profile = fetchProfile();
            } catch (ApiException e) {
                LogUtil.d("刷新资料失败，本次按未开通处理：" + e);
                if (callback != null) {
                    AppExecutors.get().main(() -> callback.onError(e));
                }
                return;
            }
            if (callback != null) {
                AppExecutors.get().main(() -> callback.onData(profile));
            }
        });
    }

    /**
     * 拉一次资料并写进 {@link UserStore}；任何失败都会把本地权益清成未开通。
     *
     * <p>失败即降级（而不是保留上一次的缓存）是有意的：见
     * {@link UserStore#setAccount} 的注释 —— 判定基准在服务端，本地多留一份
     * 无助于真能合成，只会把失败推迟到用户点下朗读的那一刻。
     *
     * <p>回填昵称，但**不回填头像**：本地那个头像字段存的是「一个汉字或印象 emoji」
     * （见 {@code UserStore.getAvatar()}），服务端给的是图片 URL，塞进去界面会显示
     * 一串网址。等头像真接上（要一套加载器）再说。
     */
    @NonNull
    private UserProfile fetchProfile() throws ApiException {
        if (!store.isAuthTokenValid()) {
            store.setAccount(null);
            throw new ApiException(ApiException.UNAUTHORIZED, "未登录");
        }
        UserProfile profile;
        try {
            profile = remote.profile();
        } catch (ApiException e) {
            store.setAccount(null);
            throw e;
        }
        store.setAccount(profile);
        if (store.getNickname().isEmpty() && !profile.getNickname().isEmpty()) {
            store.setNickname(profile.getNickname());
        }
        return profile;
    }

    /**
     * 当前账号的认证绑定（手机号 / 微信 / QQ），给「我的」页的「账号」卡片用。
     *
     * <p>本机**不存手机号**（见 {@link UserAuth}），这是唯一能拿到它的地方。
     * 未登录时直接回空列表而不是抛错：界面本来就还没到「显示手机号」那一步，
     * 让调用方为此写一个分支不值得。
     */
    public void auths(@NonNull Callback<List<UserAuth>> callback) {
        AppExecutors.get().net(() -> {
            List<UserAuth> auths;
            try {
                auths = store.isAuthTokenValid() ? remote.auths() : new ArrayList<>();
            } catch (ApiException e) {
                AppExecutors.get().main(() -> callback.onError(e));
                return;
            }
            AppExecutors.get().main(() -> callback.onData(auths));
        });
    }

    /**
     * 主动续签当前凭证，给「我的」页的「延长登录」用。
     *
     * <p>与 {@code ApiClient} 撞到 401 时的自动续签是同一条实现
     * （见 {@code ApiClient#refreshSession}），区别只在由谁发起。
     * {@code onData(false)} 表示「旧票已经换不动了」，此时该引导用户重新登录；
     * 走网络就一定会有这种情况，所以它是正常结果，不算错误。
     */
    public void refreshSession(@NonNull Callback<Boolean> callback) {
        AppExecutors.get().net(() -> {
            boolean ok;
            try {
                ok = remote.refreshSession();
            } catch (ApiException e) {
                AppExecutors.get().main(() -> callback.onError(e));
                return;
            }
            AppExecutors.get().main(() -> callback.onData(ok));
        });
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

    // ------------------------------------------------------------ 分页取数（本地优先）

    /**
     * 分页结果的回调，**最多被调两次**。
     *
     * <p>第一次 {@code fromCache=true}：本地库（或内置示例）—— 手上立刻就有，不等网络。
     * 第二次 {@code fromCache=false}：远端拿回来的权威结果。
     *
     * <p>远端那一次不来是**正常情况**（没网、后端没配、正在熔断）：界面保留第一次的
     * 内容即可，不必显示任何错误 —— 用户要看的是诗，不是一份网络报告。
     * {@link #onError} 只在「本地这一层也拿不到」时才来。
     */
    public interface PageCallback {

        /**
         * @param fromCache true = 本地/内置数据，false = 远端
         */
        void onPage(@NonNull Page<Poem> page, boolean fromCache);

        /** 本地这层也失败了（库打不开之类），界面该显示空态了。 */
        void onError(@NonNull Throwable error);
    }

    /**
     * 列表首屏：**本地先出图，远端回来再刷一次**。
     *
     * <p>这是列表类界面（分类列表、发现页、作者作品）的标准取数方式。为什么不是
     * 「远端优先」：后端读超时是 8 秒（{@code ApiConfig.READ_TIMEOUT_MS}），
     * 远端优先意味着点进「诗」要盯着加载条等一个网络往返才见到第一条；
     * 而本地库里就有全量作品，出图是毫秒级的。
     */
    public void listFirstScreen(@NonNull PoemQuery query, int size,
                                @NonNull PageCallback callback) {
        listFirstScreen(query, 1, size, callback);
    }

    /** 同 {@link #listFirstScreen(PoemQuery, int, PageCallback)}，指定页号。 */
    public void listFirstScreen(@NonNull final PoemQuery query, final int page, final int size,
                                @NonNull final PageCallback callback) {
        // 本地那一页留个引用：远端回来时要拿它补正文，见 carryOverBodies。
        // 用 AtomicReference 是因为写它的是 io 线程、读它的是 net 线程（两个池，没有
        // 天然的 happens-before）。
        final AtomicReference<Page<Poem>> cachedPage = new AtomicReference<>();
        listLocal(query, page, size, new PageCallback() {
            @Override
            public void onPage(@NonNull Page<Poem> p, boolean fromCache) {
                cachedPage.set(p);
                callback.onPage(p, true);
            }

            @Override
            public void onError(@NonNull Throwable error) {
                callback.onError(error);
            }
        });
        listRemote(query, page, size, new PageCallback() {
            @Override
            public void onPage(@NonNull Page<Poem> p, boolean fromCache) {
                carryOverBodies(p, cachedPage.get());
                callback.onPage(p, false);
            }

            @Override
            public void onError(@NonNull Throwable error) {
                // 本地那层已经画过一屏了，远端挂掉不值得惊动用户。真要排查看这条日志。
                LogUtil.w("远端刷新失败（" + query + "），保留本地内容：" + error);
            }
        });
    }

    /**
     * 把本地那一页里已有的正文补到远端这一页上。
     *
     * <p>后端的列表接口有意在 {@code items} 里**不带正文**（见 {@code JsonMapper} 的类注释），
     * 而卡片上的摘录与行数都是从正文派生的。不补的话，「本地先出图 → 远端替换」这一步会把
     * 已经画好的摘录整片抹掉：同一首诗明明还在原地，字却没了。
     *
     * <p>只补**空**正文，远端真给了正文就以远端为准 —— 这条规则让本方法在任何调用顺序下
     * 都不会用旧数据盖住新数据，所以调用方不必关心本地/远端谁先到。
     */
    private static void carryOverBodies(@NonNull Page<Poem> fresh, @Nullable Page<Poem> cached) {
        if (cached == null || cached.isEmpty() || fresh.isEmpty()) {
            return;
        }
        Map<String, String> bodies = new HashMap<>();
        for (Poem poem : cached.getItems()) {
            if (!poem.getBody().isEmpty()) {
                bodies.put(poem.identityKey(), poem.getBody());
            }
        }
        if (bodies.isEmpty()) {
            return;
        }
        for (Poem poem : fresh.getItems()) {
            if (poem.getBody().isEmpty()) {
                String body = bodies.get(poem.identityKey());
                if (body != null) {
                    poem.setBody(body);
                }
            }
        }
    }

    /**
     * 只读本地（库没就位就用内置示例），跑在 {@link AppExecutors#io()} 上，**立即**回调。
     *
     * <p>本地这一层**一定会回调**，哪怕是空的一页 —— 调用方靠它定下「第一页已经就位」，
     * 也靠它决定要不要继续翻页。
     */
    public void listLocal(@NonNull final PoemQuery query, final int page, final int size,
                          @NonNull final PageCallback callback) {
        AppExecutors.get().io(() -> {
            final Page<Poem> result;
            try {
                result = localPage(query, page, size);
            } catch (Exception e) {
                LogUtil.w("本地分页取数失败（" + query + "）", e);
                AppExecutors.get().main(() -> callback.onError(e));
                return;
            }
            // 一次顺带的「库现在能用了吗」检查，与 run() 里那句同一个用意
            publishReadiness(database.isReady(), database.filePresent());
            AppExecutors.get().main(() -> callback.onPage(result, true));
        });
    }

    /**
     * 只打远端，跑在 {@link AppExecutors#net()} 上。
     *
     * <p>失败一律走 {@link PageCallback#onError}，包括「后端没配 / 没网 / 熔断中」——
     * 那几种情况连请求都不会发出去，但仍算这一层失败，语义上是一致的。
     */
    public void listRemote(@NonNull final PoemQuery query, final int page, final int size,
                           @NonNull final PageCallback callback) {
        AppExecutors.get().net(() -> {
            final Page<Poem> result;
            try {
                result = remotePage(query, page, size);
            } catch (Throwable e) {
                LogUtil.w("远端分页取数失败（" + query + "）", e);
                AppExecutors.get().main(() -> callback.onError(e));
                return;
            }
            markSource(Source.REMOTE);
            AppExecutors.get().main(() -> callback.onPage(result, false));
        });
    }

    /** 本地那一页。库没就位就退回内置示例。 */
    @NonNull
    private Page<Poem> localPage(@NonNull PoemQuery query, int page, int size) {
        int n = Math.max(1, size);
        int p = Math.max(1, page);
        String mode = query.getMode();
        String value = query.getValue();

        // 库还没就位：内置示例顶上。总数就报这一页的条数，于是 hasMore() 是 false ——
        // 示例数据一共就十几条，为它翻页没有意义，界面上那点内容远端一回来就会被替换掉。
        if (!database.isReady()) {
            List<Poem> items = seedPage(query, n);
            return new Page<>(items, items.size(), 1, n);
        }

        // 检索与主题：本地只给得出第一页（见 PoemQuery#isLocallyPaged），往后交给远端
        if (!query.isLocallyPaged()) {
            if (p > 1) {
                return Page.empty(p, n);
            }
            List<Poem> hits = PoemQuery.MODE_THEME.equals(mode)
                    ? database.listByTheme(value, n)
                    : database.search(value, n);
            return new Page<>(hits, hits.size(), 1, n);
        }

        int offset = (p - 1) * n;
        List<Poem> items;
        if (PoemQuery.MODE_KIND.equals(mode)) {
            items = database.listByKind(PoemKind.fromCode(value), n, offset);
        } else if (PoemQuery.MODE_DYNASTY.equals(mode)) {
            items = database.listByDynasty(value, n, offset);
        } else if (PoemQuery.MODE_HOT.equals(mode)) {
            items = database.featured(n, offset);
        } else if (PoemQuery.MODE_AUTHOR.equals(mode)) {
            long authorId = localAuthorId(value);
            if (authorId <= 0) {
                // 本库里没这位作者（作者是从远端宫格点进来的）：示例数据兜底，只有第一页
                if (p > 1) {
                    return Page.empty(p, n);
                }
                List<Poem> seed = SeedDataSource.listByAuthor(idOf(value), n);
                return new Page<>(seed, seed.size(), 1, n);
            }
            items = database.listByAuthor(authorId, n, offset);
        } else {
            return Page.empty(p, n);
        }
        // 本地不额外跑 COUNT：翻页的「还有没有下一页」由 Page#hasMore() 退化成
        // 「这一页装满了没有」。多跑一次全表 COUNT 换一个更准的界标，不划算。
        return new Page<>(items, Page.TOTAL_UNKNOWN, p, n);
    }

    /** 远端那一页。 */
    @NonNull
    private Page<Poem> remotePage(@NonNull PoemQuery query, int page, int size)
            throws ApiException {
        if (!canUseRemote()) {
            throw new ApiException(ApiException.NOT_IMPLEMENTED, "远端不可用");
        }
        int p = Math.max(1, page);
        int n = Math.max(1, size);
        String mode = query.getMode();
        String value = query.getValue();
        if (PoemQuery.MODE_HOT.equals(mode)) {
            return remote.featured(p, n);
        }
        if (PoemQuery.MODE_KIND.equals(mode)) {
            return remote.listByKind(value, p, n);
        }
        if (PoemQuery.MODE_DYNASTY.equals(mode)) {
            return remote.listByDynasty(value, p, n);
        }
        if (PoemQuery.MODE_AUTHOR.equals(mode)) {
            return remote.authorPoems(value, p, n);
        }
        // 检索与主题都落到综合检索
        return remote.search(value, p, n);
    }

    /** 内置示例数据的一页。 */
    @NonNull
    private static List<Poem> seedPage(@NonNull PoemQuery query, int limit) {
        String mode = query.getMode();
        String value = query.getValue();
        if (PoemQuery.MODE_HOT.equals(mode)) {
            return SeedDataSource.featured(limit);
        }
        if (PoemQuery.MODE_KIND.equals(mode)) {
            return SeedDataSource.listByKind(PoemKind.fromCode(value), limit);
        }
        if (PoemQuery.MODE_DYNASTY.equals(mode)) {
            return SeedDataSource.listByDynasty(value, limit);
        }
        if (PoemQuery.MODE_AUTHOR.equals(mode)) {
            return SeedDataSource.listByAuthor(idOf(value), limit);
        }
        return SeedDataSource.search(value, limit);
    }

    /**
     * 把两次投递的 {@link PageCallback} 收敛成老的单次 {@link Callback}。
     *
     * <p>空的第一页**不投**：库里恰好没有这一条时，先投一个空列表会让界面闪一下
     * 「暂无内容」，几百毫秒后远端又把内容填回来。宁可保持加载态，等远端那一次；
     * 远端也失败了，调用方才会收到 onError，那时候显示空态才是对的。
     *
     * <p>用它的调用方都必须能接受 {@code onData} 来两次（都是替换语义）。
     */
    @NonNull
    private static PageCallback listing(@NonNull final Callback<List<Poem>> callback) {
        return new PageCallback() {
            @Override
            public void onPage(@NonNull Page<Poem> page, boolean fromCache) {
                if (fromCache && page.isEmpty()) {
                    return;
                }
                callback.onData(page.getItems());
            }

            @Override
            public void onError(@NonNull Throwable error) {
                callback.onError(error);
            }
        };
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
        // 这一趟会不会先去碰网络？会的话放网络池：8 秒的读超时不该占着本地库那两个线程
        // （本地列表查询就排队在这两个线程上，正是「点进分类页要等一下」的成因）。
        // 判断在提交前做，fetch() 里还会再判一次 —— 中途网络断了，最多是白占一个网络线程。
        AppExecutors.get().call(canUseRemote(), () -> {
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

    /**
     * 确定只碰本地库的那种查询：直接放 io 池，不参与 {@link #run} 的
     * 「可能访问网络」猜测。
     *
     * <p>猜测对 {@code fetch()} 那类「先远端、失败落本地」的调用是对的，但对纯本地查询
     * 只会**误判**：后端活着时 {@code canUseRemote()} 为真，一段不带任何网络的 SQL 就被
     * 塞进网络池，白占一个本该留给 HTTP 的线程。
     */
    private <T> void runLocal(@NonNull Callable<T> task, @NonNull Callback<T> callback) {
        AppExecutors.get().io(() -> {
            try {
                T data = task.call();
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

    /**
     * 宫格/榜单这类「一屏就是全部」的数据：本地先出图，远端成功再覆盖一次。
     *
     * <p>与 {@link #listFirstScreen} 同一套思路，差别在数据形状 —— 这里不翻页，
     * 没有 {@link Page}，就一段列表。
     *
     * <p>空的本地结果同样**不投**（理由见 {@link #listing}）：宫格先清空再填满
     * 看起来像是「加载失败又好了」。
     */
    private <T> void localFirst(@NonNull String what,
                                @NonNull Callable<T> localCall,
                                @NonNull Callable<T> remoteCall,
                                @NonNull Callback<T> callback) {
        AppExecutors.get().io(() -> {
            final T local;
            try {
                database.ensureOpen();
                local = localCall.call();
                markSource(database.isReady() ? Source.LOCAL : Source.SEED);
                publishReadiness(database.isReady(), database.filePresent());
            } catch (Exception e) {
                LogUtil.w("本地取数失败（" + what + "）", e);
                AppExecutors.get().main(() -> callback.onError(e));
                return;
            }
            final boolean localEmpty = isEmpty(local);
            if (!localEmpty) {
                AppExecutors.get().main(() -> callback.onData(local));
            }
            AppExecutors.get().net(() -> {
                if (!canUseRemote()) {
                    reportIfEmpty(localEmpty, local, callback);
                    return;
                }
                try {
                    T fresh = remoteCall.call();
                    markSource(Source.REMOTE);
                    AppExecutors.get().main(() -> callback.onData(fresh));
                } catch (Exception e) {
                    // 本地已经画过一屏，保留它；界面不该为一次刷新失败变样
                    LogUtil.w("远端刷新失败（" + what + "），保留本地内容：" + e);
                    reportIfEmpty(localEmpty, local, callback);
                }
            });
        });
    }

    /**
     * 兜底：本地本来就是空的、远端又没成，才把那个空结果投出去。
     *
     * <p>不这么做的话「本地空 + 远端挂」就一次回调都没有，界面上的加载指示器会一直转。
     * 反过来，本地有内容时这个空结果绝不能投 —— 那会把已经画好的宫格清空。
     */
    private static <T> void reportIfEmpty(boolean localEmpty, @Nullable T local,
                                          @NonNull Callback<T> callback) {
        if (localEmpty && local != null) {
            AppExecutors.get().main(() -> callback.onData(local));
        }
    }

    private static boolean isEmpty(@Nullable Object data) {
        return data == null || (data instanceof java.util.Collection
                && ((java.util.Collection<?>) data).isEmpty());
    }
}

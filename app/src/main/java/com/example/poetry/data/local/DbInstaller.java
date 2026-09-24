package com.example.poetry.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.AppExecutors;
import com.example.poetry.data.Callback;
import com.example.poetry.data.DbStatus;
import com.example.poetry.data.remote.ApiException;
import com.example.poetry.data.remote.DbDownloadPolicy;
import com.example.poetry.data.remote.DbManifest;
import com.example.poetry.data.remote.DownloadCallback;
import com.example.poetry.data.remote.HttpDownloader;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 诗库的「检查 → 下载 → 校验 → 安装 → 热重载」状态机。
 *
 * <h3>为什么是应用级单例</h3>
 * 下载**不能随界面销毁而取消**。它是应用级、一次性的操作，用户切到书架标签页就把它掐掉
 * 是敌意行为。所以归属在这里，只持有 {@code getApplicationContext()}，
 * 界面离开时只做 {@code removeListener}。
 *
 * <h3>为什么自带线程</h3>
 * 理由同 {@code PoetryApp.warmUp}：{@code AppExecutors.io} 是**单线程**的，所有查询都排
 * 在那条队上，一个 100 MB 的下载会把每一个查询饿死。这里另起一条线程，
 * 只在最后「换文件 + 重开库」那一步才切回 {@code AppExecutors.io}——那一步必须和查询串行。
 *
 * <h3>不变式</h3>
 * 目标路径要么不存在，要么是一个**完整**的文件。半成品的名字永远是
 * {@code poetry.db.<sha8>.part}，唯一进入目标名的动作是一次 {@code rename}。
 * 中途被杀：碎片留着，目标没动，下次启动照旧能起（旧库或示例数据）。
 */
public final class DbInstaller {

    private static final String TAG = "DbInstaller";

    private static final String PREF = "poetry_db_install";
    private static final String KEY_SHA = "installed_sha";
    private static final String KEY_BUILT_AT = "installed_built_at";
    private static final String KEY_SUBSET = "installed_subset";
    private static final String KEY_SIZE = "installed_size";
    private static final String KEY_LAST_CHECK = "last_checked_at";

    /** 空间余量：至少留 4 MB 或文件的 10%，取大的那个 */
    private static final long MIN_SLACK = 4L << 20;

    private static volatile DbInstaller instance;

    private final Context context;
    private final PoetryDatabase database;
    private final SharedPreferences prefs;

    /**
     * 专用线程。**不是** {@code AppExecutors.io}——见类注释。
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "poetry-db-installer");
        t.setPriority(Thread.MIN_PRIORITY);   // 别和界面线程抢 CPU
        return t;
    });

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile DbStatus status;

    /** 非空表示正在下载，用来实现取消 */
    @Nullable
    private volatile HttpDownloader activeDownload;

    /** 检查过、还没消费掉的 manifest。避免用户点「开始下载」时再拉一次 version.json。 */
    @Nullable
    private volatile DbManifest pendingManifest;

    /** 有任务在跑（检查或下载）。用它串行化，避免连点两次开两个下载。 */
    private volatile boolean running;

    /** 诗库状态变化的回调，总在主线程。 */
    public interface Listener {
        void onDbStatus(@NonNull DbStatus status);
    }

    private DbInstaller(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.database = PoetryDatabase.get(this.context);
        this.prefs = this.context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        this.status = DbStatus.idle(database.isReady(), database.filePresent());
    }

    @NonNull
    public static DbInstaller get(@NonNull Context context) {
        if (instance == null) {
            synchronized (DbInstaller.class) {
                if (instance == null) {
                    instance = new DbInstaller(context);
                }
            }
        }
        return instance;
    }

    // ------------------------------------------------------------ 订阅

    /** 注册，并**立刻回调一次当前状态**，界面因此不必在 {@code onViewCreated} 里另查一遍。 */
    public void addListener(@NonNull Listener listener) {
        listeners.addIfAbsent(listener);
        publish(listener, status());
    }

    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    /**
     * 当前状态。不碰磁盘——{@code isReady()}/{@code filePresent()} 都是字段读取。
     *
     * <p>休闲状态（{@code IDLE} / {@code FAILED}）下重新问一次库，而不是直接回放字段：
     * 本对象是在 {@code PoetryRepository} 的构造函数里建的，那一刻 {@code poetry.db}
     * 通常**还没打开**，快照会永远停在「未就绪」。忙的时候则必须回放快照——进度和错误
     * 都在里面，重算等于把它们抹掉。
     */
    @NonNull
    public DbStatus status() {
        DbStatus current = status;
        if (current.state != DbStatus.State.IDLE && current.state != DbStatus.State.FAILED) {
            return current;
        }
        return DbStatus.idle(database.isReady(), database.filePresent());
    }

    /** 上次安装成功的库的 sha256；从没下载安装过时为 null。 */
    @Nullable
    public String installedSha() {
        return prefs.getString(KEY_SHA, null);
    }

    // ------------------------------------------------------------ 对外动作

    /**
     * 启动时按策略决定要不要动。由 {@code PoetryApp} 的 warmUp 线程在库接通之后调用。
     *
     * <p>{@code MANUAL_ONLY} 下什么都不做——测试期就靠这个让每种失败模式都能手动复现。
     */
    public void onAppStart() {
        if (DbDownloadPolicy.TRIGGER == DbDownloadPolicy.Trigger.MANUAL_ONLY) {
            Log.i(TAG, "MANUAL_ONLY：不在启动时自动检查");
            return;
        }
        long last = prefs.getLong(KEY_LAST_CHECK, 0L);
        if (System.currentTimeMillis() - last < DbDownloadPolicy.CHECK_INTERVAL_MS) {
            Log.i(TAG, "检查节流中，跳过");
            return;
        }
        if (!DbDownloadPolicy.isOnline(context)) {
            Log.i(TAG, "当前离线，跳过检查");
            return;
        }
        checkForUpdate(false, null);
    }

    /**
     * 拉 version.json 判断要不要下载。
     *
     * @param force  true = 绕过 6 小时节流（「关于」页的按钮走这条）
     * @param result 可空。非空时：{@code onData(true)} = 已是最新，
     *               {@code onData(false)} = 有新版本可下，{@code onError} = 检查失败。
     */
    public void checkForUpdate(boolean force, @Nullable final Callback<Boolean> result) {
        if (running) {
            if (result != null) {
                AppExecutors.get().main(() -> result.onError(
                        new ApiException(ApiException.NETWORK, "正在忙，请稍候")));
            }
            return;
        }
        if (!force) {
            long last = prefs.getLong(KEY_LAST_CHECK, 0L);
            if (System.currentTimeMillis() - last < DbDownloadPolicy.CHECK_INTERVAL_MS) {
                if (result != null) {
                    AppExecutors.get().main(() -> result.onError(
                            new ApiException(ApiException.NETWORK, "刚检查过，稍后再试")));
                }
                return;
            }
        }
        running = true;
        publish(build(DbStatus.State.CHECKING, 0L, -1L, null));
        worker.execute(() -> {
            try {
                boolean upToDate = doCheck();
                if (result != null) {
                    final boolean value = upToDate;
                    AppExecutors.get().main(() -> result.onData(value));
                }
            } catch (ApiException e) {
                fail(e);
                if (result != null) {
                    final ApiException error = e;
                    AppExecutors.get().main(() -> result.onError(error));
                }
            } finally {
                running = false;
            }
        });
    }

    /**
     * 开始下载并安装。若还没检查过，会先检查一次（在同一条线程上接着做）。
     *
     * <p>下载**不在** {@code AppExecutors.io} 上跑；只有最后换文件那一步切过去。
     */
    public void startDownload() {
        if (running) {
            Log.i(TAG, "已有任务在跑，忽略这次开始下载");
            return;
        }
        running = true;
        worker.execute(() -> {
            try {
                DbManifest manifest = pendingManifest;
                if (manifest == null) {
                    if (doCheck()) {
                        return;                    // 检查下来已是最新，没必要下
                    }
                    manifest = pendingManifest;
                }
                if (manifest == null) {
                    return;                        // doCheck 已经发过状态了
                }
                doDownloadAndInstall(manifest);
            } catch (ApiException e) {
                fail(e);
            } finally {
                running = false;
            }
        });
    }

    /**
     * 取消下载。**碎片留在磁盘上**以便续传。只有两个取消触发点：用户点「取消」，
     * 或代码显式调这个方法——界面销毁不是其中之一。
     */
    public void cancelDownload() {
        HttpDownloader downloader = activeDownload;
        if (downloader != null) {
            downloader.cancel();
        }
    }

    // ------------------------------------------------------------ 检查

    /**
     * @return true 表示本地已是最新（不需要下载）
     */
    private boolean doCheck() throws ApiException {
        // meta 必须从**活着的句柄**读：文件在但打开失败（下坏了、被截断）时，
        // readMeta() 返回 null 而 filePresent() 是 true，界面据此说「诗库损坏，请重新下载」
        // 而不是「还没下载」。
        PoetryDatabase.Meta meta = onIo(() -> {
            database.ensureOpen();
            return database.readMeta();
        });

        if (meta == null) {
            Log.i(TAG, "本地没有可用的诗库");
            pendingManifest = null;
            if (!DbDownloadPolicy.isOnline(context)) {
                publish(build(DbStatus.State.IDLE, 0L, -1L, null));
                return true;                       // 没网就当「没什么可做」，别报错
            }
            return fetchAndCompare(null, false, null);
        }
        if (!meta.schemaCompatible()) {
            throw new ApiException(ApiException.SCHEMA,
                    "本地诗库版本不兼容（" + meta.schemaVersion + "），请重新下载");
        }

        // FOREIGN：prefs 里记的安装信息跟眼前的文件对不上，说明这个库不是我们装进去的
        // （assets 拷出来的、或者开发时 adb push 的）。此时把 prefs 当作**可失效的缓存**
        // 丢掉——不丢的话，adb push 一个完整库盖掉下载来的子集之后，prefs 会一直骗 App 说
        // 「已是最新」，那个完整库反而升不上去。
        boolean foreign = isForeign(meta);
        String localSha = foreign ? null : prefs.getString(KEY_SHA, null);
        if (foreign) {
            Log.i(TAG, "本地库来源不明（" + meta + "），不采信记录");
        }
        return fetchAndCompare(localSha, meta.subset, meta.builtAt);
    }

    /**
     * 拉 manifest 并比对。
     *
     * @return true 表示已是最新
     */
    private boolean fetchAndCompare(@Nullable String localSha, boolean localSubset,
                                    @Nullable String localBuiltAt) throws ApiException {
        if (!DbDownloadPolicy.isOnline(context)) {
            // 断网时不该去拉 version.json：一次连接超时是 15 秒，冷启动路径上不该有它。
            Log.i(TAG, "离线，跳过版本检查");
            publish(build(DbStatus.State.IDLE, 0L, -1L, null));
            return true;
        }

        // 也登记到 activeDownload 上：拉 version.json 可能要等满一个连接超时（15 秒），
        // 那段时间里「取太慢」和「取消没反应」在用户看来是一回事。
        final HttpDownloader downloader = new HttpDownloader();
        activeDownload = downloader;
        String text;
        try {
            text = downloader.getText(DbDownloadPolicy.MANIFEST_URL,
                    DbDownloadPolicy.MANIFEST_READ_TIMEOUT_MS);
        } finally {
            activeDownload = null;
        }
        // manifest 没写 url 时，回退到 version.json 的同级目录
        String fallbackDbUrl = DbDownloadPolicy.MANIFEST_URL
                .replace("version.json", DatabaseProvider.POETRY_DB);
        DbManifest manifest = DbManifest.parse(text, fallbackDbUrl);
        Log.i(TAG, "服务端 " + manifest + "，本地 sha=" + (localSha == null
                ? "无" : HttpDownloader.sha8(localSha)) + " subset=" + localSubset);
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();

        if (!manifest.isNewerThan(localSha, localSubset, localBuiltAt)) {
            pendingManifest = null;
            publish(build(DbStatus.State.IDLE, 0L, -1L, null));
            return true;
        }

        pendingManifest = manifest;
        // 自动模式在移动网络上先等一下；手动模式（测试期）直接给「可下载」
        boolean meterOk = DbDownloadPolicy.TRIGGER != DbDownloadPolicy.Trigger.AUTO_WIFI_PROMPT_CELLULAR
                || DbDownloadPolicy.isUnmetered(context);
        publish(build(meterOk ? DbStatus.State.NEEDS_DOWNLOAD : DbStatus.State.WAITING_NETWORK,
                manifest.bytes, -1L, null));
        return false;
    }

    /**
     * 本地文件是不是「不是我们装的」。
     *
     * <p>不能靠现算 sha256——那要读 2 MB（子集）到 115 MB（完整）的整个文件，
     * 而这是在每次版本检查的路径上。改为比对**安装时记下来的那几个廉价特征**：
     * built_at + subset + 文件长度。任何一个对不上就说明文件被换过了。
     * 换成一个内容完全相同但来源不同的文件是检测不到的——那种情况无害。
     */
    private boolean isForeign(@NonNull PoetryDatabase.Meta meta) {
        String sha = prefs.getString(KEY_SHA, null);
        if (sha == null || sha.isEmpty()) {
            return true;                           // 从没下载安装过
        }
        if (meta.builtAt == null || !meta.builtAt.equals(prefs.getString(KEY_BUILT_AT, null))) {
            return true;
        }
        if (meta.subset != prefs.getBoolean(KEY_SUBSET, false)) {
            return true;
        }
        // 用 installTarget 而不是 locate：前者不碰 assets，问路径这件事不该触发 110 MB 的拷贝。
        // 文件不在时 length() 是 0，跟记录里的 size 对不上，正好也判成 FOREIGN。
        File file = DatabaseProvider.installTarget(context, DatabaseProvider.POETRY_DB);
        return file.length() != prefs.getLong(KEY_SIZE, -1L);
    }

    // ------------------------------------------------------------ 下载与安装

    private void doDownloadAndInstall(@NonNull DbManifest manifest) throws ApiException {
        File target = DatabaseProvider.installTarget(context, DatabaseProvider.POETRY_DB);
        String sha8 = HttpDownloader.sha8(manifest.sha256);
        DatabaseProvider.gcStaleParts(target, sha8);
        File part = DatabaseProvider.partFile(target, sha8);

        checkSpace(target, manifest.bytes);

        long already = part.exists() ? part.length() : 0L;
        Log.i(TAG, "开始下载 " + manifest.url + " -> " + part.getName()
                + (already > 0 ? "（续传自 " + already + " 字节）" : ""));

        final HttpDownloader downloader = new HttpDownloader();
        activeDownload = downloader;
        publish(build(DbStatus.State.DOWNLOADING, already, manifest.bytes, null));

        // 下载是阻塞式的，三个回调都在**这条 worker 线程**上触发，所以下面用一个闩
        // 把它折成直线代码，不必把后续步骤全塞进回调里。
        final AtomicReference<ApiException> failure = new AtomicReference<>();
        final AtomicReference<File> finished = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        final long totalBytes = manifest.bytes;
        downloader.download(manifest.url, part, manifest.bytes, manifest.sha256,
                new DownloadCallback() {
                    @Override
                    public void onProgress(long done, long total) {
                        publish(build(DbStatus.State.DOWNLOADING, done,
                                total > 0 ? total : totalBytes, null));
                    }

                    @Override
                    public void onFinished(@NonNull File file, @NonNull String sha256) {
                        finished.set(file);
                        latch.countDown();
                    }

                    @Override
                    public void onFailed(@NonNull ApiException error) {
                        failure.set(error);
                        latch.countDown();
                    }
                });
        await(latch);

        try {
            activeDownload = null;
            ApiException error = failure.get();
            if (error != null) {
                if (error.getCode() == ApiException.CANCELLED) {
                    // 取消不是失败：退回「可下载」，碎片留着续传
                    Log.i(TAG, "下载已取消，碎片保留");
                    publish(build(DbStatus.State.NEEDS_DOWNLOAD, 0L, manifest.bytes, null));
                } else {
                    fail(error);
                }
                return;
            }

            File downloaded = finished.get();
            if (downloaded == null) {
                throw new ApiException(ApiException.NETWORK, "下载没有产出文件");
            }

            // sha 已在 HttpDownloader 里校验过，这里补结构探针。
            publish(build(DbStatus.State.VERIFYING, manifest.bytes, manifest.bytes, null));
            DbValidator.check(downloaded);

            install(downloaded, target, manifest);
        } finally {
            activeDownload = null;
        }
    }

    /**
     * 换文件 + 重开库。**整体作为 {@code AppExecutors.io} 上的一个任务**执行。
     *
     * <p>为什么非要切过去：如果在这条 worker 线程上 {@code close()}，而 IO 线程正在
     * {@code rawQuery}，{@code SQLiteClosable} 的引用计数会推迟真正的关闭、但
     * {@code isOpen()} 立刻变 false，后续查询会抛 {@code IllegalStateException}。
     * 放在 IO 线程上，关闭就天然排在所有查询后面。
     */
    private void install(@NonNull final File part, @NonNull final File target,
                         @NonNull final DbManifest manifest) throws ApiException {
        publish(build(DbStatus.State.INSTALLING, manifest.bytes, manifest.bytes, null));

        // 整段作为 AppExecutors.io 上的**一个任务**——理由见 installOnIo。
        onIo(() -> {
            installOnIo(part, target, manifest);
            return null;
        });

        pendingManifest = null;
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
        publish(build(DbStatus.State.INSTALLED, manifest.bytes, manifest.bytes, null));
    }

    /** 真正换文件的那三行，**只能在 IO 线程上跑**。 */
    private void installOnIo(@NonNull File part, @NonNull File target,
                             @NonNull DbManifest manifest) throws ApiException {
        // 顺序不能变：先标脏（此刻起的任何 ensureOpen 都会重新打开），再 rename，
        // 最后 reload。反过来做的话，中间那段时间里 ensureOpen() 可能打开一个还没挂好的名字。
        database.markStale();

        try {
            // Os.rename 失败会抛 ErrnoException，而 File.renameTo 只是**静默**返回 false。
            // 这条路径上静默失败意味着「以为装好了其实没有」，绝不能接受。
            Os.rename(part.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new ApiException(ApiException.NETWORK, "替换诗库失败: " + e.getMessage(), e);
        }

        boolean ready = database.reload();
        prefs.edit()
                .putString(KEY_SHA, manifest.sha256)
                .putString(KEY_BUILT_AT, manifest.builtAt)
                .putBoolean(KEY_SUBSET, manifest.isSubset)
                .putLong(KEY_SIZE, target.length())
                .apply();
        Log.i(TAG, "诗库已安装: " + target.getAbsolutePath()
                + " ready=" + ready + " " + target.length() + " 字节");
        if (!ready) {
            throw new ApiException(ApiException.SCHEMA, "诗库已替换但打不开");
        }
    }

    private void checkSpace(@NonNull File target, long bytes) throws ApiException {
        File dir = target.getParentFile();
        if (dir == null) {
            throw new ApiException(ApiException.NO_SPACE, "找不到安装目录");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new ApiException(ApiException.NO_SPACE, "无法创建安装目录: " + dir);
        }
        // StatFs 必须建在**安装目录**上：外部目录可能在 SD 卡，那是另一个卷。
        // 用 getAvailableBytes 而不是 getFreeBytes（后者把 root 保留块也算进来了）。
        long free = new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        // 原地替换时旧库**仍然占着空间**，所以要求是 free >= bytes + slack，
        // 而不是 bytes - oldBytes（生产情况是 115 MB 旧库和 115 MB 新库并存）。
        long need = bytes + Math.max(MIN_SLACK, bytes / 10);
        if (free < need) {
            throw new ApiException(ApiException.NO_SPACE,
                    "存储空间不足：需要约 " + mb(need) + "，可用 " + mb(free));
        }
    }

    // ------------------------------------------------------------ 状态投递

    @NonNull
    private DbStatus build(@NonNull DbStatus.State state, long done, long total,
                           @Nullable String error) {
        // 构造 DbStatus 不碰磁盘：isReady/filePresent 都是字段读取。
        return new DbStatus(state, database.isReady(), database.filePresent(),
                done, total, error);
    }

    private void fail(@NonNull ApiException error) {
        Log.w(TAG, "诗库操作失败: " + error.getMessage(), error);
        pendingManifest = null;
        publish(build(DbStatus.State.FAILED, 0L, -1L, error.getMessage()));
    }

    private void publish(@NonNull DbStatus next) {
        status = next;
        for (Listener listener : listeners) {
            publish(listener, next);
        }
    }

    private void publish(@NonNull final Listener listener, @NonNull final DbStatus next) {
        AppExecutors.get().main(() -> listener.onDbStatus(next));
    }

    // ------------------------------------------------------------ 工具

    /**
     * 把一段「碰数据库」的活儿丢到 {@code AppExecutors.io} 上跑，并在这里等它出结果。
     *
     * <p>为什么非要绕这一下：{@code AppExecutors.io} 是**所有查询的唯一入口**，而
     * {@code ensureOpen()} 在发现代数和句柄对不上时会 {@code closeLocked()}。那一下如果发生在
     * 本线程，正好撞上 IO 线程的 {@code rawQuery}，就落进 {@link PoetryDatabase} 注释里
     * 描述的那个 {@code SQLiteClosable} 引用计数竞态：{@code isOpen()} 立刻变 false 而真正的
     * 关闭被推迟，后续查询抛 {@code IllegalStateException}。丢到那条队上就天然串行。
     *
     * <p>不会死锁：IO 线程从不反过来等本线程。
     */
    @Nullable
    private <T> T onIo(@NonNull Callable<T> task) throws ApiException {
        final AtomicReference<T> value = new AtomicReference<>();
        final AtomicReference<ApiException> failure = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        AppExecutors.get().io(() -> {
            try {
                value.set(task.call());
            } catch (ApiException e) {
                failure.set(e);
            } catch (Exception e) {
                failure.set(new ApiException(ApiException.NETWORK, "诗库操作失败: " + e.getMessage(), e));
            } finally {
                latch.countDown();
            }
        });
        await(latch);
        ApiException error = failure.get();
        if (error != null) {
            throw error;
        }
        return value.get();
    }

    /**
     * 等一个闩。**超时要抛**，不能只记一行日志就往下走——那会让「安装任务根本没跑完」
     * 变成一句「已安装成功」。
     */
    private static void await(@NonNull CountDownLatch latch) throws ApiException {
        try {
            // 兜底上限，远大于任何一次真实操作（下载有大文件，给到 10 分钟）
            if (!latch.await(10, TimeUnit.MINUTES)) {
                throw new ApiException(ApiException.NETWORK, "等待诗库任务超时");
            }
        } catch (InterruptedException e) {
            // 中断标志要还回去：吞掉它会让线程池永远关不掉
            Thread.currentThread().interrupt();
            throw new ApiException(ApiException.CANCELLED, "诗库任务被中断", e);
        }
    }

    @NonNull
    private static String mb(long bytes) {
        return String.format(java.util.Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}

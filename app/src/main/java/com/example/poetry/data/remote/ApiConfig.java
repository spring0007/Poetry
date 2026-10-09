package com.example.poetry.data.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.BuildConfig;

/**
 * 后端接口配置。
 *
 * <p>地址与总闸来自 {@code BuildConfig}（见 {@code app/build.gradle.kts}），
 * 从 {@code local.properties} 读，不进版本库。默认值是 {@code http://10.0.2.2:8000/}，
 * 也就是「模拟器访问宿主机上的本地后端」，这样 clone 下来直接能跑通联调。
 *
 * <p>关掉 {@link #ENABLED} 就彻底退回纯离线：{@code ApiClient} 会直接抛
 * {@code ApiException.NOT_IMPLEMENTED}，一条 socket 都不建。调试时用它快速区分
 * 「问题在网络链路」还是「问题在本地逻辑」。
 */
public final class ApiConfig {

    /** 后端是否参与分流 */
    public static final boolean ENABLED = BuildConfig.API_ENABLED;

    /**
     * 应用包名（{@code com.example.poetry}）。
     *
     * <p>它承担两件事：一是作为构建期参数的**命名空间** ——
     * {@code local.properties} 里的 {@code com.example.poetry.API_BASE_URL}
     * 优先于通用的 {@code API_BASE_URL}（见 {@code app/build.gradle.kts} 的 {@code prop()}）；
     * 二是随每个请求发出（{@code X-Client-Package} 头），
     * 服务端日志据此能分清一次调用来自哪个 App —— 多端共用一个后端时这一点很关键。
     */
    @NonNull
    public static final String APP_PACKAGE = BuildConfig.APP_PACKAGE;

    /**
     * 服务根地址，**必定以 {@code /} 结尾**。
     *
     * <p>不做这个归一化的话，{@code local.properties} 里少写一个斜杠就会拼出
     * {@code http://host:8000v1/search} 这种地址，而报错是 404 —— 排查起来很费时间。
     */
    @NonNull
    public static final String BASE_URL = normalizeBase(BuildConfig.API_BASE_URL);

    // ------------------------------------------------------------ 超时与熔断

    /**
     * 连接超时。给得**短**是刻意的：
     *
     * <p>后端不可达时（服务器没开、地址填错、Wi-Fi 连着但没有出口），
     * 用户要的是「赶紧给我看本地库」，而不是对着转圈等十秒。
     * 本地开发环境握手是毫秒级的，3 秒足够；真连不上，早点失败更友好。
     */
    public static final int CONNECT_TIMEOUT_MS = 3_000;

    /** 读超时。检索接口在 357k 行的库上要跑一会儿，留足 8 秒 */
    public static final int READ_TIMEOUT_MS = 8_000;

    /**
     * 连续失败多少次后熔断。
     *
     * <p>没有熔断的话，后端挂掉期间**每一次查询**都要先等满 3 秒连接超时，
     * 界面处处卡顿，用户会以为 App 坏了。熔断后进入冷却期，请求直接快速失败，
     * 本地库瞬间接管。
     */
    public static final int BREAKER_THRESHOLD = 3;

    /** 熔断冷却时长。冷却结束后放一个请求出去探路（半开）。 */
    public static final long BREAKER_COOLDOWN_MS = 30_000L;

    // ------------------------------------------------------------ 端点
    //
    // 全部与 E:/Poetry_Server/internal/handler/router.go 里的路由逐字对应。
    // 带 {xxx} 的是路径参数，由 ApiClient.buildUrl 替换。

    /** GET 综合检索 ?q=&page=&size= */
    public static final String API_SEARCH = "v1/search";
    /** GET 热门搜索词 ?limit= */
    public static final String API_SEARCH_HOT = "v1/search/hot";
    /** GET 检索联想 ?q=&limit= */
    public static final String API_SEARCH_SUGGEST = "v1/search/suggest";

    /** GET 热度榜 ?page=&size= */
    public static final String API_FEATURED = "v1/featured";
    /** GET 每日推荐 ?exclude=<uid> */
    public static final String API_DAILY = "v1/daily";
    /** GET 随机一首 ?exclude=<uid> */
    public static final String API_RANDOM = "v1/random";

    /** GET 作品详情 v1/poems/{ref}（ref 可以是数字 id 或 uid） */
    public static final String API_POEM_DETAIL = "v1/poems/{ref}";
    /** GET 译文 v1/poems/{ref}/translation */
    public static final String API_TRANSLATION = "v1/poems/{ref}/translation";
    /** GET 赏析 v1/poems/{ref}/appreciation */
    public static final String API_APPRECIATION = "v1/poems/{ref}/appreciation";
    /** GET 平仄 v1/poems/{ref}/strains */
    public static final String API_STRAINS = "v1/poems/{ref}/strains";

    /** GET 按体裁列表 v1/list/kind/{code} ?page=&size= */
    public static final String API_LIST_KIND = "v1/list/kind/{code}";
    /** GET 按朝代列表 v1/list/dynasty/{code} ?page=&size= */
    public static final String API_LIST_DYNASTY = "v1/list/dynasty/{code}";

    /** GET 热门作者 ?limit=&offset= */
    public static final String API_AUTHORS = "v1/authors";
    /** GET 作者详情 v1/authors/{ref} */
    public static final String API_AUTHOR_DETAIL = "v1/authors/{ref}";
    /** GET 作者作品 v1/authors/{ref}/poems ?page=&size= */
    public static final String API_AUTHOR_POEMS = "v1/authors/{ref}/poems";

    /** GET 分类计数与总量（一次拿全，客户端首屏不用串行发三次） */
    public static final String API_STATS = "v1/stats";
    /** GET 标签热度榜 ?limit= */
    public static final String API_TAGS = "v1/tags";

    /** GET 云端音色列表 */
    public static final String API_VOICES = "v1/tts/voices";
    /** POST 合成音频 {poemRef, voiceId, speed} → {url, cached, bytes} */
    public static final String API_TTS = "v1/tts/synthesize";

    /** POST 下发登录验证码 {phone} */
    public static final String API_AUTH_CODE = "v1/auth/code";
    /** POST 手机号登录/注册 {phone, code, nickname} */
    public static final String API_AUTH_LOGIN = "v1/auth/login";
    /** POST 续签（需带 token） */
    public static final String API_AUTH_REFRESH = "v1/auth/refresh";

    /** GET 个人资料 */
    public static final String API_PROFILE = "v1/user/profile";
    /** GET 书架列表 ?relType=&page=&size= */
    public static final String API_LIBRARY = "v1/user/library";
    /** POST 书架批量同步 */
    public static final String API_LIBRARY_SYNC = "v1/user/library/sync";
    /** POST 朗读埋点 */
    public static final String API_PLAY_LOG = "v1/user/play-log";

    /**
     * 诗库清单文件。挂在后端的静态目录下（{@code /static}），
     * 不是根路径 —— 换后端时最容易漏掉的就是这个 {@code static/} 前缀。
     */
    public static final String API_MANIFEST = "static/version.json";

    private ApiConfig() {
    }

    /** 拼接完整地址。{@code endpoint} 不要以 {@code /} 开头。 */
    @NonNull
    public static String url(@NonNull String endpoint) {
        return BASE_URL + endpoint;
    }

    /**
     * 把 {@code endpoint} 里的 {@code {name}} 占位符换成实参。
     *
     * <p>值会做 URL 编码：uid 是 uuid 本来安全，但朝代 / 体裁 code 来自界面，
     * 将来可能带上中文，不编码会拼出非法 URL。
     */
    @NonNull
    public static String path(@NonNull String endpoint, @NonNull String name,
                              @Nullable String value) {
        String v = value == null ? "" : value;
        return endpoint.replace("{" + name + "}", encodePath(v));
    }

    /**
     * 归一化根地址：补上缺失的协议头与结尾斜杠。
     *
     * <p>两种情况都见过：{@code local.properties} 里写成 {@code 192.168.1.8:8000}
     * （少了 {@code http://}），或者以 {@code /} 结尾写成 {@code .../v1/}。
     * 与其让人对着一个 404 猜，不如在这里一次修好。
     */
    @NonNull
    private static String normalizeBase(@Nullable String raw) {
        String base = raw == null ? "" : raw.trim();
        if (base.isEmpty()) {
            return "";
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "http://" + base;
        }
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return base;
    }

    /** 路径段编码。{@code URLEncoder} 是 form 编码（空格变 {@code +}），路径段要的是 {@code %20}。 */
    @NonNull
    private static String encodePath(@NonNull String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }
}

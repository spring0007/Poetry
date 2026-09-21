package com.example.poetry.data.remote;

/**
 * 后台接口配置（占位）。
 * <p>
 * 当前后台尚未搭建，{@link #ENABLED} 为 false，
 * 所有请求直接走 {@link RemotePoetrySource} 的未实现分支，App 完全依赖本地诗库运行。
 * <p>
 * 后台就绪后的接入步骤：
 * <ol>
 *   <li>把 {@link #BASE_URL} 改成真实域名；</li>
 *   <li>把 {@link #ENABLED} 改为 true（或按 BuildConfig.DEBUG 分别配置）；</li>
 *   <li>把 {@link RemotePoetrySource} 换成 Retrofit / OkHttp 的真实实现
 *       （建议实现同一个 {@link PoetryApi} 接口，Repository 侧无需改动）。</li>
 * </ol>
 */
public final class ApiConfig {

    /** 后台是否可用：false = 全部走本地库 */
    public static final boolean ENABLED = false;

    /** 服务地址（示例，接入时替换） */
    public static final String BASE_URL = "https://api.example.com/poetry/";

    /** 接口超时（毫秒） */
    public static final int TIMEOUT_MS = 10_000;

    // ------------------------------------------------------------ 端点

    /** GET 综合检索：?q=&page=&size= */
    public static final String API_SEARCH = "v1/poems/search";
    /** GET 作品详情：v1/poems/{id} */
    public static final String API_POEM_DETAIL = "v1/poems/{id}";
    /** GET 译文：v1/poems/{id}/translation */
    public static final String API_TRANSLATION = "v1/poems/{id}/translation";
    /** GET 赏析：v1/poems/{id}/appreciation */
    public static final String API_APPRECIATION = "v1/poems/{id}/appreciation";
    /** GET 每日推荐 */
    public static final String API_DAILY = "v1/poems/daily";
    /** GET 热门搜索词 */
    public static final String API_HOT_KEYWORDS = "v1/search/hot";
    /** GET 云端音色列表 */
    public static final String API_VOICES = "v1/tts/voices";
    /** POST 合成音频：返回音频地址 */
    public static final String API_TTS = "v1/tts/synthesize";
    /** GET/POST 用户书架同步 */
    public static final String API_LIBRARY = "v1/user/library";
    /** POST 朗读埋点 */
    public static final String API_PLAY_LOG = "v1/user/play-log";

    private ApiConfig() {
    }

    /** 拼接完整地址（简单占位实现，接入时可换成 Retrofit 的 @Url） */
    public static String url(String endpoint) {
        return BASE_URL + endpoint;
    }
}

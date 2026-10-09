package com.example.poetry.data.remote;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.model.Author;
import com.example.poetry.data.model.Category;
import com.example.poetry.data.model.Dynasty;
import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.Voice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@link PoetryApi} 的真实实现：把每个方法映射到一个后端接口。
 *
 * <p>这一层刻意**只做搬运**——拼参数、发请求、把 JSON 交给 {@link JsonMapper}。
 * 任何「失败了怎么办」的决策都不在这里：那是 {@code PoetryRepository} 的活
 * （远端 → 本地库 → 内置示例三级回退）。这样划分之后，
 * 想改成别的后端（或写一个假实现跑测试）只需要换这一个类。
 */
public final class HttpPoetryApi implements PoetryApi {

    /**
     * {@code /v1/stats} 的客户端缓存时长。
     *
     * <p>发现页和分类页会分别要体裁宫格、朝代宫格、总篇数，三个都读同一个接口，
     * 而 {@code totalCount()} 又会被「我的」页单独问一次。服务端本来就有 10 分钟缓存，
     * 但这一层不挡一下的话，一次冷启动仍会发出去 4 个一模一样的请求。
     */
    private static final long STATS_TTL_MS = 60_000L;

    private final ApiClient client;
    private final Context appContext;

    /** stats 的本地副本。只在 IO 线程读写（所有接口都约定跑在 IO 线程）。 */
    @Nullable
    private JSONObject statsCache;
    private long statsCacheAt;

    public HttpPoetryApi(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.client = new ApiClient(context);
    }

    // ------------------------------------------------------------ 状态

    @Override
    public boolean isEnabled() {
        return client.isEnabled();
    }

    @Override
    public boolean isReachableNow() {
        return client.isEnabled()
                && !ApiClient.isBreakerOpen()
                && DbDownloadPolicy.isOnline(appContext);
    }

    @NonNull
    @Override
    public String statusLabel() {
        if (!ApiConfig.ENABLED) {
            return "已关闭（纯离线）";
        }
        if (ApiConfig.BASE_URL.isEmpty()) {
            return "未配置服务地址";
        }
        if (ApiClient.isBreakerOpen()) {
            return "暂时不可用，"
                    + (ApiClient.breakerRemainingMs() / 1000 + 1) + " 秒后重试";
        }
        if (!DbDownloadPolicy.isOnline(appContext)) {
            return "无网络连接";
        }
        return "已连接 " + ApiConfig.BASE_URL;
    }

    // ------------------------------------------------------------ 内容

    @NonNull
    @Override
    public List<Poem> search(@NonNull String keyword, int page, int pageSize) throws ApiException {
        Object data = client.get(ApiConfig.API_SEARCH, ApiClient.query(
                "q", keyword,
                "page", String.valueOf(Math.max(1, page)),
                "size", String.valueOf(clampSize(pageSize))));
        return JsonMapper.toPoemsFromPage(data);
    }

    @NonNull
    @Override
    public List<Poem> featured(int page, int pageSize) throws ApiException {
        Object data = client.get(ApiConfig.API_FEATURED, ApiClient.query(
                "page", String.valueOf(Math.max(1, page)),
                "size", String.valueOf(clampSize(pageSize))));
        return JsonMapper.toPoemsFromPage(data);
    }

    @Nullable
    @Override
    public Poem daily(@Nullable String excludeUid) throws ApiException {
        return JsonMapper.toPoemOrNull(client.get(ApiConfig.API_DAILY, exclude(excludeUid)));
    }

    @Nullable
    @Override
    public Poem random(@Nullable String excludeUid) throws ApiException {
        return JsonMapper.toPoemOrNull(client.get(ApiConfig.API_RANDOM, exclude(excludeUid)));
    }

    @Nullable
    @Override
    public Poem detail(@NonNull String ref) throws ApiException {
        if (ref.trim().isEmpty()) {
            return null;
        }
        String path = ApiConfig.path(ApiConfig.API_POEM_DETAIL, "ref", ref);
        return JsonMapper.toPoemOrNull(client.get(path));
    }

    @NonNull
    @Override
    public String translation(@NonNull String ref) throws ApiException {
        return textOf(ApiConfig.path(ApiConfig.API_TRANSLATION, "ref", ref));
    }

    @NonNull
    @Override
    public String appreciation(@NonNull String ref) throws ApiException {
        return textOf(ApiConfig.path(ApiConfig.API_APPRECIATION, "ref", ref));
    }

    /**
     * 平仄串。
     *
     * <p>接口同时给 {@code dataHex} 与 {@code dataText}：后者只在源数据恰好是
     * 可打印 ASCII 时才有值。客户端要的正是这一种（平仄展示与朗读断句都读它），
     * 拿不到就当「这首诗没有平仄数据」——不自己解 hex，那是服务端该统一的口径。
     */
    @NonNull
    @Override
    public String strain(@NonNull String ref) throws ApiException {
        String path = ApiConfig.path(ApiConfig.API_STRAINS, "ref", ref);
        JSONObject o = ApiClient.asObject(client.get(path));
        return o.optString("dataText", "");
    }

    @NonNull
    @Override
    public List<Poem> listByKind(@NonNull String kindCode, int page, int pageSize)
            throws ApiException {
        String path = ApiConfig.path(ApiConfig.API_LIST_KIND, "code", kindCode);
        Object data = client.get(path, ApiClient.query(
                "page", String.valueOf(Math.max(1, page)),
                "size", String.valueOf(clampSize(pageSize))));
        return JsonMapper.toPoemsFromPage(data);
    }

    @NonNull
    @Override
    public List<Poem> listByDynasty(@NonNull String dynastyCode, int page, int pageSize)
            throws ApiException {
        String path = ApiConfig.path(ApiConfig.API_LIST_DYNASTY, "code", dynastyCode);
        Object data = client.get(path, ApiClient.query(
                "page", String.valueOf(Math.max(1, page)),
                "size", String.valueOf(clampSize(pageSize))));
        return JsonMapper.toPoemsFromPage(data);
    }

    @NonNull
    @Override
    public List<Author> topAuthors(int limit) throws ApiException {
        Object data = client.get(ApiConfig.API_AUTHORS,
                ApiClient.query("limit", String.valueOf(Math.max(1, limit))));
        return JsonMapper.toAuthors(asArray(data));
    }

    @Nullable
    @Override
    public Author author(@NonNull String ref) throws ApiException {
        if (ref.trim().isEmpty()) {
            return null;
        }
        String path = ApiConfig.path(ApiConfig.API_AUTHOR_DETAIL, "ref", ref);
        return JsonMapper.toAuthorOrNull(client.get(path));
    }

    @NonNull
    @Override
    public List<Poem> authorPoems(@NonNull String authorRef, int limit) throws ApiException {
        String path = ApiConfig.path(ApiConfig.API_AUTHOR_POEMS, "ref", authorRef);
        Object data = client.get(path, ApiClient.query(
                "page", "1",
                "size", String.valueOf(clampSize(limit))));
        // 这个接口的形状是 {author, items, page, size}，不是标准 Paged（没有 total）
        return JsonMapper.toPoems(ApiClient.asObject(data).optJSONArray("items"));
    }

    @NonNull
    @Override
    public List<Category> kindCategories() throws ApiException {
        return JsonMapper.toKindCategories(stats().optJSONArray("kinds"));
    }

    /**
     * 朝代宫格。
     *
     * <p>接口返回的朝代是无序的（来自 GROUP BY），这里按 {@code Dynasty.values()} 重排：
     * 宫格的视觉顺序是设计稿定的，不该随一次 SQL 的执行计划变化。
     */
    @NonNull
    @Override
    public List<Category> dynastyCategories() throws ApiException {
        List<Category> remote = JsonMapper.toDynastyCategories(stats().optJSONArray("dynasties"));
        return sortByDynastyOrder(remote);
    }

    @Override
    public long totalCount() throws ApiException {
        return stats().optLong("totalPoems", -1L);
    }

    @NonNull
    @Override
    public List<String> hotKeywords(int limit) throws ApiException {
        Object data = client.get(ApiConfig.API_SEARCH_HOT,
                ApiClient.query("limit", String.valueOf(Math.max(1, limit))));
        return JsonMapper.toKeywords(asArray(data));
    }

    // ------------------------------------------------------------ 语音

    @NonNull
    @Override
    public List<Voice> voices() throws ApiException {
        return JsonMapper.toVoices(asArray(client.get(ApiConfig.API_VOICES)));
    }

    @NonNull
    @Override
    public String synthesize(@NonNull String poemRef, @NonNull String voiceId, float speed)
            throws ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("poemRef", poemRef);
            body.put("voiceId", voiceId);
            body.put("speed", speed <= 0 ? 1.0 : speed);
        } catch (Exception e) {
            throw new ApiException(ApiException.PARSE, "构造请求体失败", e);
        }
        JSONObject o = ApiClient.asObject(client.post(ApiConfig.API_TTS, body));
        String url = o.optString("url", "");
        if (url.isEmpty()) {
            throw new ApiException(ApiException.PARSE, "服务端没有返回音频地址");
        }
        // 后端给的是 /tts/xxx.mp3 这样的根相对路径，播放器要绝对地址
        return client.absolute(url);
    }

    // ------------------------------------------------------------ 账号

    /**
     * 下发验证码。
     *
     * @return dev 模式下服务端回显的固定验证码（生产环境恒为空串），
     *         登录页可以据此自动填上，省掉真机联调时「短信收不到」的死循环。
     */
    @NonNull
    @Override
    public String sendCode(@NonNull String phone) throws ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("phone", phone);
        } catch (Exception e) {
            throw new ApiException(ApiException.PARSE, "构造请求体失败", e);
        }
        JSONObject o = ApiClient.asObject(client.post(ApiConfig.API_AUTH_CODE, body));
        return o.optString("devCode", "");
    }

    @NonNull
    @Override
    public LoginResult login(@NonNull String phone, @NonNull String code,
                             @Nullable String nickname) throws ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("phone", phone);
            body.put("code", code);
            if (nickname != null && !nickname.trim().isEmpty()) {
                body.put("nickname", nickname.trim());
            }
        } catch (Exception e) {
            throw new ApiException(ApiException.PARSE, "构造请求体失败", e);
        }

        JSONObject o = ApiClient.asObject(client.post(ApiConfig.API_AUTH_LOGIN, body));
        String token = o.optString("token", "");
        if (token.isEmpty()) {
            throw new ApiException(ApiException.PARSE, "服务端没有返回登录凭证");
        }
        JSONObject user = o.optJSONObject("user");
        long userId = user == null ? 0L : user.optLong("id", 0L);
        String name = user == null ? "" : user.optString("nickname", "");
        return new LoginResult(token, userId, name, o.optBoolean("created", false),
                Jwt.expiryMillis(token));
    }

    @Override
    public void reportPlay(@Nullable String poemUid, @Nullable String voiceId, int durationMs)
            throws ApiException {
        if (poemUid == null || poemUid.isEmpty()) {
            // 内置示例数据没有 uid —— 后端按 uid 关联，报了也是脏数据，直接跳过
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("poemUid", poemUid);
            body.put("voiceId", voiceId == null ? "" : voiceId);
            body.put("duration", Math.max(0, durationMs));
        } catch (Exception e) {
            throw new ApiException(ApiException.PARSE, "构造请求体失败", e);
        }
        client.post(ApiConfig.API_PLAY_LOG, body);
    }

    @Override
    public boolean syncLibrary(@NonNull JSONArray items) throws ApiException {
        if (items.length() == 0) {
            return true;   // 本地没东西可推，不必白发一个空请求
        }
        JSONObject body = new JSONObject();
        try {
            body.put("items", items);
        } catch (Exception e) {
            throw new ApiException(ApiException.PARSE, "构造同步请求失败", e);
        }
        client.post(ApiConfig.API_LIBRARY_SYNC, body);
        return true;
    }

    // ------------------------------------------------------------ 内部工具

    /** 译文 / 赏析这类「只回一段文本」的接口。 */
    @NonNull
    private String textOf(@NonNull String path) throws ApiException {
        return ApiClient.asObject(client.get(path)).optString("text", "");
    }

    /** 取（并缓存）{@code /v1/stats}。 */
    @NonNull
    private JSONObject stats() throws ApiException {
        long now = SystemClock.elapsedRealtime();
        JSONObject cached = statsCache;
        if (cached != null && now - statsCacheAt < STATS_TTL_MS) {
            return cached;
        }
        JSONObject fresh = ApiClient.asObject(client.get(ApiConfig.API_STATS));
        statsCache = fresh;
        statsCacheAt = now;
        return fresh;
    }

    @Nullable
    private static Map<String, String> exclude(@Nullable String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return null;
        }
        // 后端拿 exclude 和诗库里的 uid 直接比字符串，数字 id 在这里没意义
        return ApiClient.query("exclude", uid.trim());
    }

    /** 后端 {@code ClampPage} 的上限是 100，发更大的值只会被静默截断，不如在这里对齐。 */
    private static int clampSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, 100);
    }

    @NonNull
    private static JSONArray asArray(@Nullable Object data) {
        return data instanceof JSONArray ? (JSONArray) data : new JSONArray();
    }

    /** 按 {@code Dynasty.values()} 的声明顺序重排朝代宫格。未知 code 落到 UNKNOWN，排在最后。 */
    @NonNull
    private static List<Category> sortByDynastyOrder(@NonNull List<Category> list) {
        Collections.sort(list, new Comparator<Category>() {
            @Override
            public int compare(Category a, Category b) {
                return Integer.compare(Dynasty.fromCode(a.getCode()).ordinal(),
                        Dynasty.fromCode(b.getCode()).ordinal());
            }
        });
        return list;
    }
}

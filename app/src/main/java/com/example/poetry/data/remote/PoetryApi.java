package com.example.poetry.data.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.model.Author;
import com.example.poetry.data.model.Category;
import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.Voice;

import org.json.JSONArray;

import java.util.List;

/**
 * 后端接口契约。
 *
 * <p>设计成**同步阻塞 + 抛异常**，而不是回调式：调用方是
 * {@code PoetryRepository}，它跑在 {@code AppExecutors.io()} 上，需要的是
 * 「拿不到就抛、我接住去查本地库」这种直线写法。
 * 回调式会把这条三级回退拆成一堆嵌套的 onFailure，读起来完全不是那个意思。
 *
 * <p>所有方法都必须在**非主线程**调用。
 *
 * <p>实现见 {@link HttpPoetryApi}。测试或降级场景可以另写一个实现，
 * 仓库层不必改。
 */
public interface PoetryApi {

    /** 后端是否已配置并启用 */
    boolean isEnabled();

    /** 现在能不能发请求（关掉了、熔断了都返回 false） */
    boolean isReachableNow();

    /** 人话描述当前链路状态，给「关于」页用 */
    @NonNull
    String statusLabel();

    // ------------------------------------------------------------ 内容

    /**
     * 综合检索（作者名 → 标题 → 正文）。
     *
     * @param page 从 1 开始
     */
    @NonNull
    List<Poem> search(@NonNull String keyword, int page, int pageSize) throws ApiException;

    /** 热度榜（发现页默认列表 / 「热门」入口） */
    @NonNull
    List<Poem> featured(int page, int pageSize) throws ApiException;

    /**
     * 每日推荐（同一天内所有人拿到同一首）。返回完整详情，可直接进详情页。
     *
     * @param excludeUid 要避开的那首的 uid。**必须是 uid**：后端拿它和
     *                   {@code poems.uid} 直接比字符串，传数字 id 会被当成「没有排除项」，
     *                   于是用户可能拿到正在看的那一首。传 null 表示不排除。
     */
    @Nullable
    Poem daily(@Nullable String excludeUid) throws ApiException;

    /** 随机一首（「换一首」按钮）。{@code excludeUid} 同 {@link #daily}。 */
    @Nullable
    Poem random(@Nullable String excludeUid) throws ApiException;

    /** 作品详情。{@code ref} 可以是数字 id 或 uid。 */
    @Nullable
    Poem detail(@NonNull String ref) throws ApiException;

    /** 译文；没有时返回空串 */
    @NonNull
    String translation(@NonNull String ref) throws ApiException;

    /** 赏析；没有时返回空串 */
    @NonNull
    String appreciation(@NonNull String ref) throws ApiException;

    /** 平仄串（源库里的 {@code dataText} 形态）；没有时返回空串 */
    @NonNull
    String strain(@NonNull String ref) throws ApiException;

    /** 按体裁列作品（{@code kindCode} 见 {@code PoemKind}） */
    @NonNull
    List<Poem> listByKind(@NonNull String kindCode, int page, int pageSize) throws ApiException;

    /** 按朝代列作品（{@code dynastyCode} 见 {@code Dynasty}） */
    @NonNull
    List<Poem> listByDynasty(@NonNull String dynastyCode, int page, int pageSize) throws ApiException;

    /** 热门作者 */
    @NonNull
    List<Author> topAuthors(int limit) throws ApiException;

    /** 作者详情。{@code ref} 可以是 uid 或数字 id。 */
    @Nullable
    Author author(@NonNull String ref) throws ApiException;

    /** 某作者的作品 */
    @NonNull
    List<Poem> authorPoems(@NonNull String authorRef, int limit) throws ApiException;

    /** 体裁宫格（一次拿全，避免串行发三次） */
    @NonNull
    List<Category> kindCategories() throws ApiException;

    /** 朝代宫格 */
    @NonNull
    List<Category> dynastyCategories() throws ApiException;

    /** 内容库总篇数；拿不到时返回 -1 */
    long totalCount() throws ApiException;

    /** 热门搜索词 */
    @NonNull
    List<String> hotKeywords(int limit) throws ApiException;

    // ------------------------------------------------------------ 语音

    /** 云端音色列表（未配置密钥时也会返回，元素 {@code locked=true}） */
    @NonNull
    List<Voice> voices() throws ApiException;

    /**
     * 合成朗读音频，返回**可播放的绝对地址**。
     *
     * @param poemRef 数字 id 或 uid
     * @param speed   语速倍率，{@code <= 0} 按 1.0 处理
     */
    @NonNull
    String synthesize(@NonNull String poemRef, @NonNull String voiceId, float speed)
            throws ApiException;

    // ------------------------------------------------------------ 账号

    /** 下发登录验证码 */
    @NonNull
    String sendCode(@NonNull String phone) throws ApiException;

    /** 手机号登录（不存在则自动注册） */
    @NonNull
    LoginResult login(@NonNull String phone, @NonNull String code, @Nullable String nickname)
            throws ApiException;

    /** 朗读埋点。失败不影响功能，调用方可以忽略异常。 */
    void reportPlay(@Nullable String poemUid, @Nullable String voiceId, int durationMs)
            throws ApiException;

    /**
     * 书架批量上行（收藏 / 阅读记录）。
     *
     * <p>单向：只把本地已有的推上去，不下行覆盖本地。
     * 做成批量而不是「一次收藏一个请求」，是因为离线优先的客户端本来就攒着一批变更，
     * 逐个发会变成几十个请求。
     *
     * @param items 每条含 {@code poemUid} / {@code relType}(1 收藏 2 历史 3 批注) /
     *              {@code content} / {@code progress}。没有 uid 的条目请调用方先剔掉。
     * @return true = 服务端接受并写入
     */
    boolean syncLibrary(@NonNull JSONArray items) throws ApiException;
}

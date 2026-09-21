package com.example.poetry.data.remote;

import androidx.annotation.NonNull;

import com.example.poetry.data.model.Author;
import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.Voice;

import java.util.List;

/**
 * 后台接口契约（预留）。
 * <p>
 * 定义了 App 将来要调用的每一个接口；当前由 {@link RemotePoetrySource} 提供空实现，
 * 后台搭建完成后只需替换实现类，UI 与 Repository 无需改动。
 * <p>
 * 端点与开关见 {@link ApiConfig}。
 */
public interface PoetryApi {

    /** 综合检索（诗题 / 作者 / 名句） */
    void search(@NonNull String keyword, int page, int pageSize, @NonNull ApiCallback<List<Poem>> callback);

    /** 作品详情 */
    void getPoem(long poemId, @NonNull ApiCallback<Poem> callback);

    /** 译文 */
    void getTranslation(long poemId, @NonNull ApiCallback<String> callback);

    /** 赏析 */
    void getAppreciation(long poemId, @NonNull ApiCallback<String> callback);

    /** 每日推荐 */
    void getDailyRecommend(@NonNull ApiCallback<Poem> callback);

    /** 热门搜索词 */
    void getHotKeywords(@NonNull ApiCallback<List<String>> callback);

    /** 云端音色列表 */
    void getVoices(@NonNull ApiCallback<List<Voice>> callback);

    /** 合成朗读音频，返回可播放地址 */
    void synthesize(long poemId, @NonNull String voiceId, @NonNull ApiCallback<String> callback);

    /** 作者详情 */
    void getAuthor(long authorId, @NonNull ApiCallback<Author> callback);

    /** 上传用户书架（收藏 / 历史 / 进度） */
    void syncLibrary(@NonNull String payloadJson, @NonNull ApiCallback<Boolean> callback);

    /** 朗读埋点 */
    void reportPlay(long poemId, @NonNull String voiceId, @NonNull ApiCallback<Boolean> callback);
}

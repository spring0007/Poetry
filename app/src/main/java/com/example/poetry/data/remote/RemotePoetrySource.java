package com.example.poetry.data.remote;

import androidx.annotation.NonNull;

import com.example.poetry.data.AppExecutors;
import com.example.poetry.data.model.Author;
import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.Voice;

import java.util.List;

/**
 * 后台接口的占位实现。
 * <p>
 * 后台还没有搭建，这里每个方法都直接回调 {@code NOT_IMPLEMENTED}，
 * 调用方（Repository）在 {@code ApiConfig.ENABLED == false} 时本来也不会走到这里。
 * <p>
 * 接入真实后台时：
 * <pre>
 *   1. 在 app/build.gradle.kts 加 implementation("com.squareup.retrofit2:retrofit:2.11.0")
 *      + converter-gson（或 moshi）；
 *   2. 新建 RetrofitPoetryApi implements PoetryApi，用 &#64;GET / &#64;POST 标注端点
 *      （端点常量见 ApiConfig）；
 *   3. 在 PoetryRepository 里把 new RemotePoetrySource() 换成 new RetrofitPoetryApi()；
 *   4. 把 ApiConfig.ENABLED 置为 true。
 * </pre>
 */
public class RemotePoetrySource implements PoetryApi {

    @NonNull
    private static ApiException notImplemented(@NonNull String api) {
        return new ApiException(ApiException.NOT_IMPLEMENTED,
                "后台接口尚未接入：" + api);
    }

    private static <T> void fail(@NonNull String api, @NonNull ApiCallback<T> callback) {
        AppExecutors.get().main(() -> callback.onFailure(notImplemented(api)));
    }

    @Override
    public void search(@NonNull String keyword, int page, int pageSize,
                       @NonNull ApiCallback<List<Poem>> callback) {
        fail(ApiConfig.API_SEARCH, callback);
    }

    @Override
    public void getPoem(long poemId, @NonNull ApiCallback<Poem> callback) {
        fail(ApiConfig.API_POEM_DETAIL, callback);
    }

    @Override
    public void getTranslation(long poemId, @NonNull ApiCallback<String> callback) {
        fail(ApiConfig.API_TRANSLATION, callback);
    }

    @Override
    public void getAppreciation(long poemId, @NonNull ApiCallback<String> callback) {
        fail(ApiConfig.API_APPRECIATION, callback);
    }

    @Override
    public void getDailyRecommend(@NonNull ApiCallback<Poem> callback) {
        fail(ApiConfig.API_DAILY, callback);
    }

    @Override
    public void getHotKeywords(@NonNull ApiCallback<List<String>> callback) {
        fail(ApiConfig.API_HOT_KEYWORDS, callback);
    }

    @Override
    public void getVoices(@NonNull ApiCallback<List<Voice>> callback) {
        fail(ApiConfig.API_VOICES, callback);
    }

    @Override
    public void synthesize(long poemId, @NonNull String voiceId, @NonNull ApiCallback<String> callback) {
        fail(ApiConfig.API_TTS, callback);
    }

    @Override
    public void getAuthor(long authorId, @NonNull ApiCallback<Author> callback) {
        fail("v1/authors/{id}", callback);
    }

    @Override
    public void syncLibrary(@NonNull String payloadJson, @NonNull ApiCallback<Boolean> callback) {
        fail(ApiConfig.API_LIBRARY, callback);
    }

    @Override
    public void reportPlay(long poemId, @NonNull String voiceId, @NonNull ApiCallback<Boolean> callback) {
        fail(ApiConfig.API_PLAY_LOG, callback);
    }
}

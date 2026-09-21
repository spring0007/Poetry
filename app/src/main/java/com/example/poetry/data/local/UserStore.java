package com.example.poetry.data.local;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.TtsConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户数据本地存储（SharedPreferences + JSON 快照）。
 * <p>
 * 收藏 / 朗读历史 / 阅读进度 / 朗读偏好 / 打卡统计都落在这里；
 * 之所以存整首诗词的快照，是因为收藏夹在没有本地诗库时也要能展示。
 * <p>
 * TODO（后台接入后）：把 favorites / history / progress 同步到服务端，
 * 对应接口见 {@link com.example.poetry.data.remote.PoetryApi}。
 */
public final class UserStore {

    private static final String PREF = "poetry_user";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_PROGRESS = "progress";
    private static final String KEY_NIGHT = "night_mode";
    private static final String KEY_FONT = "font_scale";
    private static final String KEY_VOICE = "voice_id";
    private static final String KEY_RATE = "speech_rate";
    private static final String KEY_VERTICAL = "vertical_reading";
    private static final String KEY_CHECKIN = "checkin_days";
    private static final String KEY_PLAY_COUNT = "play_count";
    private static final String KEY_TTS = "tts_config";

    /** 夜间模式：跟随系统 */
    public static final int NIGHT_FOLLOW_SYSTEM = 0;
    /** 夜间模式：强制日间 */
    public static final int NIGHT_OFF = 1;
    /** 夜间模式：强制夜间 */
    public static final int NIGHT_ON = 2;

    private static volatile UserStore instance;

    private final SharedPreferences pref;

    private UserStore(@NonNull Context context) {
        pref = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static UserStore get(@NonNull Context context) {
        if (instance == null) {
            synchronized (UserStore.class) {
                if (instance == null) {
                    instance = new UserStore(context);
                }
            }
        }
        return instance;
    }

    // ------------------------------------------------------------ 收藏 / 书架

    @NonNull
    public List<Poem> getFavorites() {
        return readList(KEY_FAVORITES);
    }

    public boolean isFavorite(long poemId) {
        for (Poem poem : getFavorites()) {
            if (poem.getId() == poemId) {
                return true;
            }
        }
        return false;
    }

    /** 收藏 / 取消收藏，返回操作后的状态（true = 已收藏） */
    public boolean toggleFavorite(@NonNull Poem poem) {
        List<Poem> list = getFavorites();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId() == poem.getId()) {
                list.remove(i);
                writeList(KEY_FAVORITES, list);
                return false;
            }
        }
        list.add(0, poem);
        writeList(KEY_FAVORITES, list);
        return true;
    }

    public void removeFavorite(long poemId) {
        List<Poem> list = getFavorites();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId() == poemId) {
                list.remove(i);
                break;
            }
        }
        writeList(KEY_FAVORITES, list);
    }

    public void clearFavorites() {
        pref.edit().remove(KEY_FAVORITES).apply();
    }

    // ------------------------------------------------------------ 历史 / 进度

    @NonNull
    public List<Poem> getHistory() {
        return readList(KEY_HISTORY);
    }

    /** 记录一次阅读，最多保留 30 条 */
    public void recordHistory(@NonNull Poem poem) {
        List<Poem> list = getHistory();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId() == poem.getId()) {
                list.remove(i);
                break;
            }
        }
        list.add(0, poem);
        while (list.size() > 30) {
            list.remove(list.size() - 1);
        }
        writeList(KEY_HISTORY, list);
        pref.edit().putInt(KEY_PLAY_COUNT, getPlayCount() + 1).apply();
    }

    public void clearHistory() {
        pref.edit().remove(KEY_HISTORY).apply();
    }

    public int getProgress(long poemId) {
        JSONObject root = progress();
        return root.optInt(String.valueOf(poemId), 0);
    }

    public void setProgress(long poemId, int percent) {
        JSONObject root = progress();
        try {
            root.put(String.valueOf(poemId), percent);
            pref.edit().putString(KEY_PROGRESS, root.toString()).apply();
        } catch (JSONException ignored) {
            // ignore
        }
    }

    @NonNull
    private JSONObject progress() {
        String raw = pref.getString(KEY_PROGRESS, "{}");
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    // ------------------------------------------------------------ 朗读偏好

    public int getNightMode() {
        return pref.getInt(KEY_NIGHT, NIGHT_FOLLOW_SYSTEM);
    }

    public void setNightMode(int mode) {
        pref.edit().putInt(KEY_NIGHT, mode).apply();
    }

    /** 正文字号缩放：0.9 / 1.0 / 1.15 */
    public float getFontScale() {
        return pref.getFloat(KEY_FONT, 1.0f);
    }

    public void setFontScale(float scale) {
        pref.edit().putFloat(KEY_FONT, scale).apply();
    }

    /**
     * 语音配置（引擎 / 发音人 / 语速 / 音调 / 音量）。
     * 读取失败或损坏时返回 {@link TtsConfig#defaults()}，不影响其它功能。
     */
    @NonNull
    public TtsConfig getTtsConfig() {
        String raw = pref.getString(KEY_TTS, "");
        if (raw != null && !raw.isEmpty()) {
            return TtsConfig.fromJson(raw);
        }
        return migrateLegacyVoiceSettings();
    }

    /**
     * 兼容早期版本：那时音色与语速是两个独立 key，这里升级成整份配置后清掉旧 key。
     */
    @NonNull
    private TtsConfig migrateLegacyVoiceSettings() {
        TtsConfig config = TtsConfig.defaults();
        String legacyVoice = pref.getString(KEY_VOICE, null);
        if (legacyVoice != null && !legacyVoice.isEmpty()) {
            config.setVoiceId(legacyVoice);
        }
        if (pref.contains(KEY_RATE)) {
            config.setRate(pref.getFloat(KEY_RATE, TtsConfig.RATE_DEFAULT));
        }
        if (legacyVoice != null || pref.contains(KEY_RATE)) {
            pref.edit().putString(KEY_TTS, config.toJson().toString())
                    .remove(KEY_VOICE).remove(KEY_RATE).apply();
        }
        return config;
    }

    public void setTtsConfig(@NonNull TtsConfig config) {
        pref.edit().putString(KEY_TTS, config.toJson().toString()).apply();
    }

    /** 在既有配置基础上做局部修改 */
    public void updateTtsConfig(@NonNull Consumer<TtsConfig> editor) {
        TtsConfig config = getTtsConfig();
        editor.accept(config);
        setTtsConfig(config);
    }

    /** 局部修改回调 */
    public interface Consumer<T> {
        void accept(T value);
    }

    /** 兼容旧接口：写入默认发音人 */
    public String getVoiceId() {
        return getTtsConfig().getVoiceId();
    }

    /** 兼容旧接口：设置默认发音人 */
    public void setVoiceId(String voiceId) {
        updateTtsConfig(config -> config.setVoiceId(voiceId));
    }

    /** 兼容旧接口：读取默认语速（倍率） */
    public float getSpeechRate() {
        return getTtsConfig().getRate();
    }

    /** 兼容旧接口：设置默认语速（倍率） */
    public void setSpeechRate(float rate) {
        updateTtsConfig(config -> config.setRate(rate));
    }

    public boolean isVerticalReading() {
        return pref.getBoolean(KEY_VERTICAL, false);
    }

    public void setVerticalReading(boolean vertical) {
        pref.edit().putBoolean(KEY_VERTICAL, vertical).apply();
    }

    // ------------------------------------------------------------ 统计

    public int getCheckinDays() {
        return pref.getInt(KEY_CHECKIN, 4);
    }

    public void setCheckinDays(int days) {
        pref.edit().putInt(KEY_CHECKIN, days).apply();
    }

    public int getPlayCount() {
        return pref.getInt(KEY_PLAY_COUNT, 0);
    }

    /** 朗读小时数：按每次朗读约 2 分钟估算 */
    public float getReadHours() {
        return getPlayCount() * 2.0f / 60.0f + 12.0f;
    }

    // ------------------------------------------------------------ JSON 序列化

    @NonNull
    private List<Poem> readList(@NonNull String key) {
        List<Poem> list = new ArrayList<>();
        String raw = pref.getString(key, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Poem poem = fromJson(array.getJSONObject(i));
                if (poem != null) {
                    list.add(poem);
                }
            }
        } catch (JSONException ignored) {
            // ignore
        }
        return list;
    }

    private void writeList(@NonNull String key, @NonNull List<Poem> list) {
        JSONArray array = new JSONArray();
        for (Poem poem : list) {
            array.put(toJson(poem));
        }
        pref.edit().putString(key, array.toString()).apply();
    }

    @NonNull
    private static JSONObject toJson(@NonNull Poem poem) {
        JSONObject json = new JSONObject();
        try {
            json.put("id", poem.getId());
            json.put("authorId", poem.getAuthorId());
            json.put("title", poem.getTitle());
            json.put("rhythmic", poem.getRhythmic());
            json.put("srcName", poem.getSrcName());
            json.put("authorName", poem.getAuthorName());
            json.put("dynasty", poem.getDynasty());
            json.put("body", poem.getBody());
            json.put("tags", poem.getTags());
            json.put("notes", poem.getNotes());
            json.put("translation", poem.getTranslation());
            json.put("appreciation", poem.getAppreciation());
            json.put("score", poem.getScore());
            json.put("nChar", poem.getNChar());
        } catch (JSONException ignored) {
            // ignore
        }
        return json;
    }

    private static Poem fromJson(@NonNull JSONObject json) {
        try {
            Poem poem = new Poem();
            poem.setId(json.optLong("id"));
            poem.setAuthorId(json.optLong("authorId"));
            poem.setTitle(json.optString("title"));
            poem.setRhythmic(json.optString("rhythmic"));
            poem.setSrcName(json.optString("srcName"));
            poem.setAuthorName(json.optString("authorName"));
            poem.setDynasty(json.optString("dynasty"));
            poem.setBody(json.optString("body"));
            poem.setTags(json.optString("tags"));
            poem.setNotes(json.optString("notes"));
            poem.setTranslation(json.optString("translation"));
            poem.setAppreciation(json.optString("appreciation"));
            poem.setScore(json.optInt("score"));
            poem.setNChar(json.optInt("nChar"));
            return poem;
        } catch (Exception e) {
            return null;
        }
    }
}

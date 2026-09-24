package com.example.poetry.data.local;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.TtsConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 用户数据本地存储：<b>单一 JSON 文件</b>。
 * <p>
 * 数据落在应用私有目录 {@code /data/data/com.example.poetry/files/user_data.json}，
 * 收藏 / 朗读历史 / 阅读进度 / 朗读偏好 / 打卡统计 / 个人资料 / 皮肤 / 书架设置
 * 全部是这份 JSON 的一部分，可直接导出备份、也可从备份恢复。
 * <p>
 * 文件结构：
 * <pre>{
 *   "version": 2,
 *   "profile":  { nickname, avatar, signature, taste[] },
 *   "favorites":[ Poem ],
 *   "history":  [ Poem ],
 *   "readAt":   { "诗id": 时间戳 },
 *   "progress": { "诗id": 百分比 },
 *   "checkin":  { dates[], total },
 *   "stats":    { playCount },
 *   "prefs":    { nightMode, fontScale, verticalReading, autoPlay, continuousPlay,
 *                 wifiOnly, skin, shelfGrid, shelfSort },
 *   "tts":      { ... },
 *   "updatedAt": 时间戳
 * }</pre>
 * <p>
 * 之所以存整首诗词的快照，是因为收藏夹在没有本地诗库时也要能展示。
 * <p>
 * 首次启动会自动把旧版 SharedPreferences 里的数据迁移进来（只做一次）。
 * <p>
 * TODO（后台接入后）：把 favorites / history / progress 同步到服务端，
 * 对应接口见 {@link com.example.poetry.data.remote.PoetryApi}。
 */
public final class UserStore {

    /** 数据文件名 */
    public static final String FILE_NAME = "user_data.json";
    /** 当前数据结构版本 */
    private static final int DATA_VERSION = 2;
    /** 旧版 SharedPreferences 名，仅用于迁移 */
    private static final String LEGACY_PREF = "poetry_user";

    // ---- 旧版 key，迁移完成后不再写入 ----
    private static final String L_FAVORITES = "favorites";
    private static final String L_HISTORY = "history";
    private static final String L_PROGRESS = "progress";
    private static final String L_READ_AT = "read_at";
    private static final String L_NIGHT = "night_mode";
    private static final String L_FONT = "font_scale";
    private static final String L_VOICE = "voice_id";
    private static final String L_RATE = "speech_rate";
    private static final String L_VERTICAL = "vertical_reading";
    private static final String L_CHECKIN = "checkin_days";
    private static final String L_CHECKIN_TOTAL = "checkin_total";
    private static final String L_CHECKIN_DATES = "checkin_dates";
    private static final String L_PLAY_COUNT = "play_count";
    private static final String L_TTS = "tts_config";
    private static final String L_NICKNAME = "nickname";
    private static final String L_AVATAR = "avatar";
    private static final String L_SIGNATURE = "signature";
    private static final String L_TASTE = "taste";
    private static final String L_AUTO_PLAY = "auto_play";
    private static final String L_CONTINUOUS_PLAY = "continuous_play";
    private static final String L_WIFI_ONLY = "wifi_only";
    private static final String L_SKIN = "skin_id";
    private static final String L_SHELF_GRID = "shelf_grid";
    private static final String L_SHELF_SORT = "shelf_sort";

    /** 夜间模式：跟随系统 */
    public static final int NIGHT_FOLLOW_SYSTEM = 0;
    /** 夜间模式：强制日间 */
    public static final int NIGHT_OFF = 1;
    /** 夜间模式：强制夜间 */
    public static final int NIGHT_ON = 2;

    /** 打卡日期格式；SimpleDateFormat 非线程安全，按线程各存一份 */
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
                }
            };

    private static volatile UserStore instance;

    private final File file;
    private final Object lock = new Object();
    private JSONObject root;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    private UserStore(@NonNull Context context) {
        Context app = context.getApplicationContext();
        file = new File(app.getFilesDir(), FILE_NAME);
        root = new JSONObject();
        synchronized (lock) {
            loadLocked();
        }
        if (root.length() == 0) {
            migrateFromSharedPreferences(app);
        }
        ensureSections();
        save();
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

    /** 数据文件本体，便于在「关于」页展示路径或做分享 */
    @NonNull
    public File dataFile() {
        return file;
    }

    // ------------------------------------------------------------ 载入 / 落盘

    private void loadLocked() {
        if (!file.exists()) {
            return;
        }
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            JSONObject parsed = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (parsed.length() > 0) {
                root = parsed;
            }
        } catch (Exception e) {
            // 文件损坏：另存一份后重新开始，避免整个 App 打不开
            File broken = new File(file.getParentFile(), FILE_NAME + ".broken");
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(broken);
        }
    }

    /** 标记改动并异步落盘；写临时文件后原子改名，断电不会留下半截文件 */
    private void save() {
        final String text;
        synchronized (lock) {
            try {
                root.put("updatedAt", System.currentTimeMillis());
            } catch (JSONException ignored) {
                // ignore
            }
            text = root.toString();
        }
        writer.execute(() -> writeAtomic(text));
    }

    private void writeAtomic(@NonNull String text) {
        File dir = file.getParentFile();
        if (dir == null) {
            return;
        }
        File tmp = new File(dir, FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(file)) {
            // 极少数文件系统改名失败，退回直接写
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            } catch (IOException ignored) {
                // ignore
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** 保证各分区存在，避免调用方到处判空 */
    private void ensureSections() {
        synchronized (lock) {
            for (String name : new String[]{"profile", "checkin", "stats", "prefs",
                    "readAt", "progress"}) {
                if (root.optJSONObject(name) == null) {
                    try {
                        root.put(name, new JSONObject());
                    } catch (JSONException ignored) {
                        // ignore
                    }
                }
            }
            for (String name : new String[]{"favorites", "history"}) {
                if (root.optJSONArray(name) == null) {
                    try {
                        root.put(name, new JSONArray());
                    } catch (JSONException ignored) {
                        // ignore
                    }
                }
            }
            try {
                root.put("version", DATA_VERSION);
            } catch (JSONException ignored) {
                // ignore
            }
        }
    }

    @NonNull
    private JSONObject section(@NonNull String name) {
        synchronized (lock) {
            JSONObject obj = root.optJSONObject(name);
            if (obj == null) {
                obj = new JSONObject();
                try {
                    root.put(name, obj);
                } catch (JSONException ignored) {
                    // ignore
                }
            }
            return obj;
        }
    }

    @NonNull
    private JSONArray array(@NonNull String name) {
        synchronized (lock) {
            JSONArray arr = root.optJSONArray(name);
            if (arr == null) {
                arr = new JSONArray();
                try {
                    root.put(name, arr);
                } catch (JSONException ignored) {
                    // ignore
                }
            }
            return arr;
        }
    }

    // ------------------------------------------------------------ 旧数据迁移

    /** 把旧版 SharedPreferences 的数据搬进 JSON 文件；只在文件不存在时执行一次 */
    private void migrateFromSharedPreferences(@NonNull Context context) {
        SharedPreferences pref = context.getSharedPreferences(LEGACY_PREF, Context.MODE_PRIVATE);
        if (!pref.contains(L_FAVORITES)
                && !pref.contains(L_HISTORY)
                && !pref.contains(L_NICKNAME)
                && !pref.contains(L_TTS)
                && !pref.contains(L_CHECKIN_DATES)
                && !pref.contains(L_SKIN)) {
            return; // 全新安装，无需迁移
        }
        synchronized (lock) {
            try {
                root.put("version", DATA_VERSION);

                root.put("favorites", parseArray(pref.getString(L_FAVORITES, "[]")));
                root.put("history", parseArray(pref.getString(L_HISTORY, "[]")));
                root.put("readAt", parseObject(pref.getString(L_READ_AT, "{}")));
                root.put("progress", parseObject(pref.getString(L_PROGRESS, "{}")));

                JSONObject profile = new JSONObject();
                profile.put("nickname", pref.getString(L_NICKNAME, ""));
                profile.put("avatar", pref.getString(L_AVATAR, ""));
                profile.put("signature", pref.getString(L_SIGNATURE, ""));
                JSONArray taste = new JSONArray();
                String rawTaste = pref.getString(L_TASTE, "");
                if (rawTaste != null && !rawTaste.isEmpty()) {
                    for (String part : rawTaste.split(",")) {
                        if (!part.trim().isEmpty()) {
                            taste.put(part.trim());
                        }
                    }
                }
                profile.put("taste", taste);
                root.put("profile", profile);

                JSONArray dates = new JSONArray();
                String rawDates = pref.getString(L_CHECKIN_DATES, "");
                if (rawDates != null && !rawDates.isEmpty()) {
                    for (String part : rawDates.split(",")) {
                        if (!part.trim().isEmpty()) {
                            dates.put(part.trim());
                        }
                    }
                }
                JSONObject checkin = new JSONObject();
                checkin.put("dates", dates);
                checkin.put("total", pref.getInt(L_CHECKIN_TOTAL, pref.getInt(L_CHECKIN, 0)));
                root.put("checkin", checkin);

                JSONObject stats = new JSONObject();
                stats.put("playCount", pref.getInt(L_PLAY_COUNT, 0));
                root.put("stats", stats);

                JSONObject prefs = new JSONObject();
                prefs.put("nightMode", pref.getInt(L_NIGHT, NIGHT_FOLLOW_SYSTEM));
                prefs.put("fontScale", (double) pref.getFloat(L_FONT, 1.0f));
                prefs.put("verticalReading", pref.getBoolean(L_VERTICAL, false));
                prefs.put("autoPlay", pref.getBoolean(L_AUTO_PLAY, false));
                prefs.put("continuousPlay", pref.getBoolean(L_CONTINUOUS_PLAY, true));
                prefs.put("wifiOnly", pref.getBoolean(L_WIFI_ONLY, true));
                prefs.put("skin", pref.getString(L_SKIN, "xuan"));
                prefs.put("shelfGrid", pref.getBoolean(L_SHELF_GRID, true));
                prefs.put("shelfSort", pref.getInt(L_SHELF_SORT, 0));
                root.put("prefs", prefs);

                // 语音配置：新版是一整份 JSON，旧版拆成音色 + 语速两个 key
                String rawTts = pref.getString(L_TTS, "");
                JSONObject tts;
                if (rawTts != null && !rawTts.isEmpty()) {
                    tts = parseObject(rawTts);
                } else {
                    TtsConfig config = TtsConfig.defaults();
                    String voice = pref.getString(L_VOICE, null);
                    if (voice != null && !voice.isEmpty()) {
                        config.setVoiceId(voice);
                    }
                    if (pref.contains(L_RATE)) {
                        config.setRate(pref.getFloat(L_RATE, TtsConfig.RATE_DEFAULT));
                    }
                    tts = config.toJson();
                }
                root.put("tts", tts);
            } catch (JSONException ignored) {
                // 迁移失败就当全新用户，不影响使用
            }
        }
    }

    @NonNull
    private static JSONArray parseArray(@Nullable String raw) {
        try {
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    @NonNull
    private static JSONObject parseObject(@Nullable String raw) {
        try {
            return new JSONObject(raw == null ? "{}" : raw);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    // ------------------------------------------------------------ 收藏 / 书架

    @NonNull
    public List<Poem> getFavorites() {
        return readPoems("favorites");
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
                writePoems("favorites", list);
                return false;
            }
        }
        poem.setFavoriteAt(System.currentTimeMillis());
        list.add(0, poem);
        writePoems("favorites", list);
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
        writePoems("favorites", list);
    }

    public void clearFavorites() {
        synchronized (lock) {
            try {
                root.put("favorites", new JSONArray());
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
    }

    // ------------------------------------------------------------ 历史 / 进度

    @NonNull
    public List<Poem> getHistory() {
        return readPoems("history");
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
        writePoems("history", list);
        setReadAt(poem.getId(), System.currentTimeMillis());
        synchronized (lock) {
            JSONObject stats = section("stats");
            try {
                stats.put("playCount", stats.optInt("playCount", 0) + 1);
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
    }

    /** 单条历史的时间戳，用于「今天 / 更早」分组；没有记录时返回 0 */
    public long getReadAt(long poemId) {
        synchronized (lock) {
            return section("readAt").optLong(String.valueOf(poemId), 0L);
        }
    }

    private void setReadAt(long poemId, long time) {
        synchronized (lock) {
            try {
                section("readAt").put(String.valueOf(poemId), time);
            } catch (JSONException ignored) {
                // ignore
            }
        }
    }

    /** 删掉一条历史（不影响朗读次数统计） */
    public boolean removeHistory(long poemId) {
        List<Poem> list = getHistory();
        boolean removed = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId() == poemId) {
                list.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) {
            writePoems("history", list);
        }
        return removed;
    }

    public void clearHistory() {
        synchronized (lock) {
            try {
                root.put("history", new JSONArray());
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
    }

    public int getProgress(long poemId) {
        synchronized (lock) {
            return section("progress").optInt(String.valueOf(poemId), 0);
        }
    }

    public void setProgress(long poemId, int percent) {
        synchronized (lock) {
            try {
                section("progress").put(String.valueOf(poemId), percent);
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
    }

    // ------------------------------------------------------------ 朗读偏好

    public int getNightMode() {
        return prefs().optInt("nightMode", NIGHT_FOLLOW_SYSTEM);
    }

    public void setNightMode(int mode) {
        putPref("nightMode", mode);
    }

    /** 正文字号缩放：0.9 / 1.0 / 1.15 */
    public float getFontScale() {
        return (float) prefs().optDouble("fontScale", 1.0d);
    }

    public void setFontScale(float scale) {
        putPref("fontScale", (double) scale);
    }

    /**
     * 语音配置（引擎 / 发音人 / 语速 / 音调 / 音量）。
     * 读取失败或损坏时返回 {@link TtsConfig#defaults()}，不影响其它功能。
     */
    @NonNull
    public TtsConfig getTtsConfig() {
        JSONObject tts;
        synchronized (lock) {
            tts = root.optJSONObject("tts");
        }
        if (tts != null && tts.length() > 0) {
            return TtsConfig.fromJson(tts.toString());
        }
        return TtsConfig.defaults();
    }

    public void setTtsConfig(@NonNull TtsConfig config) {
        synchronized (lock) {
            try {
                root.put("tts", config.toJson());
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
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

    /** 兼容旧接口：读取默认发音人 */
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
        return prefs().optBoolean("verticalReading", false);
    }

    public void setVerticalReading(boolean vertical) {
        putPref("verticalReading", vertical);
    }

    /** 正文是否显示拼音注音（默认关闭） */
    public boolean isPinyinShown() {
        return prefs().optBoolean("pinyin", false);
    }

    public void setPinyinShown(boolean shown) {
        putPref("pinyin", shown);
    }

    /** 正文字族：serif 宋体 / sans 黑体，默认宋体 */
    @NonNull
    public String getFontFamily() {
        String value = prefs().optString("fontFamily", "serif");
        return value.isEmpty() ? "serif" : value;
    }

    public void setFontFamily(@Nullable String family) {
        putPref("fontFamily", family == null || family.isEmpty() ? "serif" : family);
    }

    // ------------------------------------------------------------ 统计

    /** 兼容旧接口：累计打卡天数 */
    public int getCheckinDays() {
        return getCheckinTotal();
    }

    /** 兼容旧接口：直接设定累计天数 */
    public void setCheckinDays(int days) {
        synchronized (lock) {
            try {
                section("checkin").put("total", days);
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
    }

    public int getPlayCount() {
        return section("stats").optInt("playCount", 0);
    }

    /** 朗读小时数：按每次朗读约 2 分钟估算（真实计数，不再掺入演示数据） */
    public float getReadHours() {
        return getPlayCount() * 2.0f / 60.0f;
    }

    // ------------------------------------------------------------ 打卡

    /** 今天的日期串（yyyy-MM-dd），日历与打卡都以它为准 */
    @NonNull
    public static String today() {
        return DATE_FORMAT.get().format(new Date());
    }

    /** 已打卡的日期集合（yyyy-MM-dd） */
    @NonNull
    public Set<String> getCheckinDates() {
        Set<String> dates = new LinkedHashSet<>();
        synchronized (lock) {
            JSONArray arr = section("checkin").optJSONArray("dates");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String value = arr.optString(i, "");
                    if (!value.isEmpty()) {
                        dates.add(value);
                    }
                }
            }
        }
        return dates;
    }

    private void saveCheckinDates(@NonNull Set<String> dates) {
        synchronized (lock) {
            JSONArray arr = new JSONArray();
            for (String date : dates) {
                arr.put(date);
            }
            try {
                section("checkin").put("dates", arr).put("total", dates.size());
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
    }

    public boolean isCheckedInToday() {
        return getCheckinDates().contains(today());
    }

    /** 打今天的卡；返回 false 表示今天已经打过 */
    public boolean checkIn() {
        Set<String> dates = getCheckinDates();
        String today = today();
        if (dates.contains(today)) {
            return false;
        }
        dates.add(today);
        saveCheckinDates(dates);
        return true;
    }

    /** 累计打卡天数 */
    public int getCheckinTotal() {
        Set<String> dates = getCheckinDates();
        if (!dates.isEmpty()) {
            return dates.size();
        }
        return section("checkin").optInt("total", 0);
    }

    /** 连续打卡天数；今天还没打时从昨天往回数，避免刚过零点就归零 */
    public int getCheckinStreak() {
        Set<String> dates = getCheckinDates();
        if (dates.isEmpty()) {
            return 0;
        }
        Calendar cursor = Calendar.getInstance(Locale.CHINA);
        cursor.setFirstDayOfWeek(Calendar.MONDAY);
        if (!dates.contains(today())) {
            cursor.add(Calendar.DAY_OF_YEAR, -1);
        }
        int streak = 0;
        SimpleDateFormat format = DATE_FORMAT.get();
        while (dates.contains(format.format(cursor.getTime()))) {
            streak++;
            cursor.add(Calendar.DAY_OF_YEAR, -1);
        }
        return streak;
    }

    /** 本周（周一 ~ 周日）每天的打卡状态，固定 7 项 */
    @NonNull
    public List<Boolean> getWeekCheckins() {
        List<Boolean> week = new ArrayList<>(7);
        Set<String> dates = getCheckinDates();
        SimpleDateFormat format = DATE_FORMAT.get();
        Calendar cursor = Calendar.getInstance(Locale.CHINA);
        cursor.setFirstDayOfWeek(Calendar.MONDAY);
        cursor.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        for (int i = 0; i < 7; i++) {
            week.add(dates.contains(format.format(cursor.getTime())));
            cursor.add(Calendar.DAY_OF_YEAR, 1);
        }
        return week;
    }

    // ------------------------------------------------------------ 个人资料

    /** 昵称；为空表示还没设置过，界面上回落到默认文案 */
    @NonNull
    public String getNickname() {
        return profile().optString("nickname", "");
    }

    public void setNickname(@Nullable String name) {
        putProfile("nickname", name == null ? "" : name.trim());
    }

    /** 头像：一个汉字或意象 emoji，为空时用昵称首字 */
    @NonNull
    public String getAvatar() {
        return profile().optString("avatar", "");
    }

    public void setAvatar(@Nullable String avatar) {
        putProfile("avatar", avatar == null ? "" : avatar.trim());
    }

    @NonNull
    public String getSignature() {
        return profile().optString("signature", "");
    }

    public void setSignature(@Nullable String signature) {
        putProfile("signature", signature == null ? "" : signature.trim());
    }

    /** 阅读偏好标签，如「唐诗 / 山水」 */
    @NonNull
    public List<String> getTasteList() {
        List<String> list = new ArrayList<>();
        synchronized (lock) {
            JSONArray arr = profile().optJSONArray("taste");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String value = arr.optString(i, "").trim();
                    if (!value.isEmpty()) {
                        list.add(value);
                    }
                }
            }
        }
        return list;
    }

    public void setTasteList(@NonNull List<String> tastes) {
        synchronized (lock) {
            JSONArray arr = new JSONArray();
            for (String taste : tastes) {
                if (taste != null && !taste.trim().isEmpty()) {
                    arr.put(taste.trim());
                }
            }
            try {
                profile().put("taste", arr);
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
    }

    /** 资料是否已被用户改过（用于「我的」页显示引导） */
    public boolean hasProfile() {
        return !getNickname().isEmpty() || !getSignature().isEmpty();
    }

    // ------------------------------------------------------------ 朗读偏好补充

    /** 进入详情自动开始朗读 */
    public boolean isAutoPlay() {
        return prefs().optBoolean("autoPlay", false);
    }

    public void setAutoPlay(boolean enabled) {
        putPref("autoPlay", enabled);
    }

    /** 一首读完自动接着读下一篇 */
    public boolean isContinuousPlay() {
        return prefs().optBoolean("continuousPlay", true);
    }

    public void setContinuousPlay(boolean enabled) {
        putPref("continuousPlay", enabled);
    }

    /** 仅在 WiFi 下下载离线语音包 */
    public boolean isWifiOnly() {
        return prefs().optBoolean("wifiOnly", true);
    }

    public void setWifiOnly(boolean enabled) {
        putPref("wifiOnly", enabled);
    }

    // ------------------------------------------------------------ 皮肤 / 书架

    /** 皮肤 id，见 {@code com.example.poetry.ui.Skin}；默认宣纸 */
    @NonNull
    public String getSkinId() {
        String value = prefs().optString("skin", "xuan");
        return value.isEmpty() ? "xuan" : value;
    }

    public void setSkinId(@Nullable String id) {
        putPref("skin", id == null || id.isEmpty() ? "xuan" : id);
    }

    /** 书架视图：true 网格 / false 列表 */
    public boolean isShelfGrid() {
        return prefs().optBoolean("shelfGrid", true);
    }

    public void setShelfGrid(boolean grid) {
        putPref("shelfGrid", grid);
    }

    /** 书架排序：0 最近 / 1 体裁 / 2 热度 */
    public int getShelfSort() {
        return prefs().optInt("shelfSort", 0);
    }

    public void setShelfSort(int sort) {
        putPref("shelfSort", sort);
    }

    // ------------------------------------------------------------ 备份导出 / 恢复

    /**
     * 导出整份数据的 JSON（缩进 2 空格，便于直接查看）。
     * 用于「关于」页的备份功能，也可以拷到电脑上检查。
     */
    @NonNull
    public String exportJson() {
        synchronized (lock) {
            try {
                return root.toString(2);
            } catch (JSONException e) {
                return root.toString();
            }
        }
    }

    /**
     * 用一份 JSON 备份整体覆盖当前数据。
     *
     * @return true 表示恢复成功；格式不对时返回 false，且不动现有数据
     */
    public boolean importJson(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        JSONObject parsed;
        try {
            parsed = new JSONObject(text);
        } catch (JSONException e) {
            return false;
        }
        if (parsed.length() == 0) {
            return false;
        }
        synchronized (lock) {
            root = parsed;
        }
        ensureSections();
        save();
        return true;
    }

    // ------------------------------------------------------------ JSON 序列化

    @NonNull
    private JSONObject profile() {
        return section("profile");
    }

    @NonNull
    private JSONObject prefs() {
        return section("prefs");
    }

    private void putProfile(@NonNull String key, @NonNull String value) {
        synchronized (lock) {
            try {
                profile().put(key, value);
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
    }

    private void putPref(@NonNull String key, @NonNull Object value) {
        synchronized (lock) {
            try {
                prefs().put(key, value);
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
    }

    @NonNull
    private List<Poem> readPoems(@NonNull String section) {
        List<Poem> list = new ArrayList<>();
        JSONArray arr = array(section);
        for (int i = 0; i < arr.length(); i++) {
            Poem poem = fromJson(arr.optJSONObject(i));
            if (poem != null) {
                list.add(poem);
            }
        }
        return list;
    }

    private void writePoems(@NonNull String section, @NonNull List<Poem> list) {
        JSONArray arr = new JSONArray();
        for (Poem poem : list) {
            arr.put(toJson(poem));
        }
        synchronized (lock) {
            try {
                root.put(section, arr);
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
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
            json.put("favoriteAt", poem.getFavoriteAt());
        } catch (JSONException ignored) {
            // ignore
        }
        return json;
    }

    @Nullable
    private static Poem fromJson(@Nullable JSONObject json) {
        if (json == null) {
            return null;
        }
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
            poem.setFavoriteAt(json.optLong("favoriteAt"));
            return poem;
        } catch (Exception e) {
            return null;
        }
    }
}

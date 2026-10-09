package com.example.poetry.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.model.DayStat;
import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.Member;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.UserProfile;

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
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
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
 *   "daily":    { "yyyy-MM-dd": { playCount, favoriteCount, unfavoriteCount,
 *                                 plays:      { "诗id": { count, title, author, lastAt } },
 *                                 favorites:  { "诗id": { count, title, author, lastAt } },
 *                                 unfavorites:{ "诗id": { count, title, author, lastAt } } } },
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
    /**
     * 当前数据结构版本。
     * <p>2 → 3：收藏 / 历史 / 阅读进度 / 最近阅读时间的身份从「数字 id」换成
     * {@link Poem#identityKey()}，见 {@link #migrateIdentityLocked()}。
     */
    private static final int DATA_VERSION = 3;
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
        ensureMember();
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
            // 必须在下面把 version 写成 DATA_VERSION 之前读旧值：它就是「要不要迁移」的依据
            int oldVersion = root.optInt("version", 0);
            for (String name : new String[]{"profile", "checkin", "stats",
                    "prefs", "readAt", "progress", "session", "daily", "member"}) {
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
            if (oldVersion < 3) {
                migrateIdentityLocked();
            }
            try {
                root.put("version", DATA_VERSION);
            } catch (JSONException ignored) {
                // ignore
            }
        }
    }

    /**
     * v2 → v3：把 {@code readAt} / {@code progress} 的键从「数字 id」换成 {@link Poem#identityKey()}。
     * <p>
     * 收藏和历史的快照里本来就带 {@code uid}，所以这两张表不用改；这里只是借它们建一张
     * 「id → uid」表，再把那两张「id → 值」的映射就地改名——{@code UserStore} 拿不到数据库，
     * 也正因如此才要用本机快照来换算。快照里查不到的旧键改写成 {@code "id:" + 数字}，
     * 这样示例数据（没有 uid）时期的条目在新规则下仍然可达，不会被静默丢掉。
     * <p>
     * 幂等：只由 {@code ensureSections()} 在 {@code version < 3} 时调用一次，
     * 迁移结果随后由构造函数的 {@code save()} 落盘。
     */
    private void migrateIdentityLocked() {
        Map<Long, String> uidOf = new HashMap<>();
        for (String section : new String[]{"favorites", "history"}) {
            JSONArray arr = root.optJSONArray(section);
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String uid = item.optString("uid");
                if (!uid.isEmpty()) {
                    uidOf.put(item.optLong("id"), uid);
                }
            }
        }
        for (String section : new String[]{"readAt", "progress"}) {
            JSONObject map = root.optJSONObject(section);
            if (map == null || map.length() == 0) {
                continue;
            }
            JSONObject rebuilt = new JSONObject();
            for (Iterator<String> it = map.keys(); it.hasNext(); ) {
                String key = it.next();
                Object value = map.opt(key);
                String newKey = key;
                if (isDigits(key)) {
                    long id = Long.parseLong(key);
                    String uid = uidOf.get(id);
                    newKey = uid != null ? uid : "id:" + id;
                }
                try {
                    rebuilt.put(newKey, value);
                } catch (JSONException ignored) {
                    // ignore
                }
            }
            try {
                root.put(section, rebuilt);
            } catch (JSONException ignored) {
                // ignore
            }
        }
    }

    private static boolean isDigits(@NonNull String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 首次启动（member 分区没有 {@code level}）写入一份未开通记录，
     * 让「本机还没有任何权益信息」有个明确的落点。之后服务端资料回来了会覆盖它
     * （见 {@link #setAccount}）。
     * <p>
     * 写的永远是未开通：权益在服务端，本地开局不该先给出什么东西。
     */
    private void ensureMember() {
        synchronized (lock) {
            JSONObject member = root.optJSONObject("member");
            if (member != null && member.has("level")) {
                return;
            }
            try {
                root.put("member", Member.free().toJson());
            } catch (JSONException ignored) {
                // ignore
            }
        }
    }

    // ------------------------------------------------------------ 会员
    /**
     * 当前会员记录；没有记录（或记录损坏）时按未开通。
     * 宁可少放一个音色，不能错放权益。
     */
    @NonNull
    public Member getMember() {
        synchronized (lock) {
            JSONObject m = root.optJSONObject("member");
            if (m != null && m.length() > 0) {
                return Member.fromJson(m.toString());
            }
        }
        return Member.free();
    }

    public void setMember(@NonNull Member member) {
        synchronized (lock) {
            try {
                root.put("member", member.toJson());
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
    }

    /** 是否会员（会过期的会员以当前时间判定是否有效）。 */
    public boolean isMember() {
        return getMember().isActive();
    }

    /**
     * 用服务端资料刷新本机账号缓存，一次落盘写「会员权益」与「原始资料」两份。
     *
     * <p>为什么留两份：{@code member} 是判定用的归一化结果（等级、到期、是否被禁用
     * 都已经折算进去，见 {@link UserProfile#toMember()}），{@code account} 是「我的」页
     * 要显示的原字段（昵称、注册时间、等级）。合成它们会让界面为了显示一个注册时间
     * 去反解会员记录。
     *
     * <p>传 {@code null} 表示「这次拿不到资料」——未登录，或者刷新失败。
     * 那时两份一起清掉，会员回到未开通。这是刻意的失败方向：判定基准本就在服务端，
     * 本地判宽了也换不来一次成功合成，只会在点下去的时候被 403 顶回来，
     * 变成「界面说能用、点了却不行」这种更让人困惑的状态。代价是会员遇上短暂断网
     * 会看到音色被锁，下次刷新即自愈——而云端合成本来就要联网。
     */
    public void setAccount(@Nullable UserProfile profile) {
        synchronized (lock) {
            Member member = profile == null ? Member.free() : profile.toMember();
            try {
                root.put("member", member.toJson());
                if (profile == null) {
                    root.remove("account");
                } else {
                    root.put("account", profile.toJson());
                }
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
    }

    /** 服务端资料缓存；未登录或从没刷新成功过时为 {@code null}。 */
    @Nullable
    public UserProfile getServerProfile() {
        synchronized (lock) {
            JSONObject account = root.optJSONObject("account");
            return account == null ? null : UserProfile.fromJson(account);
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
                // 这里只能标 2，不能标 DATA_VERSION：SharedPreferences 里的 readAt/progress
                // 还是「数字 id → 值」的老键，标成当前版本会让下面 ensureSections() 的
                // 迁移门槛判成「已迁移」，老键就永远换不过来了。
                root.put("version", 2);

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

    /** 收藏变化监听：详情页收藏/取消后，书架等页面可以立刻跟着变 */
    public interface FavoriteListener {
        void onFavoritesChanged();
    }

    private final List<FavoriteListener> favoriteListeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void addFavoriteListener(@NonNull FavoriteListener listener) {
        if (!favoriteListeners.contains(listener)) {
            favoriteListeners.add(listener);
        }
    }

    public void removeFavoriteListener(@NonNull FavoriteListener listener) {
        favoriteListeners.remove(listener);
    }

    /**
     * 派发收藏变更。调用方几乎都在主线程；万一哪天从后台线程改了收藏，
     * 这里兜一层切回主线程，免得监听器直接去动 View 崩掉。
     */
    private void notifyFavoritesChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatchFavoritesChanged();
        } else {
            mainHandler.post(this::dispatchFavoritesChanged);
        }
    }

    private void dispatchFavoritesChanged() {
        for (FavoriteListener listener : favoriteListeners) {
            listener.onFavoritesChanged();
        }
    }

    @NonNull
    public List<Poem> getFavorites() {
        return readPoems("favorites");
    }

    /** 是否已收藏。按 {@link Poem#identityKey()} 认，不认 id——换库后 id 会变 */
    public boolean isFavorite(@NonNull Poem poem) {
        String key = poem.identityKey();
        for (Poem item : getFavorites()) {
            if (item.identityKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** 收藏 / 取消收藏，返回操作后的状态（true = 已收藏） */
    public boolean toggleFavorite(@NonNull Poem poem) {
        List<Poem> list = getFavorites();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).identityKey().equals(poem.identityKey())) {
                list.remove(i);
                writePoems("favorites", list);
                recordUnfavoriteLocked(poem);
                notifyFavoritesChanged();
                return false;
            }
        }
        poem.setFavoriteAt(System.currentTimeMillis());
        list.add(0, poem);
        writePoems("favorites", list);
        recordFavoriteLocked(poem);
        notifyFavoritesChanged();
        return true;
    }

    public void removeFavorite(@NonNull Poem poem) {
        List<Poem> list = getFavorites();
        String key = poem.identityKey();
        Poem target = null;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).identityKey().equals(key)) {
                target = list.get(i);
                list.remove(i);
                break;
            }
        }
        if (target == null) {
            return;
        }
        writePoems("favorites", list);
        recordUnfavoriteLocked(target);
        notifyFavoritesChanged();
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
        notifyFavoritesChanged();
    }

    // ------------------------------------------------------------ 历史 / 进度

    @NonNull
    public List<Poem> getHistory() {
        return readPoems("history");
    }

    /** 记录一次阅读，最多保留 30 条 */
    public void recordHistory(@NonNull Poem poem) {
        List<Poem> list = getHistory();
        String key = poem.identityKey();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).identityKey().equals(key)) {
                list.remove(i);
                break;
            }
        }
        list.add(0, poem);
        while (list.size() > 30) {
            list.remove(list.size() - 1);
        }
        writePoems("history", list);
        setReadAt(key, System.currentTimeMillis());
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
    public long getReadAt(@NonNull Poem poem) {
        synchronized (lock) {
            return section("readAt").optLong(poem.identityKey(), 0L);
        }
    }

    private void setReadAt(@NonNull String key, long time) {
        synchronized (lock) {
            try {
                section("readAt").put(key, time);
            } catch (JSONException ignored) {
                // ignore
            }
        }
    }

    /** 删掉一条历史（不影响朗读次数统计） */
    public boolean removeHistory(@NonNull Poem poem) {
        List<Poem> list = getHistory();
        String key = poem.identityKey();
        boolean removed = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).identityKey().equals(key)) {
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

    public int getProgress(@NonNull Poem poem) {
        synchronized (lock) {
            return section("progress").optInt(poem.identityKey(), 0);
        }
    }

    public void setProgress(@NonNull Poem poem, int percent) {
        synchronized (lock) {
            try {
                section("progress").put(poem.identityKey(), percent);
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

    // ------------------------------------------------------------ 每日统计

    /** daily 分区最多保留多少天；再早的会被丢掉，免得 JSON 无限增长 */
    private static final int DAILY_KEEP_DAYS = 90;

    @NonNull
    private JSONObject daily() {
        return section("daily");
    }

    @NonNull
    private JSONObject day(@NonNull String date) {
        JSONObject obj = daily().optJSONObject(date);
        if (obj == null) {
            obj = new JSONObject();
            try {
                daily().put(date, obj);
            } catch (JSONException ignored) {
                // ignore
            }
        }
        return obj;
    }

    /** 记一次朗读：同一天里同一首只累加次数，不重复列条目 */
    public void recordPlay(@NonNull Poem poem) {
        synchronized (lock) {
            JSONObject obj = day(today());
            try {
                obj.put("playCount", obj.optInt("playCount", 0) + 1);
                obj.put("plays", bumpMap(obj.optJSONObject("plays"), poem));
            } catch (JSONException ignored) {
                // ignore
            }
            pruneDailyLocked();
        }
        save();
    }

    /** 记一次收藏。调用方必须已经持有 lock */
    private void recordFavoriteLocked(@NonNull Poem poem) {
        JSONObject obj = day(today());
        try {
            obj.put("favoriteCount", obj.optInt("favoriteCount", 0) + 1);
            obj.put("favorites", bumpMap(obj.optJSONObject("favorites"), poem));
        } catch (JSONException ignored) {
            // ignore
        }
    }

    /** 记一次取消收藏：计数 +1，记进 unfavorites，并把当天收藏索引里的这一条去掉 */
    private void recordUnfavoriteLocked(@NonNull Poem poem) {
        JSONObject obj = day(today());
        try {
            obj.put("unfavoriteCount", obj.optInt("unfavoriteCount", 0) + 1);
            obj.put("unfavorites", bumpMap(obj.optJSONObject("unfavorites"), poem));
            JSONObject favorites = obj.optJSONObject("favorites");
            if (favorites != null) {
                favorites.remove(String.valueOf(poem.getId()));
            }
        } catch (JSONException ignored) {
            // ignore
        }
    }

    /**
     * 按诗词 ID 建索引累加次数：{@code { "诗id": { count, title, author, lastAt } }}。
     * <p>同一天、同一首诗只累加 {@code count}，不会重复建条目；后台拿到 daily
     * 之后直接以诗 ID 为维度出统计即可，不需要再反推时间戳。
     */
    @NonNull
    private static JSONObject bumpMap(@Nullable JSONObject source, @NonNull Poem poem) {
        JSONObject map = source == null ? new JSONObject() : source;
        String key = String.valueOf(poem.getId());
        JSONObject item = map.optJSONObject(key);
        long now = System.currentTimeMillis();
        try {
            if (item == null) {
                item = new JSONObject();
                item.put("count", 1);
            } else {
                item.put("count", item.optInt("count", 0) + 1);
            }
            item.put("title", poem.getTitle());
            item.put("author", poem.getAuthorName());
            item.put("lastAt", now);
            map.put(key, item);
        } catch (JSONException ignored) {
            // ignore
        }
        return map;
    }

    /** 只留下最近 {@link #DAILY_KEEP_DAYS} 天；yyyy-MM-dd 的字典序就是时间序 */
    private void pruneDailyLocked() {
        JSONObject daily = daily();
        List<String> dates = new ArrayList<>();
        Iterator<String> keys = daily.keys();
        while (keys.hasNext()) {
            dates.add(keys.next());
        }
        if (dates.size() <= DAILY_KEEP_DAYS) {
            return;
        }
        Collections.sort(dates);
        for (int i = 0; i < dates.size() - DAILY_KEEP_DAYS; i++) {
            daily.remove(dates.get(i));
        }
    }

    /** 最近若干天的统计，日期倒序（今天排在最前） */
    @NonNull
    public List<DayStat> getDailyStats(int maxDays) {
        List<DayStat> result = new ArrayList<>();
        synchronized (lock) {
            List<String> dates = new ArrayList<>();
            Iterator<String> keys = daily().keys();
            while (keys.hasNext()) {
                dates.add(keys.next());
            }
            Collections.sort(dates, Collections.<String>reverseOrder());
            int limit = maxDays <= 0 ? dates.size() : Math.min(maxDays, dates.size());
            for (int i = 0; i < limit; i++) {
                String date = dates.get(i);
                JSONObject obj = daily().optJSONObject(date);
                if (obj == null) {
                    continue;
                }
                DayStat stat = new DayStat(date);
                stat.playCount = obj.optInt("playCount", 0);
                stat.favoriteCount = obj.optInt("favoriteCount", 0);
                stat.unfavoriteCount = obj.optInt("unfavoriteCount", 0);
                readSection(obj, "plays", stat.plays);
                readSection(obj, "favorites", stat.favorites);
                readSection(obj, "unfavorites", stat.unfavorites);
                result.add(stat);
            }
        }
        return result;
    }

    /** 读某一天的某个索引；老版本存的是数组，这里一并兼容 */
    private static void readSection(@NonNull JSONObject dayObj, @NonNull String field,
                                    @NonNull List<DayStat.Item> out) {
        JSONObject map = dayObj.optJSONObject(field);
        if (map != null) {
            readMap(map, out);
            return;
        }
        JSONArray arr = dayObj.optJSONArray(field);
        if (arr != null) {
            readArray(arr, out);
        }
    }

    /** 读 { "诗id": { count, title, author, lastAt } } */
    private static void readMap(@NonNull JSONObject map, @NonNull List<DayStat.Item> out) {
        Iterator<String> keys = map.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject item = map.optJSONObject(key);
            if (item == null) {
                continue;
            }
            out.add(new DayStat.Item(parseId(key), item.optString("title"),
                    item.optString("author"), item.optInt("count", 1),
                    item.optLong("lastAt", 0L)));
        }
    }

    /** 老格式 [ { id, title, author, count, lastAt } ] */
    private static void readArray(@NonNull JSONArray arr, @NonNull List<DayStat.Item> out) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) {
                continue;
            }
            out.add(new DayStat.Item(item.optLong("id", -1L), item.optString("title"),
                    item.optString("author"), item.optInt("count", 1),
                    item.optLong("lastAt", 0L)));
        }
    }

    private static long parseId(@Nullable String key) {
        if (key == null) {
            return -1L;
        }
        try {
            return Long.parseLong(key);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * 跨日期按诗词 ID 汇总朗读次数，次数多的排前面。
     *
     * <p>后台要「哪首诗最受欢迎」这类内容偏好时，直接用这份结果即可，
     * 它是从 daily 里现算的，不额外占存储。
     */
    @NonNull
    public List<DayStat.Item> getTopPlayed(int limit) {
        List<DayStat> days = getDailyStats(0);
        Map<Long, DayStat.Item> merged = new LinkedHashMap<>();
        for (DayStat day : days) {
            for (DayStat.Item item : day.plays) {
                DayStat.Item old = merged.get(item.id);
                if (old == null) {
                    merged.put(item.id, new DayStat.Item(item.id, item.title, item.author,
                            item.count, item.lastAt));
                } else {
                    merged.put(item.id, new DayStat.Item(item.id, item.title, item.author,
                            old.count + item.count, Math.max(old.lastAt, item.lastAt)));
                }
            }
        }
        List<DayStat.Item> result = new ArrayList<>(merged.values());
        Collections.sort(result, new Comparator<DayStat.Item>() {
            @Override
            public int compare(DayStat.Item a, DayStat.Item b) {
                if (a.count != b.count) {
                    return b.count - a.count;
                }
                return Long.valueOf(b.lastAt).compareTo(Long.valueOf(a.lastAt));
            }
        });
        if (limit > 0 && result.size() > limit) {
            return new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    /** 有朗读记录的日期数 */
    public int getActiveDays() {
        synchronized (lock) {
            int days = 0;
            Iterator<String> keys = daily().keys();
            while (keys.hasNext()) {
                JSONObject obj = daily().optJSONObject(keys.next());
                if (obj != null && obj.optInt("playCount", 0) > 0) {
                    days++;
                }
            }
            return days;
        }
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

    // ------------------------------------------------------------ 登录会话

    /**
     * 是否已登录。微信授权与模拟登录一视同仁，都落在这份 JSON 里，
     * 因此断网、换机（配合备份导出）都能带着登录态走。
     */
    public boolean isLoggedIn() {
        return session().optBoolean("loggedIn", false);
    }

    /** 登录方式：phone（服务端账号）/ wechat / mock（本机身份）；未登录返回空串 */
    @NonNull
    public String getLoginProvider() {
        return session().optString("provider", "");
    }

    /** 登录昵称：微信授权返回的昵称，或模拟登录生成的「诗友 xxxx」 */
    @NonNull
    public String getLoginNickname() {
        return session().optString("nickname", "");
    }

    /** 账号唯一标识：微信 openId（真实授权后由后端换取）或模拟 id */
    @NonNull
    public String getLoginUid() {
        return session().optString("uid", "");
    }

    public long getLoginAt() {
        return session().optLong("loginAt", 0L);
    }

    // ---- 后端凭证（JWT）----

    /**
     * 后端签发的 JWT。空串 = 没有可用凭证（匿名，或这个账号走的是纯本地登录）。
     *
     * <p>刻意和上面那组「本地身份」放在同一个 session 分区里：登录态本来就是一份东西，
     * 拆成两处存储迟早会出现「isLoggedIn() 为真、但 token 早就失效」这种半登录状态，
     * 而那种状态在界面上无法向用户解释。
     */
    @NonNull
    public String getAuthToken() {
        return session().optString("token", "");
    }

    /** 后端 user 表的主键。注意与 {@link #getLoginUid()}（微信 openId / mock-id）不是一回事。 */
    public long getAuthUserId() {
        return session().optLong("userId", 0L);
    }

    /** 票据过期时间（毫秒时间戳，0 = 未知） */
    public long getAuthExpireAt() {
        return session().optLong("expireAt", 0L);
    }

    /**
     * 存下一次后端登录的凭证。
     *
     * <p>**不碰** {@code loggedIn} / {@code provider} 等字段：那些描述的是「本地身份」，
     * 由 {@link #setLogin} 负责；这里只管把票据收好，供 ApiClient 拼 Authorization 头。
     */
    public void setAuthToken(@Nullable String token, long userId, long expireAt) {
        synchronized (lock) {
            try {
                JSONObject s = session();
                s.put("token", token == null ? "" : token);
                s.put("userId", userId);
                s.put("expireAt", expireAt);
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
    }

    /**
     * 票据是否还可用。留 60 秒余量：卡在过期边界上发出请求，
     * 服务端判过期、客户端判有效，用户会看到一次莫名其妙的「登录已失效」。
     */
    public boolean isAuthTokenValid() {
        String token = getAuthToken();
        if (token.isEmpty()) {
            return false;
        }
        long expireAt = getAuthExpireAt();
        return expireAt <= 0 || System.currentTimeMillis() < expireAt - 60_000L;
    }

    /**
     * 记录一次登录。个人资料里的昵称还是空的就把登录昵称带过去，
     * 登录完「我的」页立刻是可用的样子，而不是一片空白。
     */
    public void setLogin(@NonNull String provider, @NonNull String uid, @Nullable String nickname) {
        synchronized (lock) {
            try {
                JSONObject s = session();
                s.put("loggedIn", true);
                s.put("provider", provider);
                s.put("uid", uid);
                s.put("nickname", nickname == null ? "" : nickname);
                s.put("loginAt", System.currentTimeMillis());
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
    }

    public void logout() {
        synchronized (lock) {
            try {
                JSONObject s = session();
                s.put("loggedIn", false);
                s.put("provider", "");
                s.put("uid", "");
                s.put("nickname", "");
                s.put("loginAt", 0L);
                // 票据必须一起清掉：留着的话下一个在这台设备上登录的人
                // 会顶着上一个人的身份往服务端写收藏。
                s.put("token", "");
                s.put("userId", 0L);
                s.put("expireAt", 0L);
            } catch (JSONException ignored) {
                // ignore
            }
            // 会员权益必须跟着一起清：它描述的是「当前这个账号能用什么」，
            // 留着的话下一个在这台设备上登录的人（甚至没登录的人）会继续看到
            // 云端音色是解锁的。真正拦得住的是服务端，但界面不该自相矛盾。
            try {
                root.put("member", Member.free().toJson());
                root.remove("account");
            } catch (JSONException ignored) {
                // ignore
            }
        }
        save();
    }

    /** 会话分区，同样随 user_data.json 备份导出 */
    @NonNull
    private JSONObject session() {
        return section("session");
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

    /** 详情页：一首读完自动重播（默认关闭） */
    public boolean isLoopPlay() {
        return prefs().optBoolean("loopPlay", false);
    }

    public void setLoopPlay(boolean enabled) {
        putPref("loopPlay", enabled);
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
            json.put("uid", poem.getUid());
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
            json.put("introduction", poem.getIntroduction());
            json.put("creativeBackground", poem.getCreativeBackground());
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
            // 这一批是后加的：optXxx 拿不到就给默认值，所以升级前存下的收藏/历史快照照样能读。
            poem.setUid(json.optString("uid"));
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
            poem.setIntroduction(json.optString("introduction"));
            poem.setCreativeBackground(json.optString("creativeBackground"));
            poem.setScore(json.optInt("score"));
            poem.setNChar(json.optInt("nChar"));
            poem.setFavoriteAt(json.optLong("favoriteAt"));
            return poem;
        } catch (Exception e) {
            return null;
        }
    }
}

package com.example.poetry.tts;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * espeak-ng-data 数据文件的定位、安装与升级。
 * <p>
 * espeak 只有在数据目录齐备时才能初始化，而数据（{@code phondata}/{@code phontab}/{@code *_dict} 等）
 * 是构建产物，仓库里没有，因此这里从 APK 的 {@code assets/espeak-ng-data} 释放到私有目录。
 * <p>
 * 查找顺序（与 {@link com.example.poetry.data.local.DatabaseProvider} 保持一致的设计）：
 * <ol>
 *   <li>应用专属外部目录 {@code /sdcard/Android/data/<pkg>/files/espeak-ng-data/}（免权限，便于 adb 投放全量语种）；</li>
 *   <li>应用私有目录 {@code filesDir/espeak-ng-data/}（首次启动由 assets 自动释放）；</li>
 * </ol>
 * <p>
 * 内置数据集为「基础 + 中文（普通话 / 粤语 / 客家话）」精简版（含女声变体所需的 f3 共振峰文件），
 * 不提供英语等其他语种；需要更多语种时把完整 data 放到上面的外部目录即可，无需改代码。
 */
public final class EspeakData {

    private static final String TAG = "EspeakData";

    /** 数据目录名；espeak 内部会自行拼接 "/espeak-ng-data"，故传给原生的是它的父目录 */
    public static final String DIR_NAME = "espeak-ng-data";
    /** assets 下的同名目录 */
    public static final String ASSET_DIR = DIR_NAME;

    /** espeak 初始化必需的基础文件（对齐上游 CheckVoiceData#BASE_RESOURCES） */
    private static final String[] BASE_RESOURCES = {
            "version", "intonations", "phondata", "phonindex", "phontab", "en_dict"
    };

    private static final Object LOCK = new Object();

    private EspeakData() {
    }

    /** 推荐投放目录：把完整 espeak-ng-data 放这里即可生效（无需权限） */
    @Nullable
    public static File recommendDir(@NonNull Context context) {
        File external = context.getExternalFilesDir(null);
        if (external == null) {
            return null;
        }
        File dir = new File(external, DIR_NAME);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** 私有目录（assets 释放的目标） */
    @NonNull
    public static File internalDir(@NonNull Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    /**
     * 当前可用的数据目录；优先外部投放版，其次内部版；都没有返回 null。
     */
    @Nullable
    public static File resolveDir(@NonNull Context context) {
        File external = recommendDir(context);
        if (external != null && hasBaseResources(external)) {
            return external;
        }
        File internal = internalDir(context);
        return hasBaseResources(internal) ? internal : null;
    }

    /**
     * 交给 espeak 的路径参数：必须是 espeak-ng-data 的<b>父目录</b>。
     */
    @NonNull
    public static String parentPath(@NonNull Context context) {
        File dir = resolveDir(context);
        if (dir != null) {
            File parent = dir.getParentFile();
            if (parent != null) {
                return parent.getAbsolutePath();
            }
        }
        return context.getFilesDir().getAbsolutePath();
    }

    public static boolean hasBaseResources(@NonNull File dir) {
        if (!dir.isDirectory()) {
            return false;
        }
        for (String name : BASE_RESOURCES) {
            File file = new File(dir, name);
            if (!file.exists() || file.length() <= 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasBaseResources(@NonNull Context context) {
        return resolveDir(context) != null;
    }

    /** 数据是否已就绪 */
    public static boolean isReady(@NonNull Context context) {
        return hasBaseResources(context);
    }

    @Nullable
    public static String readVersion(@NonNull File dir) {
        File version = new File(dir, "version");
        if (!version.exists()) {
            return null;
        }
        InputStream in = null;
        try {
            in = new java.io.FileInputStream(version);
            byte[] buf = new byte[64];
            int read = in.read(buf);
            return read <= 0 ? null : new String(buf, 0, read, "UTF-8").trim();
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    @Nullable
    public static String installedVersion(@NonNull Context context) {
        File dir = internalDir(context);
        return readVersion(dir);
    }

    @Nullable
    public static String assetVersion(@NonNull Context context) {
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET_DIR + "/version");
            byte[] buf = new byte[64];
            int read = in.read(buf);
            return read <= 0 ? null : new String(buf, 0, read, "UTF-8").trim();
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * 确保内部数据目录就绪：首次启动或 assets 版本变化时增量释放。
     * <b>必须在工作线程调用</b>（约 3MB 的 IO）。
     *
     * @return 可用的数据目录；失败返回 null。
     */
    @Nullable
    @WorkerThread
    public static File ensureInstalled(@NonNull Context context) {
        synchronized (LOCK) {
            File dir = internalDir(context);
            String wanted = assetVersion(context);
            String current = readVersion(dir);
            boolean upToDate = hasBaseResources(dir) && wanted != null && wanted.equals(current);
            if (upToDate) {
                return dir;
            }
            if (!copyAssets(context, dir)) {
                Log.w(TAG, "释放 espeak-ng-data 失败");
                return null;
            }
            Log.i(TAG, "已释放 espeak-ng-data 到 " + dir.getAbsolutePath());
            return hasBaseResources(dir) ? dir : null;
        }
    }

    /** 删除内部释放的数据（用于「重置语音数据」） */
    public static void clearInternal(@NonNull Context context) {
        synchronized (LOCK) {
            deleteRecursive(internalDir(context));
        }
    }

    private static boolean copyAssets(@NonNull Context context, @NonNull File targetDir) {
        AssetManager assets = context.getAssets();
        File temp = new File(targetDir.getParentFile(), DIR_NAME + ".tmp");
        File backup = new File(targetDir.getParentFile(), DIR_NAME + ".old");
        deleteRecursive(temp);
        try {
            if (!temp.exists() && !temp.mkdirs()) {
                return false;
            }
            copyAssetTree(assets, ASSET_DIR, temp);
            writeVersion(temp, wantedVersion(context));
            deleteRecursive(backup);
            if (targetDir.exists() && !targetDir.renameTo(backup)) {
                deleteRecursive(targetDir);
            }
            if (!temp.renameTo(targetDir)) {
                return false;
            }
            deleteRecursive(backup);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "复制 espeak-ng-data 失败", e);
            deleteRecursive(temp);
            return false;
        }
    }

    @NonNull
    private static String wantedVersion(@NonNull Context context) {
        String version = assetVersion(context);
        return version == null ? "0" : version;
    }

    private static void writeVersion(@NonNull File dir, @NonNull String version) throws IOException {
        File file = new File(dir, "version");
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(version.getBytes("UTF-8"));
            out.write('\n');
        } finally {
            closeQuietly(out);
        }
    }

    private static void copyAssetTree(@NonNull AssetManager assets, @NonNull String assetPath,
                                      @NonNull File dest) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null) {
            throw new IOException("缺失 assets 目录：" + assetPath);
        }
        if (children.length == 0) {
            copyAssetFile(assets, assetPath, dest);
            return;
        }
        if (!dest.exists() && !dest.mkdirs()) {
            throw new IOException("无法创建目录：" + dest);
        }
        for (String child : children) {
            copyAssetTree(assets, assetPath + "/" + child, new File(dest, child));
        }
    }

    private static void copyAssetFile(@NonNull AssetManager assets, @NonNull String assetPath,
                                      @NonNull File dest) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = assets.open(assetPath);
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("无法创建目录：" + parent);
            }
            out = new FileOutputStream(dest);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void deleteRecursive(@Nullable File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void closeQuietly(@Nullable Object stream) {
        if (stream == null) {
            return;
        }
        try {
            if (stream instanceof InputStream) {
                ((InputStream) stream).close();
            } else if (stream instanceof OutputStream) {
                ((OutputStream) stream).close();
            }
        } catch (IOException ignored) {
            // ignore
        }
    }
}

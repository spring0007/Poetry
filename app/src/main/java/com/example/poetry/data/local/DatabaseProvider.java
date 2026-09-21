package com.example.poetry.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 数据库文件定位器。
 * <p>
 * 诗库来自 {@code E:\chinese-poetry-master\poetry-pipeline\dist}（poetry.db 约 110MB），
 * 体积太大不适合打进 APK，因此按以下顺序查找，命中即用：
 * <ol>
 *   <li>应用专属外部目录 {@code /sdcard/Android/data/<pkg>/files/Poetry/}（免权限，推荐用 adb push 投放）；</li>
 *   <li>应用私有目录 {@code /data/data/<pkg>/files/Poetry/}；</li>
 *   <li>assets/poetry/ 下的同名文件（首次启动自动拷到私有目录，适合调试期内置小样本）。</li>
 * </ol>
 * 三个位置都没有时，Repository 会回退到 {@link SeedDataSource} 的内置占位数据，
 * 保证 UI 在任何情况下都能跑通。
 */
public final class DatabaseProvider {

    private static final String TAG = "DatabaseProvider";

    public static final String POETRY_DB = "poetry.db";
    public static final String STRAIN_DB = "poetry-strains.db";
    public static final String CHECK_DB = "poetry-checks.db";

    /** 放置目录名（在应用专属目录 / 私有目录下） */
    private static final String DIR_NAME = "Poetry";
    /** assets 下的目录 */
    private static final String ASSET_DIR = "poetry";
    private static final String PREF = "poetry_db";
    private static final String KEY_COPIED = "copied_";

    private DatabaseProvider() {
    }

    /**
     * 推荐投放目录：把 dist 里的 .db 拷到这里即可生效（无需任何权限）。
     */
    @Nullable
    public static File recommendDir(@NonNull Context context) {
        File external = context.getExternalFilesDir(null);
        if (external == null) {
            return new File(context.getFilesDir(), DIR_NAME);
        }
        return new File(external, DIR_NAME);
    }

    /**
     * 查找可用的数据库文件；找不到返回 null。
     */
    @Nullable
    public static File locate(@NonNull Context context, @NonNull String fileName) {
        // 1. 应用专属外部目录
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            File candidate = new File(new File(external, DIR_NAME), fileName);
            if (candidate.exists() && candidate.length() > 0) {
                return candidate;
            }
        }
        // 2. 应用私有目录
        File internal = new File(new File(context.getFilesDir(), DIR_NAME), fileName);
        if (internal.exists() && internal.length() > 0) {
            return internal;
        }
        // 3. assets（首次自动复制）
        File copied = copyFromAssets(context, fileName);
        if (copied != null) {
            return copied;
        }
        return null;
    }

    public static boolean exists(@NonNull Context context, @NonNull String fileName) {
        return locate(context, fileName) != null;
    }

    /**
     * 从 assets/poetry/ 复制数据库到私有目录。已复制过则直接复用。
     */
    @Nullable
    private static File copyFromAssets(@NonNull Context context, @NonNull String fileName) {
        SharedPreferences pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        File dir = new File(context.getFilesDir(), DIR_NAME);
        File target = new File(dir, fileName);
        if (pref.getBoolean(KEY_COPIED + fileName, false) && target.exists()) {
            return target;
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = context.getAssets().open(ASSET_DIR + "/" + fileName);
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            out = new FileOutputStream(target);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            pref.edit().putBoolean(KEY_COPIED + fileName, true).apply();
            Log.i(TAG, "copied " + fileName + " from assets -> " + target.getAbsolutePath());
            return target;
        } catch (IOException e) {
            Log.w(TAG, "no bundled " + fileName + " in assets");
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
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

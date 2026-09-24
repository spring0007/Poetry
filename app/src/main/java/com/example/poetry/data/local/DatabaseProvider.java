package com.example.poetry.data.local;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.util.LogUtil;

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
     * 下载安装的落地路径。**必须解析，不能写死**：{@code locate()} 的查找顺序是
     * 外部目录 → 私有目录 → assets，外部目录优先级最高，所以开发时 adb push 过去的那份
     * 才盖得住下载来的那份。若把安装目标写死成私有目录，adb push 就永远不生效了。
     *
     * <p>已经有一份就原地替换；一份都没有时落在**私有目录**——{@code getExternalFilesDir()}
     * 可能返回 null，而且外部目录会被下一次 adb push 覆盖，那正是开发时想要的行为。
     */
    @NonNull
    public static File installTarget(@NonNull Context context, @NonNull String fileName) {
        // 用 locateExisting 而不是 locate：后者在两边都没有时会去拷 assets。
        // 下载路径上绝不能因为「先问一下装哪儿」就把 110 MB 从 assets 抄一遍。
        File existing = locateExisting(context, fileName);
        if (existing != null) {
            return existing;
        }
        return new File(new File(context.getFilesDir(), DIR_NAME), fileName);
    }

    /**
     * 下载中的临时文件。**必须与目标同目录**：{@code rename(2)} 只在同一个文件系统内才是
     * 原子的，跨卷的 rename 会退化成「拷贝 + 删除」，中途失败就留下半个文件。
     *
     * <p>名字里带 sha8 是为了让「上次下到一半、这次服务端换了版本」这种情形自动失效：
     * sha 变了就是另一个文件名，旧的会被 {@link #gcStaleParts} 收走。
     */
    @NonNull
    public static File partFile(@NonNull File target, @NonNull String sha8) {
        return new File(target.getParentFile(), target.getName() + "." + sha8 + ".part");
    }

    /**
     * 清掉目标目录下所有 sha 不匹配的 {@code *.part}。中断的下载会留下这些文件，
     * 而它们永远不会再被用上——sha 相同的那一个才是能续传的。
     */
    public static void gcStaleParts(@NonNull File target, @NonNull String keepSha8) {
        File dir = target.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        String prefix = target.getName() + ".";
        String keepName = partFile(target, keepSha8).getName();
        for (File file : files) {
            String name = file.getName();
            if (!name.startsWith(prefix) || !name.endsWith(".part") || name.equals(keepName)) {
                continue;
            }
            if (file.delete()) {
                LogUtil.i("gc stale part " + name);
            }
        }
    }

    /**
     * 查找可用的数据库文件；找不到返回 null。
     */
    @Nullable
    public static File locate(@NonNull Context context, @NonNull String fileName) {
        File existing = locateExisting(context, fileName);
        if (existing != null) {
            return existing;
        }
        // 3. assets（首次自动复制）
        return copyFromAssets(context, fileName);
    }

    /**
     * 只在前两个位置找，**不碰 assets**——没有副作用，可以在「问一句装哪儿」时放心调用。
     */
    @Nullable
    private static File locateExisting(@NonNull Context context, @NonNull String fileName) {
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
        // Copy to a temporary file and rename at the end. If the app is killed mid-copy
        // (users do swipe a frozen cold start away), the real path stays nonexistent and
        // the next launch retries cleanly instead of opening a truncated database.
        File temp = new File(dir, fileName + ".part");
        try {
            in = context.getAssets().open(ASSET_DIR + "/" + fileName);
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            if (temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            out = new FileOutputStream(temp);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            try {
                out.close();
            } catch (IOException ignored) {
                // handled below
            }
            out = null;
            if (!temp.renameTo(target)) {
                throw new IOException("rename failed: " + temp);
            }
            pref.edit().putBoolean(KEY_COPIED + fileName, true).apply();
            LogUtil.i("copied " + fileName + " from assets -> " + target.getAbsolutePath());
            return target;
        } catch (IOException e) {
            LogUtil.w("no bundled " + fileName + " in assets");
            if (temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
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

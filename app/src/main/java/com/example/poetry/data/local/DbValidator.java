package com.example.poetry.data.local;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import com.example.poetry.data.remote.ApiException;
import com.example.poetry.util.LogUtil;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * 安装前的结构探针：拿一个**还没安装**的 {@code poetry.db}（通常是 {@code .part} 碎片）
 * 试着开一下，确认它是这个 App 认得的库。
 *
 * <h3>为什么不用 {@code PRAGMA integrity_check}</h3>
 * 那是全 B 树遍历。测试期的子集库只有 2 MB 无所谓，生产库 115 MB 走在安装路径上就太慢了，
 * 而**内容完整性其实由 sha256 保证**——这里只回答「结构对不对」。
 *
 * <h3>探针句柄必须在 rename 之前关掉</h3>
 * 否则目标文件名上会短暂地同时存在两个句柄，而其中一个握的是**旧 inode**——
 * 后面 {@code PoetryDatabase.reload()} 打开的那个未必是刚装好的那份。
 *
 * <p>异常类型复用 {@link ApiException}：它的编号体系（CANCELLED/NO_SPACE/CHECKSUM/SCHEMA…）
 * 正好是 {@code DbInstaller} 要往界面报的那几种，为它再搭一套平行异常层次没有意义。
 */
public final class DbValidator {

    /**
     * 缺了任何一张，这个库都不是本 App 的库。
     *
     * <p>{@code poem_extras} 是 schema 3.3 才有的：3.0 的老库富文本直接写在
     * {@code poems.notes} 列里，主版本号同样是 3，光靠 {@code schemaCompatible()} 拦不住。
     * 而老库对本 App 是**致命的**——所有查询都会因为缺列而静默返回空列表。
     * 所以这件事必须在安装前用表清单挡住。
     */
    private static final String[] REQUIRED_TABLES = {
            "poems", "authors", "rhythmics", "sources", "poem_extras", "meta"
    };

    private DbValidator() {
    }

    /**
     * @throws ApiException {@link ApiException#SCHEMA} 结构不符
     */
    public static void check(@NonNull File file) throws ApiException {
        if (!file.isFile() || file.length() == 0L) {
            throw new ApiException(ApiException.SCHEMA, "诗库文件不存在或为空");
        }

        SQLiteDatabase probe = null;
        try {
            probe = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
        } catch (Exception e) {
            throw new ApiException(ApiException.SCHEMA,
                    "诗库无法打开（可能已损坏）: " + e.getMessage(), e);
        }
        try {
            checkTables(probe);
            PoetryDatabase.Meta meta = PoetryDatabase.readMetaFrom(probe);
            if (meta == null) {
                throw new ApiException(ApiException.SCHEMA, "读不到 meta 表");
            }
            if (!meta.schemaCompatible()) {
                throw new ApiException(ApiException.SCHEMA,
                        "诗库版本不兼容（库 " + meta.schemaVersion
                                + "，App " + PoetryDatabase.SCHEMA_VERSION + "），请升级 App");
            }
            checkPoemCount(probe, meta);
            checkSources(probe);
        } finally {
            // 必须在这里关，而且要赶在调用方 rename 之前——见类注释。
            try {
                probe.close();
            } catch (Exception e) {
                LogUtil.w("关闭探针句柄失败", e);
            }
        }
    }

    private static void checkTables(@NonNull SQLiteDatabase probe) throws ApiException {
        Set<String> present = new HashSet<>();
        Cursor c = null;
        try {
            c = probe.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null);
            while (c.moveToNext()) {
                present.add(c.getString(0));
            }
        } catch (Exception e) {
            throw new ApiException(ApiException.SCHEMA, "读 sqlite_master 失败", e);
        } finally {
            closeQuietly(c);
        }
        for (String table : REQUIRED_TABLES) {
            if (!present.contains(table)) {
                throw new ApiException(ApiException.SCHEMA, "诗库缺少表: " + table);
            }
        }
    }

    /**
     * 库自己声称的篇数要和实际行数对得上——主要抓「meta 属于另一个文件」这种张冠李戴。
     *
     * <p><b>容差是必需的，不是偷懒</b>：线上那份完整库本身就差 1
     * （{@code meta.n_poems}=345359，{@code COUNT(*)}=345358），这是历史遗留、不打算修。
     * 严格的相等会把生产库挡在门外。截断的库会少掉整页的行（几百行量级），
     * 1% 的容差抓得住，而那本来就是 sha256 的活。
     */
    private static void checkPoemCount(@NonNull SQLiteDatabase probe, @NonNull PoetryDatabase.Meta meta)
            throws ApiException {
        long count = scalar(probe, "SELECT COUNT(*) FROM poems");
        if (count <= 0) {
            throw new ApiException(ApiException.SCHEMA, "诗库是空的");
        }
        if (meta.nPoems > 0) {
            long tolerance = Math.max(16L, meta.nPoems / 100L);
            if (Math.abs(count - meta.nPoems) > tolerance) {
                throw new ApiException(ApiException.SCHEMA,
                        "诗库自相矛盾：meta 说 " + meta.nPoems + " 首，实际 " + count + " 首");
            }
        }
    }

    /** {@code rhythmics} 允许为 0（子集库保留了全部，但空库也不该拦），{@code sources} 不行。 */
    private static void checkSources(@NonNull SQLiteDatabase probe) throws ApiException {
        if (scalar(probe, "SELECT COUNT(*) FROM sources") <= 0) {
            throw new ApiException(ApiException.SCHEMA, "诗库缺少来源（sources）");
        }
    }

    private static long scalar(@NonNull SQLiteDatabase probe, @NonNull String sql)
            throws ApiException {
        Cursor c = null;
        try {
            c = probe.rawQuery(sql, null);
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } catch (Exception e) {
            throw new ApiException(ApiException.SCHEMA, "查询失败: " + sql, e);
        } finally {
            closeQuietly(c);
        }
    }

    private static void closeQuietly(Cursor c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}

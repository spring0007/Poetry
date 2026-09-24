package com.example.poetry.data.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 服务端 {@code version.json} 的解析结果。
 *
 * <p>放在 {@code poetry.db} 旁边，约 200 字节：
 * <pre>
 * { "schema_version": "3.0", "built_at": "2026-09-24T12:00:00",
 *   "bytes": 2310144, "sha256": "&lt;64位小写十六进制&gt;",
 *   "is_subset": true, "n_poems": 2062,
 *   "url": "https://&lt;host&gt;/poetry/poetry.db",
 *   "note": "测试期子集库" }
 * </pre>
 *
 * <p>{@link #sha256} 指的是**未压缩的服务端文件**。请求时带 {@code Accept-Encoding: identity}，
 * 否则中间有 gzip 代理时 Content-Length、Range 偏移和这里算出来的 sha 全都指向压缩后的字节，
 * 整套校验会静默错位。
 */
public final class DbManifest {

    public final String schemaVersion;
    public final String builtAt;
    public final long bytes;
    public final String sha256;
    public final boolean isSubset;
    public final long nPoems;
    /** 真实下载地址。放在 payload 里，以后换 CDN 不用发版。 */
    public final String url;
    public final String note;

    private DbManifest(String schemaVersion, String builtAt, long bytes, String sha256,
                       boolean isSubset, long nPoems, String url, String note) {
        this.schemaVersion = schemaVersion;
        this.builtAt = builtAt;
        this.bytes = bytes;
        this.sha256 = sha256;
        this.isSubset = isSubset;
        this.nPoems = nPoems;
        this.url = url;
        this.note = note;
    }

    /**
     * @param fallbackUrl manifest 里没写 url 时用它（也就是 {@link DbDownloadPolicy#MANIFEST_URL}
     *                    同目录下的 poetry.db）
     * @throws ApiException {@link ApiException#PARSE} 字段缺失或格式不对
     */
    @NonNull
    public static DbManifest parse(@NonNull String json, @NonNull String fallbackUrl)
            throws ApiException {
        try {
            JSONObject o = new JSONObject(json);
            // Locale.ROOT：sha256 是十六进制，跟用户的语言无关。用默认 locale 的话，
            // 土耳其语环境里 "I".toLowerCase() 会得到 "ı"，比较就永远不相等。
            String sha = o.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
            long bytes = o.optLong("bytes", -1L);
            if (sha.length() != 64 || !isHex(sha)) {
                throw new ApiException(ApiException.PARSE, "version.json 的 sha256 不是 64 位十六进制");
            }
            if (bytes <= 0) {
                throw new ApiException(ApiException.PARSE, "version.json 的 bytes 非法: " + bytes);
            }
            String url = o.optString("url", "").trim();
            if (url.isEmpty()) {
                url = fallbackUrl;
            }
            return new DbManifest(
                    o.optString("schema_version", "").trim(),
                    o.optString("built_at", "").trim(),
                    bytes,
                    sha,
                    o.optBoolean("is_subset", false),
                    o.optLong("n_poems", 0L),
                    url,
                    o.optString("note", ""));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ApiException.PARSE, "version.json 解析失败: " + e.getMessage(), e);
        }
    }

    private static boolean isHex(@NonNull String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 日志用 */
    @NonNull
    @Override
    public String toString() {
        return "DbManifest{v" + schemaVersion + " subset=" + isSubset + " " + bytes + "B "
                + sha256.substring(0, Math.min(8, sha256.length())) + " @" + builtAt + "}";
    }

    /**
     * 与本地库对一下谁新。**{@code is_subset} 参与身份判定**：服务端换成完整库时，
     * 哪怕子集库的 built_at 更新，也绝不能算「已是最新」。
     *
     * <p>测试期转生产就是把服务端的 {@code is_subset} 改成 {@code false}——不发版、不重装。
     *
     * @param localSha    上次安装成功的 sha（SharedPreferences 里的），没装过传 null
     * @param localSubset 本地库的 meta.subset
     * @param localBuiltAt 本地库的 meta.built_at
     */
    public boolean isNewerThan(@Nullable String localSha, boolean localSubset,
                               @Nullable String localBuiltAt) {
        if (localSha != null && localSha.equalsIgnoreCase(sha256)) {
            return false;                       // 一模一样
        }
        if (localSubset && !isSubset) {
            return true;                        // 子集 → 完整：总是升级
        }
        if (localSubset != isSubset) {
            return false;                       // 完整 → 子集：降级，不干
        }
        if (localBuiltAt == null || localBuiltAt.isEmpty()) {
            return true;
        }
        return builtAt.compareTo(localBuiltAt) > 0;
    }
}

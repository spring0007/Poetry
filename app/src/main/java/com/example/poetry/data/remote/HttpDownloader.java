package com.example.poetry.data.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.util.LogUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 诗库的 HTTP 下载器：流式写盘 + 断点续传 + 边下边算 sha256 + 可取消。
 *
 * <p>用 {@link HttpURLConnection} 而不是 OkHttp：这个项目的依赖表是刻意精简的
 * （见 {@code app/build.gradle.kts}），而这里要的只有「GET 一个静态文件、读字节流」，
 * 标准库完全够。
 *
 * <p><b>阻塞式</b>，线程由调用方持有（{@code DbInstaller} 把
 * 检查 → 下载 → 校验 → 安装 写成直线代码，用 {@link #cancel()} 打断）。
 * <b>绝不能跑在 {@code AppExecutors.io} 上</b>——那是单线程 executor，一个 100 MB 的下载
 * 会把每一个查询饿死。
 *
 * <h3>续传为什么不需要 If-Range</h3>
 * {@code If-Range} 要拿服务端上次给的 ETag，而我们没有跨进程保留它的地方（不想为这个加
 * sidecar 文件）。这里靠**别的机制**达成同样的安全性：临时文件名里嵌了目标版本的 sha8
 * （见 {@code DatabaseProvider.partFile}），而调用方在下载前会 {@code gcStaleParts} 掉所有
 * sha 不匹配的碎片。sha256 就是内容身份——服务端文件一变，manifest 的 sha 就变，旧碎片
 * 当场被删，根本轮不到「往旧内容后面接新内容」。
 */
public final class HttpDownloader {

    private static final int BUFFER = 64 * 1024;
    /** 进度节流：至少涨这么多字节才投递一次 */
    private static final long MIN_PROGRESS_BYTES = 64 * 1024;
    /** 进度节流：或者至少隔这么久投递一次。不节流的话 100 MB 会发上千次回调。 */
    private static final long MIN_PROGRESS_MS = 200L;
    /** 错误响应体只截这么多字符进异常消息 */
    private static final int ERROR_BODY_LIMIT = 200;
    /** version.json 的读取上限，防止对端挂了个超大文件把我们读爆 */
    private static final int TEXT_LIMIT = 64 * 1024;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * 请求取消。**可以跨线程调用**。取消后当前这次下载以
     * {@link ApiException#CANCELLED} 收尾，而 {@code .part} **留在磁盘上**以便续传。
     */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    // ---------------------------------------------------------------- 小文件

    /**
     * GET 一个文本资源（version.json）。整段读进内存，上限 {@link #TEXT_LIMIT}。
     */
    @NonNull
    public String getText(@NonNull String url, int readTimeoutMs) throws ApiException {
        HttpURLConnection conn = null;
        try {
            conn = open(url, readTimeoutMs);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw httpError(code, conn);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) > 0) {
                    if (cancelled.get()) {
                        throw new ApiException(ApiException.CANCELLED, "已取消");
                    }
                    out.write(buf, 0, read);
                    if (out.size() > TEXT_LIMIT) {
                        throw new ApiException(ApiException.PARSE, "响应体过大，不是 version.json");
                    }
                }
                return out.toString("UTF-8");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ApiException.NETWORK, "请求失败: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---------------------------------------------------------------- 大文件

    /**
     * 把 {@code url} 下载到 {@code part}，边下边校验。
     *
     * <p>成功时（长度与 sha256 都对得上）调 {@code onFinished}，**不负责安装**——安装要在
     * {@code AppExecutors.io} 的一个任务里做，那是调用方的事。
     *
     * @param expectedBytes {@code version.json} 里的 bytes，用来定进度总量；&lt;= 0 表示不知道
     * @param expectedSha   {@code version.json} 里的 sha256；null 表示不校验
     */
    public void download(@NonNull String url, @NonNull File part, long expectedBytes,
                         @Nullable String expectedSha, @NonNull DownloadCallback callback) {
        long resumeFrom = part.exists() ? part.length() : 0L;
        try {
            File parent = part.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new ApiException(ApiException.NETWORK, "无法创建目录: " + parent);
            }

            MessageDigest digest = newDigest();
            transfer(url, part, resumeFrom, expectedBytes, digest, callback);

            long length = part.length();
            if (expectedBytes > 0 && length != expectedBytes) {
                // 少了是断流；多了是服务器在续传请求上返回了 200 而我们没接住。
                // 两种都不该装，且必须把碎片删掉，否则下次会从垃圾继续接。
                deleteQuietly(part);
                throw new ApiException(ApiException.SHORT_READ,
                        "下载不完整: " + length + " / " + expectedBytes + " 字节");
            }

            String actualSha = hex(digest.digest());
            if (expectedSha != null && !expectedSha.isEmpty()
                    && !expectedSha.equalsIgnoreCase(actualSha)) {
                deleteQuietly(part);
                throw new ApiException(ApiException.CHECKSUM,
                        "校验失败: 期望 " + sha8(expectedSha) + "，实际 " + sha8(actualSha));
            }

            callback.onProgress(length, length);
            callback.onFinished(part, actualSha);
        } catch (ApiException e) {
            callback.onFailed(e);
        } catch (Exception e) {
            callback.onFailed(new ApiException(ApiException.NETWORK,
                    "下载失败: " + e.getMessage(), e));
        }
    }

    /**
     * 真正搬字节。**不关连接以外的任何东西**：碎片留不留由 {@link #download} 决定，
     * 因为只有它知道失败是「内容不对」还是「只是断了一下」。
     */
    private void transfer(@NonNull String url, @NonNull File part, long resumeFrom,
                          long expectedBytes, @NonNull MessageDigest digest,
                          @NonNull DownloadCallback callback) throws ApiException {
        HttpURLConnection conn = null;
        try {
            conn = open(url, DbDownloadPolicy.READ_TIMEOUT_MS);
            if (resumeFrom > 0) {
                conn.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
            }
            int code = conn.getResponseCode();

            boolean append;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                append = resumeFrom > 0;
            } else if (code == HttpURLConnection.HTTP_OK) {
                // 两种情形都落这里：
                //  a) 本来就没请求续传（resumeFrom == 0）——正常；
                //  b) 请求了续传但服务器不支持 Range，把整个文件发回来了。
                // (b) 必须从头写：往半成品后面追加完整响应体，会得到一个**比预期更长**的
                // 损坏文件，而且 sha 校验会以一种很难看懂的方式失败。
                if (resumeFrom > 0) {
                    LogUtil.i("服务器忽略了 Range，从头重下");
                    resumeFrom = 0L;
                }
                append = false;
            } else {
                throw httpError(code, conn);
            }

            // 续传时 digest 要先把已有前缀喂进去，否则算出来的是后半段的 sha。
            // append=false 时 resumeFrom 已被清零，正好跳过。
            long written = resumeFrom;
            if (append && resumeFrom > 0) {
                feedPrefix(part, resumeFrom, digest);
            }

            long total = expectedBytes > 0 ? expectedBytes
                    : (conn.getContentLengthLong() > 0
                            ? conn.getContentLengthLong() + resumeFrom : -1L);

            long lastPostedBytes = written;
            long lastPostedAt = System.currentTimeMillis();
            callback.onProgress(written, total);

            // append=false 时 FileOutputStream 的第二参数就是「截断」，不需要额外删文件——
            // 保持同一个 inode，万一有人正拿着它也不会突然变成「文件不存在」。
            // 类型就是 FileOutputStream 而不是 OutputStream：下面要 getFD().sync()，
            // 那是把「已经写进页缓存」变成「真的落了盘」，保证进程被杀时碎片是可续传的。
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[BUFFER];
                int read;
                while ((read = in.read(buf)) > 0) {
                    if (cancelled.get()) {
                        out.flush();
                        throw new ApiException(ApiException.CANCELLED, "已取消");
                    }
                    out.write(buf, 0, read);
                    digest.update(buf, 0, read);
                    written += read;

                    long now = System.currentTimeMillis();
                    if (written - lastPostedBytes >= MIN_PROGRESS_BYTES
                            || now - lastPostedAt >= MIN_PROGRESS_MS) {
                        lastPostedBytes = written;
                        lastPostedAt = now;
                        callback.onProgress(written, total);
                    }
                }
                out.flush();
                out.getFD().sync();
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            // 传输中途断了。**这里不删 part**，留着给下次续传。
            throw new ApiException(ApiException.NETWORK,
                    "传输中断: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 独立算一个文件的 sha256：安装前复核用，也用来给 {@code meta} 之外的地方校验。 */
    @NonNull
    public static String sha256Of(@NonNull File file) throws ApiException {
        MessageDigest digest = newDigest();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[BUFFER];
            int read;
            while ((read = in.read(buf)) > 0) {
                digest.update(buf, 0, read);
            }
            return hex(digest.digest());
        } catch (IOException e) {
            throw new ApiException(ApiException.NETWORK, "读取失败: " + e.getMessage(), e);
        }
    }

    private static void feedPrefix(@NonNull File part, long length,
                                   @NonNull MessageDigest digest) throws IOException {
        try (InputStream in = new FileInputStream(part)) {
            byte[] buf = new byte[BUFFER];
            long left = length;
            while (left > 0) {
                int read = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (read <= 0) {
                    throw new IOException("碎片文件短于预期: " + length);
                }
                digest.update(buf, 0, read);
                left -= read;
            }
        }
    }

    private static void deleteQuietly(@NonNull File file) {
        if (file.exists() && !file.delete()) {
            LogUtil.w("删不掉 " + file.getName());
        }
    }

    // ---------------------------------------------------------------- HTTP 细节

    private static HttpURLConnection open(@NonNull String url, int readTimeoutMs)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(DbDownloadPolicy.CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(readTimeoutMs);
        conn.setUseCaches(false);
        // 有 gzip 代理时，Content-Length、Range 偏移和 sha 全都指向**压缩后**的字节，
        // 整套校验会静默错位。所以明确要求原样传输。
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("User-Agent", "PoetryApp/1.0 (Android)");
        return conn;
    }

    /**
     * 把非 2xx 变成一个带响应体片段的异常。不这么做的话，拿到手的只是一个光秃秃的
     * {@code FileNotFoundException}，完全看不出服务端到底说了什么。
     */
    @NonNull
    private static ApiException httpError(int code, @NonNull HttpURLConnection conn) {
        String snippet = "";
        try (InputStream err = conn.getErrorStream()) {
            if (err != null) {
                byte[] buf = new byte[ERROR_BODY_LIMIT];
                int read = err.read(buf);
                if (read > 0) {
                    snippet = new String(buf, 0, read, "UTF-8").replaceAll("\\s+", " ").trim();
                }
            }
        } catch (Exception ignored) {
            // 读不到就算了
        }
        String message = "HTTP " + code + (snippet.isEmpty() ? "" : " · " + snippet);
        return new ApiException(ApiException.HTTP, message);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    @NonNull
    private static String hex(@NonNull byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    /** 日志和异常消息里只露前 8 位，够定位是哪个版本了 */
    @NonNull
    public static String sha8(@NonNull String sha) {
        return sha.substring(0, Math.min(8, sha.length()));
    }
}

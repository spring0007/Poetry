package com.example.poetry.data.remote;

import android.util.Base64;

import androidx.annotation.NonNull;

import org.json.JSONObject;

/**
 * 只做一件事：从 JWT 里读出 {@code exp}。
 *
 * <p>为什么不直接解析响应里的 {@code expiresAt} 字符串：Go 的 {@code time.Time}
 * 序列化成 RFC3339，会带纳秒和冒号时区（{@code 2026-11-08T13:12:00.123456+08:00}），
 * 而 minSdk 24 用不了 {@code java.time}，{@code SimpleDateFormat} 又吃不下这种格式
 * （要么丢纳秒要么改冒号），得写一段挺脆的字符串手术。
 * JWT 的 {@code exp} 是一个 Unix 秒整数，没有格式歧义，解出来就是对的。
 *
 * <p><b>这里只解码、不验签</b>。前端拿不到密钥，验签本来也无从谈起 ——
 * 签名由服务端在每次请求时校验。本地读 {@code exp} 只是为了避免「明知道过期了还发一次请求」，
 * 属于体验优化，不是安全措施。
 */
final class Jwt {

    private Jwt() {
    }

    /**
     * 取 token 的到期时间（毫秒）。解不出来就返回 0，调用方按「未知」处理
     * （{@code UserStore.isAuthTokenValid()} 对 0 是放行的，最终由服务端的 401 兜底）。
     */
    static long expiryMillis(@NonNull String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return 0L;
            }
            byte[] payload = Base64.decode(parts[1],
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            JSONObject json = new JSONObject(new String(payload, "UTF-8"));
            long exp = json.optLong("exp", 0L);
            return exp > 0 ? exp * 1000L : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
}

package com.example.poetry.wxapi;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.example.poetry.R;
import com.example.poetry.auth.WeChatAuth;
import com.example.poetry.data.local.UserStore;

/**
 * 微信授权回调页。
 *
 * <p>微信 SDK 硬性要求本类必须位于 {@code &lt;applicationId&gt;.wxapi} 包下、类名固定为
 * {@code WXEntryActivity}，所以单独放这个包。没有接 SDK 时它同样能编译，
 * 只是永远不会被微信拉起。
 *
 * <p>职责只有一件：接住微信的授权结果 → 落库 → 结束自己。
 * 登录页（LoginActivity）在 onResume 里发现自己已登录后自行收尾跳转。
 */
public class WXEntryActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WeChatAuth.handleIntent(this, getIntent(), (ok, code, message) -> {
            UserStore store = UserStore.get(WXEntryActivity.this);
            if (ok && code != null && !code.isEmpty()) {
                // 真实接入后：code 应交由后端用 AppSecret 换 openid / access_token，
                // 拿到 openid 与昵称再落库。当前先把 code 作为会话标识。
                store.setLogin("wechat", "wx-" + code, null);
            } else {
                Toast.makeText(this, getString(R.string.login_wechat_failed,
                        message == null ? "" : message), Toast.LENGTH_SHORT).show();
            }
            finish();
        });
    }
}

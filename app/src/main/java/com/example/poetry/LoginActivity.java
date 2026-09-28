package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.poetry.auth.AuthGuard;
import com.example.poetry.auth.WeChatAuth;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.databinding.ActivityLoginBinding;
import com.example.poetry.ui.Skin;

import java.util.Locale;
import java.util.Random;

/**
 * 登录页：微信授权为主路径，未配置 SDK / AppID 时自动落到模拟登录。
 *
 * <p>进本页的来源有两种：
 * <ol>
 *   <li>AuthGuard 拦截（带着被拦截页面的原始 Intent）→ 登录后原路送回；</li>
 *   <li>用户在「我的」页主动点登录 → 登录后直接返回上一页。</li>
 * </ol>
 */
public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private UserStore store;
    private boolean finishing;

    public static void open(@NonNull Context context) {
        context.startActivity(new Intent(context, LoginActivity.class));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        store = UserStore.get(this);

        binding.backButton.setOnClickListener(v -> finish());
        binding.loginWechat.setOnClickListener(v -> tryWechatLogin());
        binding.loginMock.setOnClickListener(v -> mockLogin());
        refreshMockVisibility();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 微信回调在 WXEntryActivity 里完成落库；回到本页时若已登录则直接收尾
        if (!finishing && store.isLoggedIn()) {
            routeBackAfterLogin();
        }
    }

    /** 主路径：真实微信授权；拉不起来就按约定降级到模拟登录 */
    private void tryWechatLogin() {
        if (WeChatAuth.isConfigured(this) && WeChatAuth.sendAuth(this)) {
            // 授权页起来后，结果会回到 wxapi/WXEntryActivity，那里落库，本页 onResume 收尾
            return;
        }
        Toast.makeText(this, R.string.login_wechat_missing, Toast.LENGTH_SHORT).show();
        mockLogin();
    }

    /**
     * 模拟登录：不连任何服务器，本地生成一个「诗友 xxxx」身份。
     * 未配置 AppID 时它是唯一的可玩路径，也是开发调试的开关。
     */
    private void mockLogin() {
        String uid = "mock-" + String.format(Locale.CHINA, "%06x",
                new Random().nextInt(0x1000000));
        String nickname = getString(R.string.login_mock_nickname,
                String.format(Locale.CHINA, "%04d", new Random().nextInt(10000)));
        store.setLogin("mock", uid, nickname);
        if (store.getNickname().isEmpty()) {
            store.setNickname(nickname);
        }
        Toast.makeText(this, getString(R.string.login_success, nickname),
                Toast.LENGTH_SHORT).show();
        routeBackAfterLogin();
    }

    /** 登录成功后的去向：有原始页面就原路送回，否则直接返回 */
    private void routeBackAfterLogin() {
        if (finishing) {
            return;
        }
        finishing = true;
        Intent pending = getIntent().getParcelableExtra(AuthGuard.EXTRA_PENDING);
        if (pending != null) {
            startActivity(pending);
        }
        finish();
    }

    /** 微信没配置时才露出模拟登录按钮；配置好后这条路径自动隐藏 */
    private void refreshMockVisibility() {
        boolean configured = WeChatAuth.isConfigured(this);
        binding.loginMock.setVisibility(configured ? View.GONE : View.VISIBLE);
        TextView mockNote = findViewById(R.id.loginMockNote);
        if (mockNote != null) {
            mockNote.setVisibility(configured ? View.GONE : View.VISIBLE);
        }
    }
}

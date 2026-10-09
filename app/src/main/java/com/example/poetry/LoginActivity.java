package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.poetry.auth.AuthGuard;
import com.example.poetry.auth.WeChatAuth;
import com.example.poetry.data.Callback;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.remote.ApiException;
import com.example.poetry.data.remote.LoginResult;
import com.example.poetry.databinding.ActivityLoginBinding;
import com.example.poetry.ui.Skin;

import java.util.Locale;
import java.util.Random;

/**
 * 登录页：三条路径，按「可用性」从高到低排。
 *
 * <ol>
 *   <li><b>手机号 + 验证码</b> —— 唯一一条不依赖任何第三方 App 的路。只要服务端通就能用，
 *       所以排在第一位。服务端没配地址（{@code API_ENABLED=false}）时整块收起；</li>
 *   <li><b>微信授权</b> —— 需要微信 App 与 AppID，装好即可用但门槛高；</li>
 *   <li><b>模拟登录</b> —— 完全不联网，本地生成临时身份。前两条都不通时的兜底，
 *       也是开发调试的开关。</li>
 * </ol>
 *
 * <p>进本页的来源有两种：
 * <ol>
 *   <li>AuthGuard 拦截（带着被拦截页面的原始 Intent）→ 登录后原路送回；</li>
 *   <li>用户在「我的」页主动点登录 → 登录后直接返回上一页。</li>
 * </ol>
 */
public class LoginActivity extends AppCompatActivity {

    /**
     * 「获取验证码」的重发间隔（秒）。
     * <p>与后端验证码的有效期无关，纯粹是防连点 —— 用户连按十次就会落下十条短信费用。
     */
    private static final int RESEND_SECONDS = 60;

    private ActivityLoginBinding binding;
    private UserStore store;
    private PoetryRepository repository;
    private boolean finishing;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 重发倒计时剩余秒数；> 0 期间「获取验证码」不可点 */
    private int resendLeft;

    private final Runnable resendTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (resendLeft <= 0) {
                binding.loginSendCode.setEnabled(true);
                binding.loginSendCode.setText(R.string.login_send_code);
                return;
            }
            binding.loginSendCode.setText(getString(R.string.login_resend_in, resendLeft));
            resendLeft--;
            handler.postDelayed(this, 1000L);
        }
    };

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
        repository = PoetryRepository.get(this);

        binding.backButton.setOnClickListener(v -> finish());
        binding.loginWechat.setOnClickListener(v -> tryWechatLogin());
        binding.loginMock.setOnClickListener(v -> mockLogin());
        binding.loginSendCode.setOnClickListener(v -> onSendCode());
        binding.loginPhoneSubmit.setOnClickListener(v -> onPhoneSubmit());
        refreshMockVisibility();
        refreshPhoneVisibility();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 微信回调在 WXEntryActivity 里完成落库；回到本页时若已登录则直接收尾
        if (!finishing && store.isLoggedIn()) {
            routeBackAfterLogin();
        }
    }

    @Override
    protected void onDestroy() {
        // 倒计时是打在已销毁视图上的 setText，不收掉会漏一个引用
        handler.removeCallbacks(resendTick);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 手机号

    /**
     * 下发验证码。
     *
     * <p>开发模式下后端会把固定验证码回显在 {@code data.devCode} 里，这里替用户填上 ——
     * 这是「本地联调时不用去翻服务端日志」的关键一步。生产环境该字段为空串，
     * 于是什么都不会发生（见 {@code PoetryRepository#sendCode}）。
     */
    private void onSendCode() {
        final String phone = binding.loginPhone.getText().toString().trim();
        if (!isPhone(phone)) {
            Toast.makeText(this, R.string.login_phone_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        binding.loginSendCode.setEnabled(false);
        repository.sendCode(phone, new Callback<String>() {
            @Override
            public void onData(@NonNull String devCode) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (devCode.isEmpty()) {
                    Toast.makeText(LoginActivity.this, R.string.login_code_sent,
                            Toast.LENGTH_SHORT).show();
                } else {
                    binding.loginCode.setText(devCode);
                    Toast.makeText(LoginActivity.this,
                            getString(R.string.login_code_sent_dev, devCode),
                            Toast.LENGTH_LONG).show();
                }
                startResendCountdown();
            }

            @Override
            public void onError(@Nullable Throwable error) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                binding.loginSendCode.setEnabled(true);
                Toast.makeText(LoginActivity.this,
                        getString(R.string.login_failed, describe(error)),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 登录 / 注册。后端对没见过的手机号会自动建号，所以这里没有单独的「注册」入口。 */
    private void onPhoneSubmit() {
        final String phone = binding.loginPhone.getText().toString().trim();
        final String code = binding.loginCode.getText().toString().trim();
        if (!isPhone(phone)) {
            Toast.makeText(this, R.string.login_phone_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        if (code.isEmpty()) {
            Toast.makeText(this, R.string.login_code_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        binding.loginPhoneSubmit.setEnabled(false);
        repository.login(phone, code, null, new Callback<LoginResult>() {
            @Override
            public void onData(@NonNull LoginResult result) {
                if (finishing) {
                    return;
                }
                // nickname 已在仓库层落进 UserStore，这里直接读回落库之后的值
                Toast.makeText(LoginActivity.this,
                        getString(R.string.login_success, store.getNickname()),
                        Toast.LENGTH_SHORT).show();
                routeBackAfterLogin();
            }

            @Override
            public void onError(@Nullable Throwable error) {
                if (finishing) {
                    return;
                }
                binding.loginPhoneSubmit.setEnabled(true);
                if (fallbackToMock(error)) {
                    return;
                }
                Toast.makeText(LoginActivity.this,
                        getString(R.string.login_failed, describe(error)),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startResendCountdown() {
        handler.removeCallbacks(resendTick);
        resendLeft = RESEND_SECONDS;
        handler.post(resendTick);
    }

    /**
     * 服务端链路本身挂了（连不上 / 熔断冷却中 / 5xx）时，落到本地模拟身份。
     *
     * <p>为什么不直接把错误抛给用户：他点「登录 / 注册」是为了进去用 App，
     * 不是为了拿到一份故障报告。而验证码填错、手机号被限流这类**服务端正常工作**
     * 的结果必须原样显示，所以这里只认 {@link ApiException#tripsBreaker()}。
     *
     * @return true = 已经改走模拟登录，调用方不要再说错误的事
     */
    private boolean fallbackToMock(@Nullable Throwable error) {
        if (!(error instanceof ApiException) || !((ApiException) error).tripsBreaker()) {
            return false;
        }
        Toast.makeText(this, R.string.login_api_offline, Toast.LENGTH_SHORT).show();
        mockLogin();
        return true;
    }

    /** 11 位、以 1 开头、全是数字。输入框的 digits 过滤器挡不住粘贴，所以仍要校验。 */
    private static boolean isPhone(@NonNull String phone) {
        if (phone.length() != 11 || phone.charAt(0) != '1') {
            return false;
        }
        for (int i = 0; i < phone.length(); i++) {
            char c = phone.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** 把异常翻译成能读的一句话。{@code ApiException} 的 message 已经是人话了，直接用。 */
    @NonNull
    private static String describe(@Nullable Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return (message == null || message.isEmpty()) ? error.toString() : message;
    }

    // ------------------------------------------------------------------ 微信 / 模拟

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
     * 未配置 AppID、服务端又不可用时它是唯一的可玩路径，也是开发调试的开关。
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

    // ------------------------------------------------------------------ 可见性

    /** 微信没配置时才露出模拟登录按钮；配置好后这条路径自动隐藏 */
    private void refreshMockVisibility() {
        boolean configured = WeChatAuth.isConfigured(this);
        binding.loginMock.setVisibility(configured ? View.GONE : View.VISIBLE);
        TextView mockNote = findViewById(R.id.loginMockNote);
        if (mockNote != null) {
            mockNote.setVisibility(configured ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * 服务端压根没配地址时收起手机号那一整块。
     *
     * <p>判据是 {@code isEnabled()}（配置里有没有地址）而**不是** {@code isReachableNow()}
     * —— 后者在熔断冷却、断网时也会返回 false，那会把本来就该露出来的入口藏掉，
     * 用户无从知道自己其实可以直接登录。
     */
    private void refreshPhoneVisibility() {
        boolean hasServer = repository.remote().isEnabled();
        binding.phoneLoginBlock.setVisibility(hasServer ? View.VISIBLE : View.GONE);
        // 「— 或 —」只在上下两条路都在时才有意义
        binding.loginOrDivider.setVisibility(
                hasServer && WeChatAuth.isConfigured(this) ? View.VISIBLE : View.GONE);
    }
}

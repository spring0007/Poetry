package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.example.poetry.data.Callback;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.Member;
import com.example.poetry.data.model.UserAuth;
import com.example.poetry.data.model.UserProfile;
import com.example.poetry.databinding.ActivityProfileBinding;
import com.example.poetry.databinding.ViewSettingsRowBinding;
import com.example.poetry.media.Speaker;
import com.example.poetry.ui.Skin;
import com.example.poetry.util.Chips;
import com.example.poetry.util.LogUtil;
import com.google.android.material.chip.Chip;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 个人信息：账号（登录方式 / 绑定手机 / 会员状态 + 续签退出）、头像、昵称、签名与阅读偏好。
 *
 * <p>昵称 / 签名 / 头像 / 偏好都是**本机**数据，直接写 {@link UserStore}；
 * 「账号」卡片里那些（手机号、注册时间、会员权益）来自**服务端**，
 * 本机只缓存了一份资料快照（见 {@code UserStore.getServerProfile()}）。
 * 两者的区别很实在：前者改完立刻生效，后者只有服务端说了才算数。
 */
public class ProfileActivity extends AppCompatActivity {

    /** 内置头像：十二个意象字，避免引入图片资源 */
    private static final String[] AVATARS = {
            "诗", "词", "墨", "竹", "兰", "菊", "松", "月", "云", "鹤", "山", "雪"
    };

    /** 阅读偏好标签 */
    private static final String[] TASTES = {
            "唐诗", "宋词", "元曲", "楚辞", "山水", "咏物", "边塞", "思乡"
    };

    private ActivityProfileBinding binding;
    private PoetryRepository repository;
    private UserStore store;
    private String avatar = "";

    public static void open(@Nullable Context context) {
        if (context != null) {
            context.startActivity(new Intent(context, ProfileActivity.class));
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = PoetryRepository.get(this);
        store = repository.store();

        binding.backButton.setOnClickListener(v -> finish());
        binding.saveButton.setOnClickListener(v -> save());

        avatar = store.getAvatar();
        binding.nicknameInput.setText(store.getNickname());
        binding.signatureInput.setText(store.getSignature());
        binding.nicknameInput.setHint(R.string.me_nickname);
        if (avatar.isEmpty() && !store.getNickname().isEmpty()) {
            avatar = store.getNickname().substring(0, 1);
        }

        buildAvatars();
        buildTastes();
        bindStats();
        bindAccount();
        updatePreview();
    }

    // ------------------------------------------------------------ 账号

    /**
     * 账号卡片。
     *
     * <p>「有没有服务端账号」与「有没有登录」是两件事：微信登录与本机身份都是
     * {@code isLoggedIn()} 但没有服务端用户，此时 uid / 注册时间 / 会员这些统统没有，
     * 显示「—」而不是编一个。判据统一用 {@code isAuthTokenValid()} ——
     * 它问的是「这次会话背后真的有张服务端凭证吗」。
     */
    private void bindAccount() {
        boolean server = store.isAuthTokenValid();
        UserProfile profile = server ? store.getServerProfile() : null;

        // 第一行：登录方式（本机身份也算一种，说清楚比留空好）
        setInfoRow(binding.rowAccountType, R.string.profile_account_type,
                loginTypeLabel(store.getLoginProvider()));

        // 第二行：绑定手机。本机不存号码（见 UserAuth），要问服务端，先占个位
        setInfoRow(binding.rowAccountPhone, R.string.profile_account_phone,
                server ? getString(R.string.profile_unknown)
                        : getString(store.isLoggedIn()
                                ? R.string.profile_phone_none : R.string.profile_unknown));

        setInfoRow(binding.rowAccountUid, R.string.profile_account_uid,
                profile == null || profile.getUid() <= 0L
                        ? getString(R.string.profile_unknown)
                        : String.valueOf(profile.getUid()));

        setInfoRow(binding.rowAccountRegister, R.string.profile_account_register,
                profile == null || profile.getRegisterTime() <= 0L
                        ? getString(R.string.profile_unknown)
                        : formatDate(profile.getRegisterTime()));

        setInfoRow(binding.rowAccountMember, R.string.profile_account_member, memberLabel());

        // 续签 / 退出：这两个是可点的动作行，保留箭头；上面那些信息行才去掉
        bindActionRow(binding.rowAccountRefresh,
                R.string.profile_account_refresh, R.string.profile_account_refresh_sub);
        bindActionRow(binding.rowAccountLogout,
                R.string.profile_account_logout, R.string.profile_account_logout_sub);
        // 前者要有服务端凭证才有意义，后者只要有登录态就该能点
        binding.rowAccountRefresh.getRoot().setVisibility(server ? View.VISIBLE : View.GONE);
        binding.rowAccountRefresh.getRoot().setOnClickListener(v -> refreshSession());
        binding.rowAccountLogout.getRoot().setVisibility(
                store.isLoggedIn() ? View.VISIBLE : View.GONE);
        binding.rowAccountLogout.getRoot().setOnClickListener(v -> confirmLogout());

        if (server) {
            loadPhone();
        }
    }

    /** 只读信息行：右侧取值，去掉箭头，也不响应点击（点它没有「下一步」）。 */
    private void setInfoRow(@NonNull ViewSettingsRowBinding row, int titleRes,
                            @NonNull CharSequence value) {
        row.rowTitle.setText(titleRes);
        row.rowSub.setText("");
        row.rowValue.setText(value);
        row.rowChevron.setVisibility(View.GONE);
        row.getRoot().setClickable(false);
        row.getRoot().setFocusable(false);
    }

    /** 动作行：标题 + 说明，保留箭头与点击。 */
    private void bindActionRow(@NonNull ViewSettingsRowBinding row, int titleRes, int subRes) {
        row.rowTitle.setText(titleRes);
        row.rowSub.setText(subRes);
        row.rowValue.setText("");
        row.rowChevron.setVisibility(View.VISIBLE);
        row.getRoot().setClickable(true);
        row.getRoot().setFocusable(true);
    }

    @NonNull
    private String loginTypeLabel(@NonNull String provider) {
        switch (provider) {
            case "phone":
                return getString(R.string.profile_login_phone);
            case "wechat":
                return getString(R.string.profile_login_wechat);
            case "qq":
                return getString(R.string.profile_login_qq);
            default:
                return getString(store.isLoggedIn()
                        ? R.string.profile_login_local : R.string.me_not_logged_in);
        }
    }

    /** 会员状态。口径与语音设置页、「我的」页一致，都读服务端下发的 {@link Member}。 */
    @NonNull
    private CharSequence memberLabel() {
        if (!store.isLoggedIn()) {
            return getString(R.string.me_not_logged_in);
        }
        Member member = store.getMember();
        if (!member.isActive()) {
            return getString(R.string.me_member_free);
        }
        return member.getExpireAt() > 0L
                ? getString(R.string.tts_member_expire, formatDate(member.getExpireAt()))
                : getString(R.string.tts_member_lifetime);
    }

    /** 脱敏手机号异步取回来填第二行。取不到就保持「—」，不弹错。 */
    private void loadPhone() {
        repository.auths(new Callback<List<UserAuth>>() {
            @Override
            public void onData(@NonNull List<UserAuth> data) {
                UserAuth phone = UserAuth.phoneOf(data);
                binding.rowAccountPhone.rowValue.setText(phone == null
                        ? getString(R.string.profile_phone_none)
                        : phone.maskedIdentifier());
            }

            @Override
            public void onError(@Nullable Throwable error) {
                // 这里显示的只是信息：拿不到就留「—」，不为它打断用户
                LogUtil.d("取账号绑定失败（手机号留空）：" + error);
            }
        });
    }

    /**
     * 「延长登录」。换到新票才算成功；{@code false} 说明旧票已经换不动了，
     * 这时候唯一的出路是重新登录。
     */
    private void refreshSession() {
        repository.refreshSession(new Callback<Boolean>() {
            @Override
            public void onData(@NonNull Boolean ok) {
                Toast.makeText(ProfileActivity.this, ok
                        ? R.string.profile_refresh_ok : R.string.profile_refresh_fail,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(@Nullable Throwable error) {
                Toast.makeText(ProfileActivity.this, R.string.profile_refresh_fail,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 「退出登录」。二次确认是必须的：这会清掉本机凭证，虽然后台还能重新登录拿回来，
     * 但用户点错一下就得再收一次验证码。
     */
    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.profile_logout_title)
                .setMessage(R.string.profile_logout_msg)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    store.logout();
                    // 音色目录的锁定态是服务端按请求里那张票现算的，票刚被清掉，
                    // 手里这份就是过期的（云端音色还挂着解锁）。重取一次，让权益当场回收；
                    // 离线音色与它无关。引擎没被 prepare 过时这次调用什么也不做，
                    // 重复调用也是安全的（见 Speaker#refreshVoices）。
                    Speaker.get().refreshVoices();
                    Toast.makeText(this, R.string.profile_logout_done, Toast.LENGTH_SHORT).show();
                    // 资料页整个是围绕账号组织的，退出之后没有内容可留
                    finish();
                })
                .show();
    }

    /** 到期 / 注册时间都显示到日：这里要的是「什么时候」，不是精确到秒。 */
    @NonNull
    private static String formatDate(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(millis));
    }

    private void buildAvatars() {
        binding.avatarGrid.removeAllViews();
        int size = getResources().getDimensionPixelSize(R.dimen.size_author_avatar);
        int gap = getResources().getDimensionPixelSize(R.dimen.space_2);
        boolean known = Arrays.asList(AVATARS).contains(avatar);
        String shown = known ? avatar : (avatar.isEmpty() ? "诗" : avatar);

        for (String item : AVATARS) {
            TextView cell = new TextView(this);
            cell.setLayoutParams(new GridLayout.LayoutParams());
            cell.setWidth(size);
            cell.setHeight(size);
            cell.setBackgroundResource(R.drawable.bg_circle);
            cell.setGravity(Gravity.CENTER);
            cell.setText(item);
            cell.setTextAppearance(R.style.TextAppearance_Poetry_CardTitle);
            boolean selected = item.equals(shown);
            ViewCompat.setBackgroundTintList(cell, ColorStateList.valueOf(
                    ContextCompat.getColor(this, selected ? R.color.tenghuang_100
                            : R.color.surface_2)));
            cell.setOnClickListener(v -> {
                avatar = item;
                updatePreview();
                buildAvatars();
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(gap, gap, gap, gap);
            binding.avatarGrid.addView(cell, lp);
        }
    }

    private void buildTastes() {
        List<String> saved = store.getTasteList();
        binding.tasteChips.removeAllViews();
        for (String label : TASTES) {
            Chip chip = Chips.create(this, label, false);
            chip.setChecked(saved.contains(label));
            chip.setOnCheckedChangeListener((button, checked) -> {
                // 选择变化即时记录，返回后「我的」页也能看到
                List<String> next = new ArrayList<>();
                for (int i = 0; i < binding.tasteChips.getChildCount(); i++) {
                    Chip child = (Chip) binding.tasteChips.getChildAt(i);
                    if (child.isChecked()) {
                        next.add(child.getText().toString());
                    }
                }
                store.setTasteList(next);
            });
            binding.tasteChips.addView(chip);
        }
    }

    private void bindStats() {
        binding.statPlays.setText(getString(R.string.profile_stat_plays, store.getPlayCount()));
        binding.statFavorites.setText(getString(R.string.profile_stat_favorites,
                repository.favorites().size()));
        binding.statCheckin.setText(getString(R.string.profile_stat_checkin,
                store.getCheckinTotal(), store.getCheckinStreak()));
    }

    private void updatePreview() {
        String shown = avatar.isEmpty() ? "诗" : avatar;
        binding.avatarPreview.setText(shown);
        ViewCompat.setBackgroundTintList(binding.avatarPreview, ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.tenghuang_100)));
    }

    private void save() {
        String nickname = binding.nicknameInput.getText() == null
                ? "" : binding.nicknameInput.getText().toString().trim();
        String signature = binding.signatureInput.getText() == null
                ? "" : binding.signatureInput.getText().toString().trim();
        store.setNickname(nickname);
        store.setSignature(signature);
        store.setAvatar(avatar);
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}

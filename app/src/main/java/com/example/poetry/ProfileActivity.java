package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.databinding.ActivityProfileBinding;
import com.example.poetry.ui.Skin;
import com.example.poetry.util.Chips;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 个人信息：头像、昵称、签名与阅读偏好。
 *
 * 这些只用于本机展示（App 没有账号体系），所以直接写 {@link UserStore}，
 * 返回「我的」页时会重新读取并刷新。
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
        updatePreview();
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

    @NonNull
    private String shownNickname() {
        String nickname = store.getNickname();
        return nickname.isEmpty() ? getString(R.string.me_nickname) : nickname;
    }
}

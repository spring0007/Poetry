package com.example.poetry.fragment;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.example.poetry.R;
import com.example.poetry.VoiceSettingsActivity;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.Voice;
import com.example.poetry.databinding.FragmentMineBinding;
import com.example.poetry.media.Speaker;
import com.example.poetry.ui.VoiceSheet;

/**
 * 我的页：资料卡 + 统计 + 打卡 + 朗读偏好 + 成就 + 更多 + 数据状态。
 */
public class MineFragment extends Fragment {

    private static final int TOTAL_CHECKIN = 7;
    private static final int TOTAL_BADGES = 6;

    private FragmentMineBinding binding;
    private PoetryRepository repository;
    private UserStore store;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMineBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        repository = PoetryRepository.get(requireContext());
        store = repository.store();

        setupProfile();
        setupStats();
        setupPreferences();
        setupBadges();
        setupMore();
        setupData();
    }

    // ---------------------------------------------------------------- 资料

    private void setupProfile() {
        binding.profileName.setText(R.string.me_nickname);
        binding.profileAvatar.setText(getString(R.string.me_nickname).substring(0, 1));
        binding.profileId.setText(R.string.me_id);
        binding.profileLevel.setText(R.string.me_level);
        binding.profileEdit.setOnClickListener(v ->
                Toast.makeText(requireContext(), R.string.toast_edit_profile, Toast.LENGTH_SHORT).show());
    }

    private void setupStats() {
        int favorites = repository.favorites().size();
        int days = store.getCheckinDays();
        binding.statFav.setText(String.valueOf(favorites));
        binding.statHours.setText(String.format(java.util.Locale.CHINA, "%.1f", store.getReadHours()));
        binding.statDays.setText(String.valueOf(days));

        binding.checkinDots.removeAllViews();
        int dotSize = getResources().getDimensionPixelSize(R.dimen.size_checkin_dot);
        int gap = getResources().getDimensionPixelSize(R.dimen.space_2);
        for (int i = 0; i < TOTAL_CHECKIN; i++) {
            TextView dot = new TextView(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dotSize, dotSize);
            lp.weight = 1f;
            lp.gravity = Gravity.CENTER;
            if (i > 0) {
                lp.leftMargin = gap;
            }
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.bg_circle);
            dot.setGravity(Gravity.CENTER);
            dot.setText(String.valueOf(i + 1));
            dot.setTextAppearance(R.style.TextAppearance_Poetry_Caption);
            boolean checked = i < days;
            ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(),
                            checked ? R.color.color_primary : R.color.surface_3)));
            dot.setTextColor(ContextCompat.getColor(requireContext(),
                    checked ? R.color.on_primary : R.color.ink_400));
            binding.checkinDots.addView(dot);
        }
        binding.checkinMeta.setText(getString(R.string.me_checkin_meta, days, TOTAL_CHECKIN));
    }

    // ---------------------------------------------------------------- 朗读偏好

    private void setupPreferences() {
        binding.prefHeader.sectionTitle.setText(R.string.me_group_pref);
        binding.prefHeader.sectionMeta.setText(R.string.me_group_pref_meta);

        // 默认音色
        binding.rowVoice.rowTitle.setText(R.string.pref_voice);
        binding.rowVoice.rowSub.setText(R.string.pref_voice_sub);
        binding.rowVoice.rowValue.setText(currentVoiceName());
        binding.rowVoice.getRoot().setOnClickListener(v -> VoiceSheet.show(requireContext(),
                new VoiceSheet.Callback() {
                    @Override
                    public void onSelected(@NonNull Voice voice) {
                        binding.rowVoice.rowValue.setText(voice.getName());
                    }

                    @Override
                    public void onPreview(@NonNull Voice voice) {
                        Speaker.get().speak(getString(R.string.tts_preview_text));
                    }
                }));

        // 语音设置（发音人 / 语速 / 音调 / 音量 / 引擎）
        binding.rowSpeech.rowTitle.setText(R.string.pref_speech);
        binding.rowSpeech.rowSub.setText(R.string.pref_speech_sub);
        binding.rowSpeech.rowValue.setText(speechSummary());
        binding.rowSpeech.getRoot().setOnClickListener(v ->
                VoiceSettingsActivity.open(requireContext()));

        // 语速
        float rate = store.getTtsConfig().getRate();
        binding.rateSlider.setValue(rate);
        Speaker.get().setRate(rate);
        binding.rateSlider.addOnChangeListener((slider, value, fromUser) -> {
            store.updateTtsConfig(config -> config.setRate(value));
            Speaker.get().setRate(value);
        });

        // 字号
        updateFontButtons();
        binding.fontSmall.setOnClickListener(v -> applyFont(0.9f, getString(R.string.font_small)));
        binding.fontNormal.setOnClickListener(v -> applyFont(1.0f, getString(R.string.font_normal)));
        binding.fontLarge.setOnClickListener(v -> applyFont(1.15f, getString(R.string.font_large)));

        // 竖排阅读
        binding.rowVertical.switchTitle.setText(R.string.pref_vertical);
        binding.rowVertical.switchSub.setText(R.string.pref_vertical_sub);
        binding.rowVertical.switchControl.setChecked(store.isVerticalReading());
        binding.rowVertical.switchControl.setOnCheckedChangeListener(
                (buttonView, isChecked) -> store.setVerticalReading(isChecked));

        // 夜间模式
        binding.rowDark.switchTitle.setText(R.string.pref_dark);
        binding.rowDark.switchSub.setText(R.string.pref_dark_sub);
        binding.rowDark.switchControl.setChecked(
                store.getNightMode() == UserStore.NIGHT_ON);
        binding.rowDark.switchControl.setOnCheckedChangeListener((buttonView, isChecked) -> {
            store.setNightMode(isChecked ? UserStore.NIGHT_ON : UserStore.NIGHT_OFF);
            AppCompatDelegate.setDefaultNightMode(isChecked
                    ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_NO);
        });
    }

    /** 「语音设置」行摘要：发音人 + 语速 */
    @NonNull
    private String speechSummary() {
        TtsConfig config = store.getTtsConfig();
        return String.format(java.util.Locale.CHINA, "%s · %.1f×",
                currentVoiceName(), config.getRate());
    }

    @NonNull
    private String currentVoiceName() {
        String saved = store.getTtsConfig().getVoiceId();
        if (saved.isEmpty()) {
            return getString(R.string.voice_default_name);
        }
        for (Voice voice : Speaker.get().listVoices()) {
            if (voice.getId().equals(saved)) {
                return voice.getName();
            }
        }
        return saved;
    }

    private void applyFont(float scale, @NonNull String label) {
        store.setFontScale(scale);
        updateFontButtons();
        Toast.makeText(requireContext(), getString(R.string.toast_font_set, label),
                Toast.LENGTH_SHORT).show();
    }

    private void updateFontButtons() {
        float scale = store.getFontScale();
        binding.fontSmall.setSelected(scale < 0.95f);
        binding.fontNormal.setSelected(scale >= 0.95f && scale <= 1.05f);
        binding.fontLarge.setSelected(scale > 1.05f);
        binding.fontSmall.setAlpha(binding.fontSmall.isSelected() ? 1f : 0.55f);
        binding.fontNormal.setAlpha(binding.fontNormal.isSelected() ? 1f : 0.55f);
        binding.fontLarge.setAlpha(binding.fontLarge.isSelected() ? 1f : 0.55f);
    }

    // ---------------------------------------------------------------- 成就

    private void setupBadges() {
        int favorites = repository.favorites().size();
        int unlocked = 2 + (favorites >= 5 ? 1 : 0) + (store.getPlayCount() >= 10 ? 1 : 0);
        binding.badgeHeader.sectionTitle.setText(R.string.me_group_badges);
        binding.badgeHeader.sectionMeta.setText(
                getString(R.string.me_badges_meta, unlocked, TOTAL_BADGES));

        binding.badgeRow.removeAllViews();
        addBadge("🌙", "月下独酌", true);
        addBadge("🍶", "对酒当歌", store.getPlayCount() >= 10);
        addBadge("📚", "藏书五车", favorites >= 5);
    }

    private void addBadge(@NonNull String icon, @NonNull String name, boolean unlocked) {
        LinearLayout item = new LinearLayout(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        item.setLayoutParams(lp);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView iconView = new TextView(requireContext());
        int size = getResources().getDimensionPixelSize(R.dimen.size_badge_icon);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(size, size);
        iconView.setLayoutParams(iconLp);
        iconView.setBackgroundResource(R.drawable.bg_circle);
        iconView.setGravity(Gravity.CENTER);
        iconView.setText(icon);
        iconView.setTextSize(18f);
        ViewCompat.setBackgroundTintList(iconView, ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(),
                        unlocked ? R.color.tenghuang_100 : R.color.surface_3)));
        iconView.setAlpha(unlocked ? 1f : 0.45f);

        TextView nameView = new TextView(requireContext());
        nameView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        nameView.setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_2), 0, 0);
        nameView.setText(name);
        nameView.setTextAppearance(R.style.TextAppearance_Poetry_Caption);
        nameView.setTextColor(ContextCompat.getColor(requireContext(),
                unlocked ? R.color.ink_700 : R.color.ink_400));

        item.addView(iconView);
        item.addView(nameView);
        binding.badgeRow.addView(item);
    }

    // ---------------------------------------------------------------- 更多

    private void setupMore() {
        binding.moreHeader.sectionTitle.setText(R.string.me_group_more);
        binding.moreHeader.sectionMeta.setText("");

        bindRow(binding.rowHistory, R.string.more_history, R.string.more_history_sub);
        bindRow(binding.rowDownload, R.string.more_download, R.string.more_download_sub);
        bindRow(binding.rowSkin, R.string.more_skin, R.string.more_skin_sub);
        bindRow(binding.rowAbout, R.string.more_about, R.string.more_about_sub);

        View.OnClickListener pending = v -> Toast.makeText(requireContext(),
                getString(R.string.toast_not_ready, ""), Toast.LENGTH_SHORT).show();
        binding.rowHistory.getRoot().setOnClickListener(pending);
        binding.rowDownload.getRoot().setOnClickListener(pending);
        binding.rowSkin.getRoot().setOnClickListener(pending);
        binding.rowAbout.getRoot().setOnClickListener(pending);
    }

    private void bindRow(@NonNull com.example.poetry.databinding.ViewSettingsRowBinding row,
                         int titleRes, int subRes) {
        row.rowTitle.setText(titleRes);
        row.rowSub.setText(subRes);
        row.rowValue.setText("");
    }

    // ---------------------------------------------------------------- 数据状态

    private void setupData() {
        binding.dataHeader.sectionTitle.setText(R.string.mine_data_group);
        binding.dataHeader.sectionMeta.setText("");

        boolean ready = repository.isLocalReady();
        binding.rowDataSource.rowTitle.setText(R.string.mine_data_source);
        binding.rowDataSource.rowValue.setText(ready
                ? R.string.mine_data_ready : R.string.mine_data_seed);
        binding.rowDataSource.rowSub.setText(getString(R.string.data_source_label,
                repository.sourceLabel()));
        binding.rowDataSource.getRoot().setOnClickListener(v -> Toast.makeText(requireContext(),
                getString(R.string.data_db_hint, repository.recommendDbDir()),
                Toast.LENGTH_LONG).show());

        binding.dbHint.setText(getString(R.string.data_db_hint, repository.recommendDbDir()));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}

package com.example.poetry.fragment;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.example.poetry.AboutActivity;
import com.example.poetry.HistoryActivity;
import com.example.poetry.LoginActivity;
import com.example.poetry.ProfileActivity;
import com.example.poetry.SkinActivity;
import com.example.poetry.StatsActivity;
import com.example.poetry.R;
import com.example.poetry.VoiceSettingsActivity;
import com.example.poetry.data.DbStatus;
import com.example.poetry.data.DbStatusListener;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.Member;
import com.example.poetry.data.model.TtsConfig;
import com.example.poetry.data.model.UserProfile;
import com.example.poetry.data.model.Voice;
import com.example.poetry.databinding.FragmentMineBinding;
import com.example.poetry.ui.Night;
import com.example.poetry.ui.Skin;
import com.example.poetry.media.Speaker;
import com.example.poetry.ui.VoiceSheet;
import com.example.poetry.util.Sliders;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 我的页：资料卡 + 统计 + 打卡 + 朗读偏好 + 成就 + 更多 + 数据状态。
 */
public class MineFragment extends Fragment {

    private static final int TOTAL_CHECKIN = 7;

    private FragmentMineBinding binding;
    private PoetryRepository repository;
    private UserStore store;

    /**
     * 诗库状态订阅者，**每个视图一份**，在 {@code onViewCreated} 里新建。
     *
     * <p>用的是**裸的** {@link DbStatusListener} 而不是 {@link DbStatusListener.ReadyWatcher}：
     * 这一行要显示的就是整个状态机（检查中 / 下载中 n% / 校验中 / 失败原因），而 ReadyWatcher
     * 只在 {@code localReady} 翻面时才回调——下载进度根本不会动。
     *
     * <p>每个事件都重渲染是划算的：这里只是几次 {@code setText}。真正贵的是重新查询列表，
     * 那件事在四个列表页里，那些界面用的才是 ReadyWatcher。
     */
    private DbStatusListener statusWatcher;

    /**
     * 最近一次渲染的状态，只为点击时判分支。**不参与显示**——显示的永远是最新事件。
     */
    @Nullable
    private DbStatus lastStatus;

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
        setupCheckin();
        setupPreferences();
        setupBadges();
        setupMore();
        bindData();

        // 注册会立刻回调一次当前状态，「诗库状态」行的首次取值就由这次回调完成。
        // 注册点必须是 onViewCreated 而不是 bindData()：后者虽然目前也只在
        // onViewCreated 里跑，但名字上它是「渲染」，以后一旦挪进 onResume 就会漏监听器。
        statusWatcher = this::renderDbStatus;
        repository.addDbStatusListener(statusWatcher);
    }

    // ---------------------------------------------------------------- 资料

    private void setupProfile() {
        // 未登录：资料卡整体变成登录入口；登录后展示资料并照旧跳个人信息页
        if (!store.isLoggedIn()) {
            binding.profileName.setText(R.string.me_not_logged_in);
            binding.profileAvatar.setText(R.string.login_seal_char);
            binding.profileId.setText(R.string.me_login_hint);
            // 没登录就没有会员状态可讲，整枚标签收起来；空标签会留下一块没字的底色
            binding.profileLevel.setText("");
            binding.profileLevel.setVisibility(View.GONE);
            View.OnClickListener login = v -> LoginActivity.open(requireContext());
            binding.profileEdit.setOnClickListener(login);
            binding.profileAvatar.setOnClickListener(login);
            return;
        }

        String nickname = store.getNickname();
        String shown = nickname.isEmpty() ? getString(R.string.me_nickname) : nickname;
        binding.profileName.setText(shown);

        String avatar = store.getAvatar();
        if (avatar.isEmpty()) {
            avatar = shown.substring(0, 1);
        }
        binding.profileAvatar.setText(avatar);
        binding.profileId.setText(profileIdLine());
        binding.profileLevel.setVisibility(View.VISIBLE);
        renderMember();

        View.OnClickListener edit = v -> ProfileActivity.open(requireContext());
        binding.profileEdit.setOnClickListener(edit);
        binding.profileAvatar.setOnClickListener(edit);
    }

    /**
     * 资料卡第二行。
     *
     * <p>只有**背后真有服务端身份**（{@code isAuthTokenValid()}）时才报 uid ——
     * 微信 / 本机身份登录没有服务端账号，写一个假 ID 出来比不写更糟。
     * 没有资料时退化成「本机身份 · 未绑定手机号」，顺带告诉用户绑定入口在哪儿。
     */
    @NonNull
    private CharSequence profileIdLine() {
        UserProfile profile = store.isAuthTokenValid() ? store.getServerProfile() : null;
        if (profile == null || profile.getUid() <= 0L) {
            return getString(R.string.me_id_local);
        }
        long days = joinedDays(profile.getRegisterTime());
        return days > 0L
                ? getString(R.string.me_id_joined, profile.getUid(), days)
                : getString(R.string.me_id_only, profile.getUid());
    }

    /**
     * 注册到今天的天数；服务端没给注册时间（老账号）时返回 0，界面退化成只显示 ID。
     * 当天注册算第 1 天 —— 显示「加入 0 天」不像人话。
     */
    private static long joinedDays(long registerTime) {
        if (registerTime <= 0L) {
            return 0L;
        }
        long elapsed = (System.currentTimeMillis() - registerTime) / 86_400_000L;
        return Math.max(1L, elapsed);
    }

    /**
     * 资料卡第三行：会员状态。口径与语音设置页一致 —— 都读服务端下发、
     * {@code UserStore} 缓存的 {@link Member}，本地不再编造等级。
     * 原先这里显示的是签名，位置被占掉之后「是不是会员」在「我的」页反而看不见。
     *
     * <p>配色跟着状态走。这一格原来借的是 Hero 卡上的 {@code bg_hero_tag}
     * ——那是个半透明白底、配白字的标签，铺在深色 Hero 图上正好，铺在浅色卡面上
     * 等于看不见（实测白字白底糊成一团）。所以换成可着色的 {@code bg_badge}，
     * 非会员是素色、会员是金色实底，只靠底色的冷暖就能分辨。
     */
    private void renderMember() {
        Member member = store.getMember();
        boolean active = member.isActive();
        binding.profileLevel.setText(memberLine(member));
        int tint = ContextCompat.getColor(requireContext(),
                active ? R.color.tenghuang_500 : R.color.surface_3);
        ViewCompat.setBackgroundTintList(binding.profileLevel,
                ColorStateList.valueOf(tint));
    }

    /** 会员状态的三种写法，与语音设置页共用同一批字符串。 */
    @NonNull
    private CharSequence memberLine(@NonNull Member member) {
        if (!member.isActive()) {
            return getString(R.string.me_member_free);
        }
        return member.getExpireAt() > 0L
                ? getString(R.string.tts_member_expire, formatDate(member.getExpireAt()))
                : getString(R.string.tts_member_lifetime);
    }

    /** 到期时间显示到日：用户只关心「还能用到哪天」。 */
    @NonNull
    private static String formatDate(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(millis));
    }

    private void setupStats() {
        int favorites = repository.favorites().size();
        binding.statFav.setText(String.valueOf(favorites));
        binding.statHours.setText(String.format(Locale.CHINA, "%.1f",
                store.getReadHours()));
        binding.statDays.setText(String.valueOf(store.getCheckinStreak()));
    }

    /**
     * 本周打卡：周一到周日七个点，按真实日期点亮；今日未打卡时按钮可点。
     */
    private void setupCheckin() {
        List<Boolean> week = store.getWeekCheckins();
        String[] weekdays = getResources().getStringArray(R.array.weekday_labels);
        boolean today = store.isCheckedInToday();
        int streak = store.getCheckinStreak();

        binding.checkinStreak.setText(getString(R.string.me_checkin_streak, streak));
        binding.checkinDots.removeAllViews();
        int dotSize = getResources().getDimensionPixelSize(R.dimen.size_checkin_dot);
        int gap = getResources().getDimensionPixelSize(R.dimen.space_2);
        int done = 0;
        for (int i = 0; i < TOTAL_CHECKIN; i++) {
            boolean checked = i < week.size() && week.get(i);
            if (checked) {
                done++;
            }
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
            dot.setText(weekdays[i]);
            dot.setTextAppearance(R.style.TextAppearance_Poetry_Caption);
            ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(),
                            checked ? R.color.color_primary : R.color.surface_3)));
            dot.setTextColor(ContextCompat.getColor(requireContext(),
                    checked ? R.color.on_primary : R.color.ink_400));
            binding.checkinDots.addView(dot);
        }
        binding.checkinMeta.setText(getString(R.string.me_checkin_meta, done, TOTAL_CHECKIN));

        binding.checkinButton.setText(today ? R.string.me_checkin_done
                : R.string.me_checkin_today);
        binding.checkinButton.setEnabled(!today);
        binding.checkinButton.setAlpha(today ? 0.6f : 1f);
        binding.checkinButton.setOnClickListener(v -> {
            if (store.checkIn()) {
                Toast.makeText(requireContext(),
                        getString(R.string.me_checkin_toast, store.getCheckinStreak()),
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), R.string.me_checkin_repeat,
                        Toast.LENGTH_SHORT).show();
            }
            refreshStats();
        });
    }

    /** 打卡 / 收藏变化后统一刷新统计区 */
    private void refreshStats() {
        setupStats();
        setupCheckin();
        setupBadges();
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
        TtsConfig tts = store.getTtsConfig();
        float rate = Sliders.snap(binding.rateSlider, tts.getRate());
        if (rate != tts.getRate()) {
            // 存下来的值可能不在滑杆的步进栅格上（0.92727274 就是从这儿来的）：交给
            // setValue 会在首次布局时抛 IllegalStateException。就地收敛并写回，
            // 坏值只修一次，之后界面、prefs、引擎三边都一致
            store.updateTtsConfig(config -> config.setRate(rate));
        }
        binding.rateSlider.setValue(rate);
        Speaker.get().setRate(rate);
        binding.rateSlider.addOnChangeListener((slider, value, fromUser) -> {
            // 拖出来的值也要收：Material 的拖拽栅格是坏的（见 Sliders 的类注释），
            // 偏值留在滑杆里，这个页面下一次尺寸变化就会崩
            float current = Sliders.snapAndPushBack(slider, value);
            store.updateTtsConfig(config -> config.setRate(current));
            Speaker.get().setRate(current);
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
            // 写偏好 + 设默认模式 + 必要时重建当前页，三步都收在 Night 里：
            // 只调 setDefaultNightMode 时，当前页未必会重建，表现就是「拨了开关界面不变」
            Night.setEnabled(getActivity(), store, isChecked);
            Toast.makeText(requireContext(), isChecked
                    ? R.string.night_on_msg : R.string.night_off_msg, Toast.LENGTH_SHORT).show();
        });

        // 自动朗读
        binding.rowAutoPlay.switchTitle.setText(R.string.pref_auto_play);
        binding.rowAutoPlay.switchSub.setText(R.string.pref_auto_play_sub);
        binding.rowAutoPlay.switchControl.setChecked(store.isAutoPlay());
        binding.rowAutoPlay.switchControl.setOnCheckedChangeListener(
                (buttonView, isChecked) -> store.setAutoPlay(isChecked));

        // 连续朗读
        binding.rowContinuous.switchTitle.setText(R.string.pref_continuous);
        binding.rowContinuous.switchSub.setText(R.string.pref_continuous_sub);
        binding.rowContinuous.switchControl.setChecked(store.isContinuousPlay());
        binding.rowContinuous.switchControl.setOnCheckedChangeListener(
                (buttonView, isChecked) -> store.setContinuousPlay(isChecked));

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
        // 引擎归一之后的 id 才是实际会发声的那个：存着的老 id（例如旧版留下的
        // sherpa-xiao-ya）和这次没通过核对的音色都会被它落到默认发音人上
        String saved = Speaker.get().currentVoiceId();
        if (saved == null || saved.isEmpty()) {
            saved = store.getTtsConfig().getVoiceId();
        }
        for (Voice voice : Speaker.get().listVoices()) {
            if (voice.getId().equals(saved)) {
                return voice.getName();
            }
        }
        // 找不到就别把内部 id 当名字显示出来
        return getString(R.string.voice_default_name);
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
        List<Badge> badges = loadBadges();
        int unlocked = 0;
        for (Badge badge : badges) {
            if (badge.unlocked) {
                unlocked++;
            }
        }
        binding.badgeHeader.sectionTitle.setText(R.string.me_group_badges);
        binding.badgeHeader.sectionMeta.setText(
                getString(R.string.me_badges_meta, unlocked, badges.size()));

        binding.badgeRow.removeAllViews();
        for (Badge badge : badges) {
            addBadge(badge);
        }
    }

    /**
     * 徽章清单。图标直接写在代码里（给一个 emoji 建资源没意义），名称与条件走字符串资源；
     * 解锁与否**全部由真实数据推导**。
     *
     * <p>原先这一屏写死「已解锁 2 个」并且只画三个，于是无论读多少首都停在 2/6 ——
     * 成就系统不反映任何东西。现在每个条件都能在界面上找到出处：
     * 次数在「更多 · 数据统计」里，收藏在书架，打卡在同一张卡的第一行。
     */
    @NonNull
    private List<Badge> loadBadges() {
        int plays = store.getPlayCount();
        int favorites = repository.favorites().size();
        List<Badge> badges = new ArrayList<>();
        badges.add(new Badge("🌙", R.string.badge_moon, R.string.badge_moon_cond, plays >= 1));
        badges.add(new Badge("🍶", R.string.badge_wine, R.string.badge_wine_cond, plays >= 50));
        badges.add(new Badge("📖", R.string.badge_book, R.string.badge_book_cond,
                store.getReadHours() >= 1f));
        badges.add(new Badge("📚", R.string.badge_stack, R.string.badge_stack_cond,
                favorites >= 5));
        badges.add(new Badge("🔥", R.string.badge_streak, R.string.badge_streak_cond,
                store.getCheckinStreak() >= 7));
        badges.add(new Badge("🗓️", R.string.badge_days, R.string.badge_days_cond,
                store.getActiveDays() >= 30));
        return badges;
    }

    /** 点一下说明还差什么 —— 锁着的徽章没有别的信息可给。 */
    private void addBadge(@NonNull Badge badge) {
        Context context = requireContext();
        LinearLayout item = new LinearLayout(context);
        // 列宽按权重均分（三列两行，见 fragment_mine.xml 的 columnCount）。
        // 不用固定宽度：格子数以后变了也不用改这里。
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.bottomMargin = getResources().getDimensionPixelSize(R.dimen.space_3);
        item.setLayoutParams(lp);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView iconView = new TextView(context);
        int size = getResources().getDimensionPixelSize(R.dimen.size_badge_icon);
        iconView.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        iconView.setBackgroundResource(R.drawable.bg_circle);
        iconView.setGravity(Gravity.CENTER);
        iconView.setText(badge.icon);
        iconView.setTextSize(18f);
        ViewCompat.setBackgroundTintList(iconView, ColorStateList.valueOf(
                ContextCompat.getColor(context,
                        badge.unlocked ? R.color.tenghuang_100 : R.color.surface_3)));
        iconView.setAlpha(badge.unlocked ? 1f : 0.45f);

        TextView nameView = new TextView(context);
        nameView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        nameView.setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_2), 0, 0);
        nameView.setText(badge.nameRes);
        nameView.setTextAppearance(R.style.TextAppearance_Poetry_Caption);
        nameView.setTextColor(ContextCompat.getColor(context,
                badge.unlocked ? R.color.ink_700 : R.color.ink_400));

        item.addView(iconView);
        item.addView(nameView);
        item.setOnClickListener(v -> Toast.makeText(context,
                badge.unlocked
                        ? getString(R.string.badge_toast_done, getString(badge.nameRes))
                        : getString(R.string.badge_toast_todo,
                                getString(badge.nameRes), getString(badge.condRes)),
                Toast.LENGTH_SHORT).show());
        binding.badgeRow.addView(item);
    }

    /** 一个成就徽章。{@code unlocked} 由真实数据算出来，不写死。 */
    private static final class Badge {
        @NonNull
        final String icon;
        @StringRes
        final int nameRes;
        @StringRes
        final int condRes;
        final boolean unlocked;

        Badge(@NonNull String icon, @StringRes int nameRes, @StringRes int condRes,
              boolean unlocked) {
            this.icon = icon;
            this.nameRes = nameRes;
            this.condRes = condRes;
            this.unlocked = unlocked;
        }
    }

    // ---------------------------------------------------------------- 更多

    private void setupMore() {
        binding.moreHeader.sectionTitle.setText(R.string.me_group_more);
        binding.moreHeader.sectionMeta.setText("");

        bindRow(binding.rowHistory, R.string.more_history, R.string.more_history_sub);
        bindRow(binding.rowStats, R.string.stats_title, R.string.stats_sub);
        bindRow(binding.rowSkin, R.string.more_skin, R.string.more_skin_sub);
        bindRow(binding.rowAbout, R.string.more_about, R.string.more_about_sub);

        binding.rowHistory.rowValue.setText(getString(R.string.more_history_count,
                repository.history().size()));
        binding.rowStats.rowValue.setText(getString(R.string.stats_value,
                store.getActiveDays(), store.getPlayCount(),
                repository.favorites().size()));
        binding.rowSkin.rowValue.setText(Skin.nameOf(store.getSkinId()));
        binding.rowAbout.rowValue.setText(R.string.more_about_sub);

        binding.rowHistory.getRoot().setOnClickListener(v ->
                HistoryActivity.open(requireContext()));
        binding.rowStats.getRoot().setOnClickListener(v ->
                StatsActivity.open(requireContext()));
        binding.rowSkin.getRoot().setOnClickListener(v ->
                SkinActivity.open(requireContext()));
        binding.rowAbout.getRoot().setOnClickListener(v ->
                AboutActivity.open(requireContext()));
    }

    private void bindRow(@NonNull com.example.poetry.databinding.ViewSettingsRowBinding row,
                         int titleRes, int subRes) {
        row.rowTitle.setText(titleRes);
        row.rowSub.setText(subRes);
        row.rowValue.setText("");
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从资料页 / 历史页返回后，昵称、收藏数与打卡状态都可能变了
        if (binding != null) {
            setupProfile();
            refreshStats();
            setupMore();
            // 切网络、熔断冷却结束都会改「服务端」那一行
            renderApiStatus();
        }
    }

    // ---------------------------------------------------------------- 数据状态

    /** 只搭架子：分组标题、行标题、点击事件。渲染归 {@link #renderDbStatus}。 */
    private void bindData() {
        binding.dataHeader.sectionTitle.setText(R.string.mine_data_group);
        binding.dataHeader.sectionMeta.setText("");

        binding.rowDataSource.rowTitle.setText(R.string.mine_data_source);
        binding.rowDataSource.getRoot().setOnClickListener(v -> onDataRowClick());
        binding.dbHint.setText(getString(R.string.data_db_hint, repository.recommendDbDir()));

        // 服务端行只是说明：点它没有「下一步」可做，所以去掉点击与箭头，
        // 否则它会长得跟「诗库状态」一样，让人以为点进去还有东西。
        binding.rowApi.getRoot().setClickable(false);
        binding.rowApi.getRoot().setFocusable(false);
        renderApiStatus();
    }

    /**
     * 渲染「服务端」行。
     *
     * <p>与「诗库状态」分成两行是因为它们回答不同的问题：内容这次是从哪儿来的
     * （可能回退了本地），以及后端链路本身通不通（熔断、未配地址、无网络）。
     * 合成一行会让「正在用本地库」和「后端挂了」看起来像同一件事。
     *
     * <p>只在 {@code bindData} 与 {@code onResume} 里刷新，不订阅任何事件：
     * 链路状态不是逐秒在变的，为它建一套推送得不偿失。
     */
    private void renderApiStatus() {
        if (binding == null) {
            return;
        }
        binding.rowApi.rowTitle.setText(R.string.mine_api_status);
        binding.rowApi.rowSub.setText(repository.apiStatusLabel());
        binding.rowApi.rowValue.setText("");
        binding.rowApi.rowChevron.setVisibility(View.GONE);
    }

    /**
     * 点「诗库状态」行。动作完全由当前状态决定——这行既是展示也是唯一的入口，
     * 所以本轮没有单独的开始/取消按钮。
     */
    private void onDataRowClick() {
        DbStatus status = lastStatus;
        if (status == null) {
            // 还没收到过任何事件（理论上不会：注册时必回调一次），退回问仓库要快照
            status = repository.dbStatus();
        }
        switch (status.state) {
            case DOWNLOADING:
                repository.cancelDbDownload();
                break;
            case CHECKING:
            case VERIFYING:
            case INSTALLING:
                // 正忙，点它没有意义。**不是**禁用整行：disabled 会连带灰掉文字，
                // 而这里的文字正是进度本身。
                break;
            case IDLE:
            case INSTALLED:
                if (status.localReady) {
                    repository.checkForDbUpdate(true, null);
                } else {
                    repository.startDbDownload();
                }
                break;
            case NEEDS_DOWNLOAD:
            case WAITING_NETWORK:
            case FAILED:
            default:
                // WAITING_NETWORK 也走下载：策略挡的是「自动下载」，
                // 用户亲手点这一下就是策略在等的那个授权。
                repository.startDbDownload();
                break;
        }
    }

    /**
     * 渲染「诗库状态」行。**每个事件都会调**（下载中每秒好几次），所以这里只做
     * 几次 setText，绝不碰仓库、绝不重新查询。
     *
     * <p>唯一的副作用是记下 {@link #lastStatus} 供点击时判分支。
     */
    private void renderDbStatus(@NonNull DbStatus status) {
        if (binding == null) {
            return;
        }
        lastStatus = status;

        CharSequence value;
        String sub;
        switch (status.state) {
            case CHECKING:
                value = getString(R.string.data_loading);
                sub = getString(R.string.mine_data_checking_sub);
                break;
            case NEEDS_DOWNLOAD:
                value = getString(R.string.mine_data_update);
                sub = getString(R.string.mine_data_update_sub, readableSize(status.total));
                break;
            case WAITING_NETWORK:
                value = getString(R.string.mine_data_wait_wifi);
                sub = getString(R.string.mine_data_update_sub, readableSize(status.total));
                break;
            case DOWNLOADING:
                // percent 在总量未知时是 -1，那时只报「下载中」
                value = status.percent >= 0
                        ? getString(R.string.mine_data_downloading, status.percent)
                        : getString(R.string.data_loading);
                sub = getString(R.string.mine_data_progress_sub,
                        readableSize(status.done), readableSize(status.total))
                        + " · " + getString(R.string.mine_data_cancel);
                break;
            case VERIFYING:
                value = getString(R.string.mine_data_verifying);
                sub = getString(R.string.mine_data_verifying_sub);
                break;
            case INSTALLING:
                value = getString(R.string.mine_data_installing);
                sub = getString(R.string.mine_data_installing_sub);
                break;
            case FAILED:
                value = getString(R.string.mine_data_failed);
                sub = getString(R.string.mine_data_failed_sub,
                        status.error == null ? getString(R.string.mine_data_failed) : status.error);
                break;
            case IDLE:
            case INSTALLED:
            default:
                // INSTALLED 只是一次性信号，显示的稳态和「库已就绪」是同一个。
                //
                // 本机没装库时的取值刻意不是写死的「内置示例数据」：这时候内容
                // 其实可能来自服务端（离线策略是「远端 → 本地库 → 内置示例」），
                // 写死会在有网时明明白白说错话。
                value = status.localReady
                        ? getString(R.string.mine_data_ready)
                        : repository.sourceLabel();
                sub = getString(status.localReady
                        ? R.string.mine_data_ready_sub : R.string.mine_data_offline_sub);
                break;
        }

        binding.rowDataSource.rowValue.setText(value);
        binding.rowDataSource.rowSub.setText(sub);
        // 忙碌时把箭头藏掉：它说的是「点进去有详情页」，而这几秒里点它是没用的
        binding.rowDataSource.rowChevron.setVisibility(status.busy() ? View.GONE : View.VISIBLE);
        // adb 投放提示只在「什么都没载入」时出现——库好好的却教用户去 push 文件本来就是错的
        binding.dbHint.setVisibility(status.state == DbStatus.State.IDLE && !status.localReady
                ? View.VISIBLE : View.GONE);
    }

    /** 进度文案用。刻意不进 bytes 级别的精确度：这里要的是「还有多久」，不是字节数。 */
    @NonNull
    private static String readableSize(long bytes) {
        if (bytes <= 0) {
            return "—";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 先摘订阅再把 binding 置空：晚一步的话，队列里的那次回调会撞上 null binding。
        if (statusWatcher != null) {
            repository.removeDbStatusListener(statusWatcher);
            statusWatcher = null;
        }
        lastStatus = null;
        binding = null;
    }
}

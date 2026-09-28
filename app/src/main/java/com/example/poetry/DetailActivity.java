package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.Poem;
import com.example.poetry.databinding.ActivityDetailBinding;
import com.example.poetry.media.Speaker;
import com.example.poetry.ui.PinyinView;
import com.example.poetry.ui.Skin;
import com.example.poetry.ui.VoiceSheet;
import com.example.poetry.util.Pinyin;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;

import java.util.List;

/**
 * 作品详情页：顶部标题栏 + 正文（横排 / 竖排，可注音）+ 译文 / 注释 / 赏析 + 底部朗读条。
 * <p>
 * 竖屏下正文与各级标题一律居中排版；正文字号只有一个来源（text_xl × 缩放），
 * 不再出现样式与代码两套尺寸导致的「预览与实际不符」。
 */
public class DetailActivity extends AppCompatActivity {

    private static final String EXTRA_POEM = "extra_poem";

    private ActivityDetailBinding binding;
    private PoetryRepository repository;
    private UserStore store;

    private Poem poem;
    private boolean verticalMode;
    private boolean pinyinShown;
    private String fontFamily = "serif";
    private float fontScale = 1.0f;
    private boolean playing;
    /** 循环播放：一首读完自动重播本首 */
    private boolean loopPlay;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    /** 循环播放的下一次开读；停播 / 关循环 / 退出页面都要撤掉 */
    private Runnable loopRunnable;

    /**
     * 收藏变更监听：书架里把这首移出了、或在别处取消了收藏，
     * 这个页面的 ♡ 也要立刻跟着变，而不是等到下次进页面才对。
     */
    private final UserStore.FavoriteListener favoriteListener = new UserStore.FavoriteListener() {
        @Override
        public void onFavoritesChanged() {
            updateFavoriteIcon();
        }
    };

    public static void open(@NonNull Context context, @NonNull Poem poem) {
        Intent intent = new Intent(context, DetailActivity.class);
        intent.putExtra(EXTRA_POEM, poem);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = PoetryRepository.get(this);
        store = repository.store();
        store.addFavoriteListener(favoriteListener);
        Speaker.get().init(this);

        poem = getIntent().getParcelableExtra(EXTRA_POEM);
        if (poem == null) {
            Toast.makeText(this, R.string.search_no_match, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        verticalMode = store.isVerticalReading();
        pinyinShown = store.isPinyinShown();
        loopPlay = store.isLoopPlay();
        fontFamily = store.getFontFamily();
        fontScale = store.getFontScale();

        repository.recordHistory(poem);
        // 拼音表约 96 KB，进页面就后台载入，点开注音时无需等待
        Pinyin.warm(this);

        bindHeader();
        bindBody();
        bindTools();
        bindTabs();
        bindPlayer();
        // 偏好里打开了「进入详情自动朗读」，等页面稳住后自动开读
        if (store.isAutoPlay()) {
            binding.getRoot().postDelayed(this::startPlay, 600);
        }
    }

    // ---------------------------------------------------------------- 头部

    private void bindHeader() {
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        // 顶部标题栏平时留白，正文标题滚出屏幕后才显现，避免标题重复
        binding.toolbar.setTitle("");
        binding.toolbar.setOnMenuItemClickListener(this::onToolbarAction);
        binding.detailScroll.setOnScrollChangeListener(
                (NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldX, oldY) -> {
                    int threshold = binding.poemTitle.getBottom();
                    CharSequence title = scrollY > threshold ? poem.getTitle() : "";
                    if (!title.equals(binding.toolbar.getTitle())) {
                        binding.toolbar.setTitle(title);
                    }
                });

        binding.poemTitle.setText(poem.getTitle());
        binding.poemBadge.setText(poem.getKind().getLabel());
        ViewCompat_setBadgeTint();
        binding.poemBadge.setTextColor(ContextCompat.getColor(this, poem.getKind().getColorRes()));
        binding.poemAuthor.setText(poem.getAuthorLabel());
        binding.poemMeta.setText(getString(R.string.detail_meta,
                poem.getLineCount(), poem.getNChar()));

        updateFavoriteIcon();

        if (!poem.getStrain().isEmpty()) {
            binding.strainRow.setVisibility(View.VISIBLE);
            binding.strainText.setText(prettyStrain(poem.getStrain()));
        }
    }

    private void ViewCompat_setBadgeTint() {
        androidx.core.view.ViewCompat.setBackgroundTintList(binding.poemBadge,
                android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this,
                        poem.getKind().getLightColorRes())));
    }

    private boolean onToolbarAction(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_favorite) {
            toggleFavorite();
            return true;
        }
        return false;
    }

    private void toggleFavorite() {
        boolean added = repository.toggleFavorite(poem);
        updateFavoriteIcon();
        String message = getString(added ? R.string.shelf_added : R.string.shelf_removed);
        Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo, view -> {
                    repository.toggleFavorite(poem);
                    updateFavoriteIcon();
                })
                .show();
    }

    private void updateFavoriteIcon() {
        boolean favorite = repository.isFavorite(poem.getId());
        MenuItem item = binding.toolbar.getMenu().findItem(R.id.action_favorite);
        if (item != null) {
            item.setIcon(favorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
            item.setTitle(getString(R.string.cd_favorite));
        }
    }

    /** 平仄：1 平 / 0 仄 / 2 中 / 3 平韵 / 4 仄韵 */
    @NonNull
    private String prettyStrain(@NonNull String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            switch (raw.charAt(i)) {
                case '1':
                    sb.append('—');
                    break;
                case '0':
                    sb.append('｜');
                    break;
                case '3':
                    sb.append('◎');
                    break;
                case '4':
                    sb.append('●');
                    break;
                default:
                    sb.append('◇');
                    break;
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 正文

    private void bindBody() {
        updateBodyView();
    }

    /** 正文字号唯一来源：样式里的 text_xl（24sp）× 用户缩放 */
    private float poemCharSize() {
        return getResources().getDimension(R.dimen.text_xl) * fontScale;
    }

    @NonNull
    private Typeface currentTypeface() {
        return "sans".equals(fontFamily) ? Typeface.SANS_SERIF : Typeface.SERIF;
    }

    /**
     * 三种展示形态互斥：
     * 带注音 → PinyinView（横排/竖排都支持）；否则竖排 → 分列 TextView；否则 → 普通 TextView。
     */
    private void updateBodyView() {
        boolean pinyin = pinyinShown;
        binding.poemBody.setVisibility(!pinyin && !verticalMode ? View.VISIBLE : View.GONE);
        binding.pinyinScroll.setVisibility(pinyin ? View.VISIBLE : View.GONE);
        binding.verticalScroll.setVisibility(!pinyin && verticalMode ? View.VISIBLE : View.GONE);

        if (pinyin) {
            // 一次设完全部样式：只重排一遍，切换竖排/注音时不会看到中间态闪一下
            binding.pinyinBody.setStyle(currentTypeface(), poemCharSize(),
                    verticalMode ? PinyinView.ORIENT_VERTICAL : PinyinView.ORIENT_HORIZONTAL,
                    poem.getBody());
            binding.pinyinBody.setTextColor(ContextCompat.getColor(this, R.color.ink_900));
            binding.pinyinBody.setPinyinColor(ContextCompat.getColor(this, R.color.ink_500));
            return;
        }
        if (verticalMode) {
            renderVertical();
        } else {
            binding.poemBody.setTypeface(currentTypeface());
            binding.poemBody.setTextSize(TypedValue.COMPLEX_UNIT_PX, poemCharSize());
            binding.poemBody.setText(poem.getBody());
        }
    }

    /** 竖排：一句一列，自右向左排列（中文传统排版） */
    private void renderVertical() {
        binding.verticalContainer.removeAllViews();
        List<String> lines = poem.getLines();
        float size = poemCharSize();
        // 自右向左：按逆序添加，第一行最终落在最右侧
        for (int i = lines.size() - 1; i >= 0; i--) {
            TextView column = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(getResources().getDimensionPixelSize(R.dimen.space_2));
            column.setLayoutParams(lp);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.setTypeface(currentTypeface());
            column.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            column.setTextColor(ContextCompat.getColor(this, R.color.ink_900));
            column.setIncludeFontPadding(false);
            column.setLineSpacing(0f, 1.35f);

            StringBuilder sb = new StringBuilder();
            String line = lines.get(i);
            for (int c = 0; c < line.length(); c++) {
                if (c > 0) {
                    sb.append('\n');
                }
                sb.append(line.charAt(c));
            }
            column.setText(sb.toString());
            binding.verticalContainer.addView(column);
        }
    }

    private void applyFontScale() {
        updateBodyView();
    }

    // ---------------------------------------------------------------- 工具条

    private void bindTools() {
        setToggle(binding.toolVertical, verticalMode);
        binding.toolVertical.setOnClickListener(v -> {
            verticalMode = !verticalMode;
            store.setVerticalReading(verticalMode);
            setToggle(binding.toolVertical, verticalMode);
            updateBodyView();
        });

        binding.toolFontSmaller.setOnClickListener(v -> {
            fontScale = Math.max(0.85f, fontScale - 0.15f);
            store.setFontScale(fontScale);
            applyFontScale();
        });
        binding.toolFontLarger.setOnClickListener(v -> {
            fontScale = Math.min(1.6f, fontScale + 0.15f);
            store.setFontScale(fontScale);
            applyFontScale();
        });

        binding.toolFontFamily.setText(fontFamilyLabel());
        binding.toolFontFamily.setOnClickListener(v -> {
            boolean serif = "serif".equals(fontFamily);
            fontFamily = serif ? "sans" : "serif";
            store.setFontFamily(fontFamily);
            binding.toolFontFamily.setText(fontFamilyLabel());
            Toast.makeText(this, getString(R.string.font_switched, fontFamilyLabel()),
                    Toast.LENGTH_SHORT).show();
            updateBodyView();
        });

        binding.toolNote.setOnClickListener(v -> {
            TabLayout.Tab tab = binding.contentTabs.getTabAt(1);
            if (tab != null) {
                tab.select();
            }
            binding.contentTabs.requestFocus();
        });

        setToggle(binding.toolPinyin, pinyinShown);
        binding.toolPinyin.setOnClickListener(v -> {
            pinyinShown = !pinyinShown;
            store.setPinyinShown(pinyinShown);
            setToggle(binding.toolPinyin, pinyinShown);
            updateBodyView();
            Toast.makeText(this, pinyinShown ? R.string.pinyin_shown : R.string.pinyin_hidden,
                    Toast.LENGTH_SHORT).show();
        });
    }

    @NonNull
    private String fontFamilyLabel() {
        return getString("sans".equals(fontFamily)
                ? R.string.font_family_sans : R.string.font_family_serif);
    }

    /** 开关型工具按钮的选中态：选中不透明，未选中半透明 */
    private void setToggle(@NonNull View view, boolean selected) {
        view.setSelected(selected);
        view.setAlpha(selected ? 1f : 0.55f);
    }

    // ---------------------------------------------------------------- 译文 / 注释 / 赏析

    private void bindTabs() {
        binding.contentTabs.addTab(binding.contentTabs.newTab().setText(R.string.tab_translation));
        binding.contentTabs.addTab(binding.contentTabs.newTab().setText(R.string.tab_annotation));
        binding.contentTabs.addTab(binding.contentTabs.newTab().setText(R.string.tab_appreciation));
        binding.contentTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showContent(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                // ignore
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                showContent(tab.getPosition());
            }
        });
        showContent(0);
    }

    private void showContent(int position) {
        switch (position) {
            case 0:
                binding.contentText.setText(poem.getTranslation().isEmpty()
                        ? getString(R.string.remote_pending) : poem.getTranslation());
                break;
            case 1:
                List<String> notes = poem.getNoteList();
                if (notes.isEmpty()) {
                    binding.contentText.setText(R.string.detail_no_notes);
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < notes.size(); i++) {
                        sb.append(i + 1).append(". ").append(notes.get(i));
                        if (i < notes.size() - 1) {
                            sb.append("\n\n");
                        }
                    }
                    binding.contentText.setText(sb.toString());
                }
                break;
            default:
                binding.contentText.setText(poem.getAppreciation().isEmpty()
                        ? getString(R.string.remote_pending) : poem.getAppreciation());
                break;
        }
    }

    // ---------------------------------------------------------------- 朗读

    private void bindPlayer() {
        binding.nowPlaying.setText(getString(R.string.now_reading, poem.getTitle()));
        updateVoiceLabel();
        updatePlayIcon();

        binding.playButton.setOnClickListener(v -> {
            if (playing) {
                stopPlay();
            } else {
                startPlay();
            }
        });

        binding.voiceButton.setOnClickListener(v -> VoiceSheet.show(this,
                new VoiceSheet.Callback() {
                    @Override
                    public void onSelected(@NonNull com.example.poetry.data.model.Voice voice) {
                        updateVoiceLabel();
                        Toast.makeText(DetailActivity.this,
                                getString(R.string.player_voice, voice.getName()),
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onPreview(@NonNull com.example.poetry.data.model.Voice voice) {
                        Speaker.get().speak(getString(R.string.tts_preview_text));
                    }
                }));

        setToggle(binding.loopButton, loopPlay);
        binding.loopButton.setOnClickListener(v -> {
            loopPlay = !loopPlay;
            store.setLoopPlay(loopPlay);
            setToggle(binding.loopButton, loopPlay);
            Toast.makeText(this, loopPlay ? R.string.player_loop_on
                    : R.string.player_loop_off, Toast.LENGTH_SHORT).show();
            if (!loopPlay) {
                cancelLoop();
            }
        });

        Speaker.get().setListener(playerListener);
    }

    /** 朗读状态回调：从设置页返回时单例上的监听会被置空，因此每次播放前重新挂上 */
    private final Speaker.Listener playerListener = new Speaker.Listener() {
        @Override
        public void onStart() {
            playing = true;
            updatePlayIcon();
            startProgress();
        }

        @Override
        public void onDone() {
            playing = false;
            updatePlayIcon();
            stopProgress();
            binding.playProgress.setProgress(100);
            store.setProgress(poem.getId(), 100);
            scheduleLoop();
        }

        @Override
        public void onError(String message) {
            playing = false;
            updatePlayIcon();
            stopProgress();
            cancelLoop();
            Toast.makeText(DetailActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    };

    private void updateVoiceLabel() {
        String voiceId = store.getTtsConfig().getVoiceId();
        String name = voiceId.isEmpty() ? getString(R.string.voice_default_name) : "";
        if (name.isEmpty()) {
            for (com.example.poetry.data.model.Voice voice : Speaker.get().listVoices()) {
                if (voice.getId().equals(voiceId)) {
                    name = voice.getName();
                    break;
                }
            }
        }
        if (name.isEmpty()) {
            // 存着的音色在当前语音包里已经不存在了（换过包，或者是旧版的 sherpa 音色）：
            // 与其把内部 id 当名字显示给用户，不如说清楚它就是「默认」
            name = getString(R.string.voice_default_name);
        }
        binding.voiceLabel.setText(getString(R.string.player_voice, name));
    }

    private void updatePlayIcon() {
        binding.playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        binding.playButton.setContentDescription(getString(playing ? R.string.cd_pause : R.string.cd_play));
    }

    private void startPlay() {
        if (!Speaker.get().isReady()) {
            // 语音包没加载起来（模型还在拷、或 assets 里就没有）就没有声音可放；
            // 直接把用户送到语音设置页，那里会讲清楚是哪一种情况
            Snackbar.make(binding.getRoot(), R.string.tts_unavailable, Snackbar.LENGTH_LONG)
                    .setAction(R.string.tts_go_settings,
                            v -> VoiceSettingsActivity.open(this))
                    .show();
            return;
        }
        // 直接应用整份配置：引擎 + 发音人 + 语速 + 音调 + 音量
        Speaker.get().apply(store.getTtsConfig());
        Speaker.get().setListener(playerListener);
        // 按日期记一次朗读：同一天里同一首只累加次数
        store.recordPlay(poem);
        Speaker.get().speak(poem.getBody().replace("\n", "。"));
    }

    private void stopPlay() {
        cancelLoop();
        Speaker.get().stop();
        playing = false;
        updatePlayIcon();
        stopProgress();
    }

    /**
     * 循环模式下读完一首，隔一小会儿自动重播。
     * 留 1.2 秒空档：一口气接上去听不出「这一遍结束了」，不像在循环。
     */
    private void scheduleLoop() {
        cancelLoop();
        if (!loopPlay || isFinishing() || isDestroyed()) {
            return;
        }
        loopRunnable = new Runnable() {
            @Override
            public void run() {
                loopRunnable = null;
                startPlay();
            }
        };
        handler.postDelayed(loopRunnable, 1200L);
    }

    private void cancelLoop() {
        if (loopRunnable != null) {
            handler.removeCallbacks(loopRunnable);
            loopRunnable = null;
        }
    }

    /** 按字数估算朗读时长，进度条平滑推进 */
    private void startProgress() {
        stopProgress();
        long durationMs = Math.max(2000L, poem.getNChar() * 260L);
        long start = System.currentTimeMillis();
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                int percent = (int) ((System.currentTimeMillis() - start) * 100 / durationMs);
                if (percent > 100) {
                    percent = 100;
                }
                binding.playProgress.setProgress(percent);
                store.setProgress(poem.getId(), percent);
                if (percent < 100 && playing) {
                    handler.postDelayed(this, 200);
                }
            }
        };
        handler.post(progressRunnable);
    }

    private void stopProgress() {
        if (progressRunnable != null) {
            handler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    @Override
    protected void onDestroy() {
        store.removeFavoriteListener(favoriteListener);
        super.onDestroy();
        cancelLoop();
        stopProgress();
        Speaker.get().setListener(null);
        Speaker.get().stop();
    }
}

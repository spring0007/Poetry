package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.Poem;
import com.example.poetry.databinding.ActivityDetailBinding;
import com.example.poetry.media.Speaker;
import com.example.poetry.ui.VoiceSheet;
import com.google.android.material.tabs.TabLayout;

import java.util.List;

/**
 * 作品详情页：正文（横排 / 竖排）+ 译文 / 注释 / 赏析 + 底部朗读条。
 */
public class DetailActivity extends AppCompatActivity {

    private static final String EXTRA_POEM = "extra_poem";

    private ActivityDetailBinding binding;
    private PoetryRepository repository;
    private UserStore store;

    private Poem poem;
    private boolean verticalMode;
    private float fontScale = 1.0f;
    private boolean playing;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;

    public static void open(@NonNull Context context, @NonNull Poem poem) {
        Intent intent = new Intent(context, DetailActivity.class);
        intent.putExtra(EXTRA_POEM, poem);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = PoetryRepository.get(this);
        store = repository.store();
        Speaker.get().init(this);

        poem = getIntent().getParcelableExtra(EXTRA_POEM);
        if (poem == null) {
            Toast.makeText(this, R.string.search_no_match, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        verticalMode = store.isVerticalReading();
        fontScale = store.getFontScale();

        repository.recordHistory(poem);

        bindHeader();
        bindBody();
        bindTools();
        bindTabs();
        bindPlayer();
    }

    // ---------------------------------------------------------------- 头部

    private void bindHeader() {
        binding.toolbarTitle.setText(poem.getTitle());
        binding.backButton.setOnClickListener(v -> finish());

        binding.poemTitle.setText(poem.getTitle());
        binding.poemBadge.setText(poem.getKind().getLabel());
        ViewCompat.setBackgroundTintList(binding.poemBadge,
                android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this,
                        poem.getKind().getLightColorRes())));
        binding.poemBadge.setTextColor(ContextCompat.getColor(this, poem.getKind().getColorRes()));
        binding.poemAuthor.setText(poem.getAuthorLabel());
        binding.poemMeta.setText(getString(R.string.detail_meta,
                poem.getLineCount(), poem.getNChar()));

        updateFavoriteIcon();
        binding.favButton.setOnClickListener(v -> {
            boolean added = repository.toggleFavorite(poem);
            updateFavoriteIcon();
            Toast.makeText(this, added ? R.string.shelf_added : R.string.shelf_removed,
                    Toast.LENGTH_SHORT).show();
        });

        if (!poem.getStrain().isEmpty()) {
            binding.strainRow.setVisibility(View.VISIBLE);
            binding.strainText.setText(prettyStrain(poem.getStrain()));
        }
    }

    private void updateFavoriteIcon() {
        boolean favorite = repository.isFavorite(poem.getId());
        binding.favButton.setImageResource(favorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        binding.favButton.setContentDescription(getString(R.string.cd_favorite));
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
        binding.poemBody.setText(poem.getBody());
        applyFontScale();
        renderVerticalIfNeeded();
    }

    private void applyFontScale() {
        float base = getResources().getDimension(R.dimen.text_lg);
        binding.poemBody.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * fontScale);
        renderVerticalIfNeeded();
    }

    /**
     * 竖排：一句一列，自右向左排列（中文传统排版）。
     */
    private void renderVerticalIfNeeded() {
        binding.verticalContainer.removeAllViews();
        if (!verticalMode) {
            binding.poemBody.setVisibility(View.VISIBLE);
            binding.verticalScroll.setVisibility(View.GONE);
            return;
        }
        binding.poemBody.setVisibility(View.GONE);
        binding.verticalScroll.setVisibility(View.VISIBLE);

        List<String> lines = poem.getLines();
        float base = getResources().getDimension(R.dimen.text_lg);
        // 自右向左：按逆序添加
        for (int i = lines.size() - 1; i >= 0; i--) {
            TextView column = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(getResources().getDimensionPixelSize(R.dimen.space_2));
            column.setLayoutParams(lp);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.setTypeface(android.graphics.Typeface.SERIF);
            column.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * fontScale);
            column.setTextColor(ContextCompat.getColor(this, R.color.ink_900));
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

    private void bindTools() {
        binding.toolVertical.setSelected(verticalMode);
        binding.toolVertical.setAlpha(verticalMode ? 1f : 0.55f);
        binding.toolVertical.setOnClickListener(v -> {
            verticalMode = !verticalMode;
            binding.toolVertical.setSelected(verticalMode);
            binding.toolVertical.setAlpha(verticalMode ? 1f : 0.55f);
            renderVerticalIfNeeded();
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
        binding.toolNote.setOnClickListener(v -> {
            TabLayout.Tab tab = binding.contentTabs.getTabAt(1);
            if (tab != null) {
                tab.select();
            }
            binding.contentTabs.requestFocus();
        });
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

        Speaker.get().setListener(new Speaker.Listener() {
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
            }

            @Override
            public void onError(String message) {
                playing = false;
                updatePlayIcon();
                stopProgress();
                Toast.makeText(DetailActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateVoiceLabel() {
        String voiceId = store.getVoiceId();
        String name = voiceId.isEmpty() ? getString(R.string.voice_system_default) : voiceId;
        binding.voiceLabel.setText(getString(R.string.player_voice, name));
    }

    private void updatePlayIcon() {
        binding.playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        binding.playButton.setContentDescription(getString(playing ? R.string.cd_pause : R.string.cd_play));
    }

    private void startPlay() {
        if (!Speaker.get().isReady()) {
            Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        String voiceId = store.getVoiceId();
        if (!voiceId.isEmpty()) {
            Speaker.get().setVoice(voiceId);
        }
        Speaker.get().setRate(store.getSpeechRate());
        Speaker.get().speak(poem.getBody().replace("\n", "。"));
    }

    private void stopPlay() {
        Speaker.get().stop();
        playing = false;
        updatePlayIcon();
        stopProgress();
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
        super.onDestroy();
        stopProgress();
        Speaker.get().setListener(null);
        Speaker.get().stop();
    }
}

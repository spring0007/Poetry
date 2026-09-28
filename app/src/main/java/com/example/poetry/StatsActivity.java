package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.DayStat;
import com.example.poetry.databinding.ActivityStatsBinding;
import com.example.poetry.ui.Skin;

import java.util.List;
import java.util.Locale;

/**
 * 数据统计：按日期列出朗读过的诗与收藏过的诗。
 *
 * <p>数据来自 {@link UserStore} 里 {@code user_data.json} 的 {@code daily} 分区，
 * 结构就是「日期 → 当天行为」，以后要往服务端同步，直接把这一段上报即可，
 * 不需要后台再按时间戳反推。这里只做只读展示，正文里不留任何写操作。
 */
public class StatsActivity extends AppCompatActivity {

    /** 最多回看多少天 */
    private static final int MAX_DAYS = 30;
    /** 累计榜展示前几名 */
    private static final int TOP_LIMIT = 5;

    private ActivityStatsBinding binding;
    private UserStore store;

    public static void open(@Nullable Context context) {
        if (context != null) {
            context.startActivity(new Intent(context, StatsActivity.class));
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityStatsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        PoetryRepository repository = PoetryRepository.get(this);
        store = repository.store();

        binding.backButton.setOnClickListener(v -> finish());
        binding.emptyState.emptyTitle.setText(R.string.stats_empty_title);
        binding.emptyState.emptyDesc.setText(R.string.stats_empty_desc);
        binding.emptyState.emptyAction.setVisibility(View.GONE);

        render();
    }

    @NonNull
    private LinearLayout newCard(int gap, int pad) {
        LinearLayout card = new LinearLayout(this);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = gap;
        card.setLayoutParams(cardLp);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        card.setBackgroundResource(R.drawable.bg_card_lg);
        return card;
    }

    private void render() {
        List<DayStat> days = store.getDailyStats(MAX_DAYS);
        binding.statsSummary.setText(getString(R.string.stats_summary,
                store.getActiveDays(), store.getPlayCount()));
        binding.statsContainer.removeAllViews();

        boolean empty = days.isEmpty();
        binding.emptyState.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);

        String today = UserStore.today();
        int gap = getResources().getDimensionPixelSize(R.dimen.space_3);
        int pad = getResources().getDimensionPixelSize(R.dimen.space_4);

        List<DayStat.Item> top = store.getTopPlayed(TOP_LIMIT);
        if (!top.isEmpty()) {
            LinearLayout card = newCard(gap, pad);
            card.addView(title(getString(R.string.stats_top_title)));
            card.addView(caption(getString(R.string.stats_top_desc)));
            for (DayStat.Item item : top) {
                card.addView(caption("#" + item.id + "  《" + item.title + "》" + item.author
                        + "  " + String.format(Locale.CHINA, "×%d", item.count)));
            }
            binding.statsContainer.addView(card);
        }

        for (DayStat day : days) {
            LinearLayout card = new LinearLayout(this);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = gap;
            card.setLayoutParams(cardLp);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(pad, pad, pad, pad);
            card.setBackgroundResource(R.drawable.bg_card_lg);

            card.addView(title(day.date.equals(today)
                    ? getString(R.string.stats_today) + " · " + day.date : day.date));

            StringBuilder meta = new StringBuilder();
            meta.append(getString(R.string.stats_day_play, day.playCount, day.playTitles()));
            if (day.favoriteCount > 0 || day.unfavoriteCount > 0) {
                meta.append(" · ").append(getString(R.string.stats_day_fav,
                        day.favoriteCount, day.unfavoriteCount));
            }
            card.addView(caption(meta.toString()));

            if (!day.plays.isEmpty()) {
                card.addView(subTitle(getString(R.string.stats_group_played)));
                for (DayStat.Item item : day.plays) {
                    card.addView(caption("#" + item.id + "  《" + item.title + "》" + item.author
                            + "  " + String.format(Locale.CHINA, "×%d", item.count)));
                }
            }
            if (!day.favorites.isEmpty()) {
                card.addView(subTitle(getString(R.string.stats_group_favorite)));
                for (DayStat.Item item : day.favorites) {
                    card.addView(caption("#" + item.id + "  《" + item.title + "》" + item.author
                            + "  " + String.format(Locale.CHINA, "×%d", item.count)));
                }
            }

            binding.statsContainer.addView(card);
        }
    }

    @NonNull
    private TextView title(@NonNull String text) {
        TextView view = new TextView(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        view.setText(text);
        view.setTextAppearance(R.style.TextAppearance_Poetry_SectionTitle);
        return view;
    }

    @NonNull
    private TextView subTitle(@NonNull String text) {
        TextView view = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = getResources().getDimensionPixelSize(R.dimen.space_2);
        view.setLayoutParams(lp);
        view.setText(text);
        view.setTextAppearance(R.style.TextAppearance_Poetry_Caption);
        view.setTextColor(ContextCompat.getColor(this, R.color.color_primary));
        return view;
    }

    @NonNull
    private TextView caption(@NonNull String text) {
        TextView view = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = getResources().getDimensionPixelSize(R.dimen.space_1);
        view.setLayoutParams(lp);
        view.setGravity(Gravity.START);
        view.setText(text);
        view.setTextAppearance(R.style.TextAppearance_Poetry_CaptionWeak);
        return view;
    }
}

package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.Poem;
import com.example.poetry.databinding.ActivityHistoryBinding;
import com.example.poetry.databinding.ItemPoemRowBinding;
import com.example.poetry.ui.Skin;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 朗读历史：最近读过的 30 首，可单条删除，也可一键清空。
 *
 * 历史与朗读次数都只存在本机 {@link UserStore} 里，清空不可撤销，
 * 所以清空前会先确认一次。
 */
public class HistoryActivity extends AppCompatActivity {

    private ActivityHistoryBinding binding;
    private PoetryRepository repository;
    private UserStore store;
    private HistoryAdapter adapter;

    public static void open(@Nullable Context context) {
        if (context != null) {
            context.startActivity(new Intent(context, HistoryActivity.class));
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = PoetryRepository.get(this);
        store = repository.store();

        binding.backButton.setOnClickListener(v -> finish());
        binding.clearButton.setOnClickListener(v -> confirmClear());

        adapter = new HistoryAdapter(poem -> DetailActivity.open(this, poem),
                poem -> {
                    repository.removeHistory(poem.getId());
                    refresh();
                });
        binding.historyList.setLayoutManager(new LinearLayoutManager(this));
        binding.historyList.setAdapter(adapter);

        binding.emptyState.emptyTitle.setText(R.string.history_empty_title);
        binding.emptyState.emptyDesc.setText(R.string.history_empty_desc);
        binding.emptyState.emptyAction.setVisibility(View.GONE);

        refresh();
    }

    private void confirmClear() {
        if (adapter.getItemCount() == 0) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.history_clear)
                .setMessage(R.string.history_clear_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    store.clearHistory();
                    Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }

    private void refresh() {
        List<Poem> list = new ArrayList<>(repository.history());
        binding.historySummary.setText(getString(R.string.history_summary, list.size()));
        binding.emptyState.getRoot().setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        binding.historyList.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        binding.clearButton.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.submit(list);
    }

    /** 把时间戳说成人话：今天几点 / 昨天 / 周几 / 几月几日 */
    @NonNull
    public static String formatReadAt(long time) {
        if (time <= 0) {
            return "";
        }
        Calendar target = Calendar.getInstance(Locale.CHINA);
        target.setTimeInMillis(time);
        Calendar now = Calendar.getInstance(Locale.CHINA);
        boolean sameDay = target.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
        if (sameDay) {
            return "今天 " + new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(time));
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        boolean yesterday = target.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
        if (yesterday) {
            return "昨天 " + new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(time));
        }
        long days = (System.currentTimeMillis() - time) / 86_400_000L;
        if (days < 7) {
            return new String[]{"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"}[
                    weekdayOf(target)];
        }
        return new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(time));
    }

    private static int weekdayOf(@NonNull Calendar calendar) {
        int value = calendar.get(Calendar.DAY_OF_WEEK);
        // Calendar 以周日为 1，这里换算成「周一 = 1 … 周日 = 7」
        return value == Calendar.SUNDAY ? 7 : value - 1;
    }

    // ---------------------------------------------------------------- 列表

    private final class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.Holder> {

        private final List<Poem> data = new ArrayList<>();
        private final RowListener open;
        private final RowListener remove;

        HistoryAdapter(@NonNull RowListener open, @NonNull RowListener remove) {
            this.open = open;
            this.remove = remove;
        }

        void submit(@NonNull List<Poem> poems) {
            data.clear();
            data.addAll(poems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemPoemRowBinding row = ItemPoemRowBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new Holder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(data.get(position));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        final class Holder extends RecyclerView.ViewHolder {

            private final ItemPoemRowBinding row;

            Holder(@NonNull ItemPoemRowBinding row) {
                super(row.getRoot());
                this.row = row;
            }

            void bind(@NonNull Poem poem) {
                Context context = itemView.getContext();
                int color = ContextCompat.getColor(context, poem.getKind().getColorRes());
                int light = ContextCompat.getColor(context, poem.getKind().getLightColorRes());

                row.rowBar.setBackgroundColor(color);
                row.rowTitle.setText(poem.getTitle());
                row.rowBadge.setText(poem.getKind().getLabel());
                ViewCompat.setBackgroundTintList(row.rowBadge, ColorStateList.valueOf(light));
                row.rowBadge.setTextColor(color);

                String when = formatReadAt(store.getReadAt(poem.getId()));
                row.rowMeta.setText(when.isEmpty()
                        ? poem.getAuthorLabel()
                        : when + " · " + poem.getAuthorLabel());

                row.getRoot().setOnClickListener(v -> open.onClick(poem));
                row.rowRemove.setOnClickListener(v -> remove.onClick(poem));
            }
        }
    }

    /** 行点击回调：打开 / 删除 */
    private interface RowListener {
        void onClick(@NonNull Poem poem);
    }
}

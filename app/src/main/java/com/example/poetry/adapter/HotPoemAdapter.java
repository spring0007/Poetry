package com.example.poetry.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.poetry.R;
import com.example.poetry.data.model.Poem;
import com.example.poetry.databinding.ItemHotPoemBinding;
import com.example.poetry.util.PoemLength;

import java.util.ArrayList;
import java.util.List;

/**
 * 热点榜适配器（发现页横向列表）。
 * <p>
 * 前三名用金色排名徽标，之后用体裁淡色，视觉上与其他徽标保持一致。
 */
public class HotPoemAdapter extends RecyclerView.Adapter<HotPoemAdapter.Holder> {

    public interface Listener {
        void onPoemClick(@NonNull Poem poem);
    }

    private final List<Poem> data = new ArrayList<>();
    private final Listener listener;

    public HotPoemAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    /**
     * 替换整份列表。走 diff 而不是 {@code notifyDataSetChanged()}，规则见 {@link PoemDiff}
     * —— 排名徽标是 position 派生的，那份规则把位置也算作内容，挪过位的条目会重绑。
     */
    public void submit(@NonNull List<Poem> poems) {
        PoemDiff.submit(this, data, poems);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHotPoemBinding binding = ItemHotPoemBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(data.get(position), position);
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    class Holder extends RecyclerView.ViewHolder {

        private final ItemHotPoemBinding binding;

        Holder(@NonNull ItemHotPoemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Poem poem, int position) {
            int rank = position + 1;
            binding.hotRank.setText(String.valueOf(rank));
            if (rank <= 3) {
                ViewCompat.setBackgroundTintList(binding.hotRank, ColorStateList.valueOf(
                        ContextCompat.getColor(itemView.getContext(), R.color.color_gold)));
                binding.hotRank.setTextColor(ContextCompat.getColor(itemView.getContext(),
                        R.color.white));
            } else {
                ViewCompat.setBackgroundTintList(binding.hotRank, ColorStateList.valueOf(
                        KindColors.light(itemView.getContext(), poem.getKind())));
                binding.hotRank.setTextColor(KindColors.solid(itemView.getContext(), poem.getKind()));
            }

            binding.hotBadge.setText(poem.getKind().getLabel());
            binding.hotBadge.setTextColor(KindColors.solid(itemView.getContext(), poem.getKind()));

            binding.hotTitle.setText(poem.getTitle());
            binding.hotAuthor.setText(poem.getAuthorLabel());
            // 热度榜走的是远端列表接口，条目没有正文，篇幅只能按 nChar 写「N 字」；
            // 连 nChar 都没有时只显示热度，别在后面挂一个空的「 · 」
            Context context = itemView.getContext();
            String length = PoemLength.label(context, poem);
            binding.hotValue.setText(length.isEmpty()
                    ? context.getString(R.string.hot_value_only, poem.getHotPercent())
                    : context.getString(R.string.hot_value, poem.getHotPercent(), length));

            binding.getRoot().setOnClickListener(v -> listener.onPoemClick(poem));
            binding.getRoot().setContentDescription(
                    itemView.getContext().getString(R.string.hot_rank_desc, rank, poem.getTitle()));
        }
    }
}

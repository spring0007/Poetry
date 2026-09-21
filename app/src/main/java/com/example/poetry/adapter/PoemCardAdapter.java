package com.example.poetry.adapter;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.poetry.R;
import com.example.poetry.data.model.Dynasty;
import com.example.poetry.data.model.Poem;
import com.example.poetry.databinding.ItemPoemCardBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 诗卡适配器（发现页「精选诗词」、书架网格、分类结果列表）。
 * <p>
 * 对应设计稿：左侧体裁色条 + 体裁徽标 / 标题 / 作者 / 摘录 / 热度星级。
 */
public class PoemCardAdapter extends RecyclerView.Adapter<PoemCardAdapter.Holder> {

    public interface Listener {
        void onPoemClick(@NonNull Poem poem);

        void onFavoriteClick(@NonNull Poem poem);
    }

    private final List<Poem> data = new ArrayList<>();
    private final Listener listener;

    public PoemCardAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void submit(@NonNull List<Poem> poems) {
        data.clear();
        data.addAll(poems);
        notifyDataSetChanged();
    }

    public void append(@NonNull List<Poem> poems) {
        int start = data.size();
        data.addAll(poems);
        notifyItemRangeInserted(start, poems.size());
    }

    public void clear() {
        data.clear();
        notifyDataSetChanged();
    }

    @NonNull
    public List<Poem> items() {
        return data;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPoemCardBinding binding = ItemPoemCardBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(data.get(position));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    class Holder extends RecyclerView.ViewHolder {

        private final ItemPoemCardBinding binding;

        Holder(@NonNull ItemPoemCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Poem poem) {
            int color = ContextCompat.getColor(itemView.getContext(), poem.getKind().getColorRes());
            int light = ContextCompat.getColor(itemView.getContext(), poem.getKind().getLightColorRes());

            // 左侧体裁色条
            binding.cardBar.setBackgroundColor(color);

            // 体裁徽标
            binding.cardBadge.setText(poem.getKind().getLabel());
            ViewCompat.setBackgroundTintList(binding.cardBadge, ColorStateList.valueOf(light));
            binding.cardBadge.setTextColor(color);

            binding.cardDynasty.setText(Dynasty.labelOf(poem.getDynasty()));
            binding.cardTitle.setText(poem.getTitle());
            binding.cardAuthor.setText(poem.getAuthorLabel());
            binding.cardExcerpt.setText(poem.getExcerpt());
            binding.cardStars.setText(poem.getStars());
            binding.cardHot.setText(itemView.getContext().getString(R.string.poem_line_count,
                    poem.getLineCount()));
            binding.cardFavFlag.setText(itemView.getContext().getString(R.string.cd_favorite));

            binding.getRoot().setOnClickListener(v -> listener.onPoemClick(poem));
            binding.cardFavFlag.setOnClickListener(v -> listener.onFavoriteClick(poem));
            binding.getRoot().setContentDescription(poem.getTitle() + "，" + poem.getAuthorLabel());
        }
    }
}

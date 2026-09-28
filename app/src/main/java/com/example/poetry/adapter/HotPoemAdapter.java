package com.example.poetry.adapter;

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

    public void submit(@NonNull List<Poem> poems) {
        data.clear();
        data.addAll(poems);
        notifyDataSetChanged();
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
                        ContextCompat.getColor(itemView.getContext(),
                                poem.getKind().getLightColorRes())));
                binding.hotRank.setTextColor(ContextCompat.getColor(itemView.getContext(),
                        poem.getKind().getColorRes()));
            }

            binding.hotBadge.setText(poem.getKind().getLabel());
            binding.hotBadge.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    poem.getKind().getColorRes()));

            binding.hotTitle.setText(poem.getTitle());
            binding.hotAuthor.setText(poem.getAuthorLabel());
            binding.hotValue.setText(itemView.getContext().getString(R.string.hot_value,
                    poem.getHotPercent(), poem.getLineCount()));

            binding.getRoot().setOnClickListener(v -> listener.onPoemClick(poem));
            binding.getRoot().setContentDescription(
                    itemView.getContext().getString(R.string.hot_rank_desc, rank, poem.getTitle()));
        }
    }
}

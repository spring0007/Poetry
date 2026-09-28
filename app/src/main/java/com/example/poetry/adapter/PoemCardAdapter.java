package com.example.poetry.adapter;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
 * <p>
 * 删除入口是**可选能力**：默认关闭，只有书架页通过
 * {@link #setDeleteMode(boolean)} 打开编辑态后才出现右上角 ✕；
 * 长按卡片同样可请求进入编辑态。发现页、列表页不设置监听器，行为与之前完全一致。
 */
public class PoemCardAdapter extends RecyclerView.Adapter<PoemCardAdapter.Holder> {

    public interface Listener {
        void onPoemClick(@NonNull Poem poem);

        void onFavoriteClick(@NonNull Poem poem);
    }

    /** 删除相关回调，只有书架页会设置 */
    public interface DeleteListener {
        /** 点击删除按钮 */
        void onDeleteClick(@NonNull Poem poem);

        /** 长按卡片，请求进入编辑态 */
        void onLongPress(@NonNull Poem poem);
    }

    /** 热度达到该百分比才打「热」标记 */
    private static final int HOT_THRESHOLD = 70;

    private final List<Poem> data = new ArrayList<>();
    private final Listener listener;

    private boolean deleteMode;
    private DeleteListener deleteListener;
    private boolean hotMarkEnabled;

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

    /** 编辑态开关：打开后每张卡右上角出现删除按钮 */
    public void setDeleteMode(boolean enabled) {
        if (deleteMode == enabled) {
            return;
        }
        deleteMode = enabled;
        notifyDataSetChanged();
    }

    public void setDeleteListener(@Nullable DeleteListener deleteListener) {
        this.deleteListener = deleteListener;
    }

    /** 是否显示热度标记（热点榜与精选列表打开，书架网格保持原样） */
    public void setHotMarkEnabled(boolean enabled) {
        if (hotMarkEnabled == enabled) {
            return;
        }
        hotMarkEnabled = enabled;
        notifyDataSetChanged();
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
            // 编辑态下「收藏」标记让位给删除按钮，避免同一张卡上两个操作点互相干扰
            binding.cardFavFlag.setVisibility(deleteMode ? View.GONE : View.VISIBLE);

            bindHotTag(poem);
            bindDelete(poem);

            binding.getRoot().setOnClickListener(v -> listener.onPoemClick(poem));
            binding.getRoot().setOnLongClickListener(v -> {
                if (deleteListener != null) {
                    deleteListener.onLongPress(poem);
                    return true;
                }
                return false;
            });
            binding.getRoot().setContentDescription(poem.getTitle() + "，" + poem.getAuthorLabel());
        }

        /** 热度标记：达到阈值才显示，配色沿用体裁徽标那一套，不引入新样式 */
        private void bindHotTag(@NonNull Poem poem) {
            if (!hotMarkEnabled || poem.getHotPercent() < HOT_THRESHOLD) {
                binding.cardHotTag.setVisibility(View.GONE);
                return;
            }
            int color = ContextCompat.getColor(itemView.getContext(), poem.getKind().getColorRes());
            int light = ContextCompat.getColor(itemView.getContext(), poem.getKind().getLightColorRes());
            binding.cardHotTag.setVisibility(View.VISIBLE);
            binding.cardHotTag.setText(itemView.getContext().getString(R.string.hot_mark_value,
                    poem.getHotPercent()));
            ViewCompat.setBackgroundTintList(binding.cardHotTag, ColorStateList.valueOf(light));
            binding.cardHotTag.setTextColor(color);
        }

        private void bindDelete(@NonNull Poem poem) {
            binding.cardDelete.setVisibility(deleteMode ? View.VISIBLE : View.GONE);
            if (deleteMode && deleteListener != null) {
                binding.cardDelete.setOnClickListener(v -> deleteListener.onDeleteClick(poem));
            } else {
                binding.cardDelete.setOnClickListener(null);
            }
        }
    }
}

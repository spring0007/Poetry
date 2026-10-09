package com.example.poetry.adapter;

import android.content.Context;
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
import com.example.poetry.data.model.PoemKind;
import com.example.poetry.databinding.ItemListFooterBinding;
import com.example.poetry.databinding.ItemPoemCardBinding;
import com.example.poetry.util.PoemLength;

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
 * <p>
 * 底部的「加载中 / 到底了」同样是可选能力，见 {@link #setFooterState(int)} ——
 * 只有分页的列表页需要它，其余场景多出来的一行会破坏布局。
 */
public class PoemCardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

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

    private static final int TYPE_POEM = 0;
    private static final int TYPE_FOOTER = 1;

    /** 不显示底部（默认） */
    public static final int FOOTER_NONE = 0;
    /** 底部显示「正在加载更多」 */
    public static final int FOOTER_LOADING = 1;
    /** 底部显示「已经到底了」 */
    public static final int FOOTER_END = 2;

    private final List<Poem> data = new ArrayList<>();
    private final Listener listener;

    private boolean deleteMode;
    private DeleteListener deleteListener;
    private boolean hotMarkEnabled;
    private int footerState = FOOTER_NONE;

    public PoemCardAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    /**
     * 替换整份列表。走 diff 而不是 {@code notifyDataSetChanged()}，规则见 {@link PoemDiff}。
     */
    public void submit(@NonNull List<Poem> poems) {
        PoemDiff.submit(this, data, poems);
    }

    /** 追加一页（分页加载用）。 */
    public void append(@NonNull List<Poem> poems) {
        if (poems.isEmpty()) {
            return;
        }
        int start = data.size();
        data.addAll(poems);
        notifyItemRangeInserted(start, poems.size());
    }

    public void clear() {
        int size = data.size();
        data.clear();
        if (size > 0) {
            notifyItemRangeRemoved(0, size);
        }
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

    /**
     * 底部状态，见 {@code FOOTER_*}。只有分页列表该开它。
     */
    public void setFooterState(int state) {
        if (footerState == state) {
            return;
        }
        footerState = state;
        if (state == FOOTER_NONE) {
            notifyItemRemoved(data.size());
        } else if (getItemCount() > data.size()) {
            // 已经有一行了，只换内容，不动列表结构
            notifyItemChanged(data.size());
        } else {
            notifyItemInserted(data.size());
        }
    }

    @Override
    public int getItemViewType(int position) {
        return position >= data.size() ? TYPE_FOOTER : TYPE_POEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_FOOTER) {
            return new FooterViewHolder(ItemListFooterBinding.inflate(inflater, parent, false));
        }
        return new Holder(ItemPoemCardBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof FooterViewHolder) {
            bindFooter((FooterViewHolder) holder);
        } else {
            ((Holder) holder).bind(data.get(position));
        }
    }

    @Override
    public int getItemCount() {
        return data.size() + (footerState == FOOTER_NONE ? 0 : 1);
    }

    private void bindFooter(@NonNull FooterViewHolder holder) {
        boolean loading = footerState == FOOTER_LOADING;
        holder.binding.footerProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        holder.binding.footerText.setText(loading ? R.string.list_loading_more : R.string.list_end);
    }

    class FooterViewHolder extends RecyclerView.ViewHolder {

        private final ItemListFooterBinding binding;

        FooterViewHolder(@NonNull ItemListFooterBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    class Holder extends RecyclerView.ViewHolder {

        private final ItemPoemCardBinding binding;

        Holder(@NonNull ItemPoemCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Poem poem) {
            Context context = itemView.getContext();
            PoemKind kind = poem.getKind();
            int color = KindColors.solid(context, kind);
            int light = KindColors.light(context, kind);

            // 左侧体裁色条
            binding.cardBar.setBackgroundColor(color);

            // 体裁徽标
            binding.cardBadge.setText(kind.getLabel());
            ViewCompat.setBackgroundTintList(binding.cardBadge, ColorStateList.valueOf(light));
            binding.cardBadge.setTextColor(color);

            binding.cardDynasty.setText(Dynasty.labelOf(poem.getDynasty()));
            binding.cardTitle.setText(poem.getTitle());
            binding.cardAuthor.setText(poem.getAuthorLabel());
            binding.cardExcerpt.setText(poem.getExcerpt());
            binding.cardStars.setText(poem.getStars());
            binding.cardHot.setText(PoemLength.label(context, poem));
            binding.cardFavFlag.setText(context.getString(R.string.cd_favorite));
            // 编辑态下「收藏」标记让位给删除按钮，避免同一张卡上两个操作点互相干扰
            binding.cardFavFlag.setVisibility(deleteMode ? View.GONE : View.VISIBLE);

            bindHotTag(poem, kind, color, light);
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
        private void bindHotTag(@NonNull Poem poem, @NonNull PoemKind kind,
                                int color, int light) {
            if (!hotMarkEnabled || poem.getHotPercent() < HOT_THRESHOLD) {
                binding.cardHotTag.setVisibility(View.GONE);
                return;
            }
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

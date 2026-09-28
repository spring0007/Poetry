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

import com.example.poetry.data.model.Dynasty;
import com.example.poetry.data.model.Poem;
import com.example.poetry.databinding.ItemPoemRowBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 书架行卡适配器（列表视图）。
 * <p>
 * 删除按钮默认隐藏，由书架页通过 {@link #setDeleteMode(boolean)} 打开编辑态后显示，
 * 与网格视图的删除入口行为保持一致。
 */
public class PoemRowAdapter extends RecyclerView.Adapter<PoemRowAdapter.Holder> {

    /** 由调用方决定行内第二行显示什么 */
    public interface MetaProvider {
        @NonNull
        String metaOf(@NonNull Poem poem);
    }

    public interface Listener {
        void onPoemClick(@NonNull Poem poem);

        void onRemoveClick(@NonNull Poem poem);
    }

    /** 长按行卡，请求进入编辑态 */
    public interface EditListener {
        void onLongPress(@NonNull Poem poem);
    }

    private final List<Poem> data = new ArrayList<>();
    private final Listener listener;
    /** 可选的元信息提供者，用于显示收藏时间等额外信息 */
    private MetaProvider metaProvider;
    private EditListener editListener;
    private boolean deleteMode;

    public PoemRowAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void setMetaProvider(@Nullable MetaProvider provider) {
        metaProvider = provider;
    }

    public void setEditListener(@Nullable EditListener editListener) {
        this.editListener = editListener;
    }

    /** 编辑态开关：打开后每行右侧出现删除按钮 */
    public void setDeleteMode(boolean enabled) {
        if (deleteMode == enabled) {
            return;
        }
        deleteMode = enabled;
        notifyDataSetChanged();
    }

    public void submit(@NonNull List<Poem> poems) {
        data.clear();
        data.addAll(poems);
        notifyDataSetChanged();
    }

    @NonNull
    public List<Poem> items() {
        return data;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPoemRowBinding binding = ItemPoemRowBinding.inflate(
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

        private final ItemPoemRowBinding binding;

        Holder(@NonNull ItemPoemRowBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Poem poem) {
            int color = ContextCompat.getColor(itemView.getContext(), poem.getKind().getColorRes());
            int light = ContextCompat.getColor(itemView.getContext(), poem.getKind().getLightColorRes());

            binding.rowBar.setBackgroundColor(color);
            binding.rowTitle.setText(poem.getTitle());
            binding.rowBadge.setText(poem.getKind().getLabel());
            ViewCompat.setBackgroundTintList(binding.rowBadge, ColorStateList.valueOf(light));
            binding.rowBadge.setTextColor(color);
            String meta = metaProvider != null ? metaProvider.metaOf(poem)
                    : poem.getAuthorLabel() + " · "
                    + Dynasty.labelOf(poem.getDynasty()) + " · " + poem.getLineCount() + " 句";
            binding.rowMeta.setText(meta);

            binding.rowRemove.setVisibility(deleteMode ? View.VISIBLE : View.GONE);
            binding.rowRemove.setOnClickListener(v -> listener.onRemoveClick(poem));

            binding.getRoot().setOnClickListener(v -> listener.onPoemClick(poem));
            binding.getRoot().setOnLongClickListener(v -> {
                if (editListener != null) {
                    editListener.onLongPress(poem);
                    return true;
                }
                return false;
            });
        }
    }
}

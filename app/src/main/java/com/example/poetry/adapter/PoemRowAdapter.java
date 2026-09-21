package com.example.poetry.adapter;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
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
 */
public class PoemRowAdapter extends RecyclerView.Adapter<PoemRowAdapter.Holder> {

    public interface Listener {
        void onPoemClick(@NonNull Poem poem);

        void onRemoveClick(@NonNull Poem poem);
    }

    private final List<Poem> data = new ArrayList<>();
    private final Listener listener;

    public PoemRowAdapter(@NonNull Listener listener) {
        this.listener = listener;
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
            binding.rowMeta.setText(poem.getAuthorLabel() + " · "
                    + Dynasty.labelOf(poem.getDynasty()) + " · " + poem.getLineCount() + " 句");

            binding.getRoot().setOnClickListener(v -> listener.onPoemClick(poem));
            binding.rowRemove.setOnClickListener(v -> listener.onRemoveClick(poem));
        }
    }
}

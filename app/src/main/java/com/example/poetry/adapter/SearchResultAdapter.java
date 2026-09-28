package com.example.poetry.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.poetry.data.model.Poem;
import com.example.poetry.databinding.ItemSearchResultBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索结果适配器（顶部搜索面板下拉列表）。
 */
public class SearchResultAdapter extends RecyclerView.Adapter<SearchResultAdapter.Holder> {

    public interface Listener {
        void onResultClick(@NonNull Poem poem);
    }

    private final List<Poem> data = new ArrayList<>();
    private final Listener listener;

    public SearchResultAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void submit(@NonNull List<Poem> poems) {
        data.clear();
        data.addAll(poems);
        notifyDataSetChanged();
    }

    /** 详情页要拿着整份搜索结果翻页，见 {@code DetailActivity#openInList} */
    @NonNull
    public List<Poem> items() {
        return data;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemSearchResultBinding binding = ItemSearchResultBinding.inflate(
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

        private final ItemSearchResultBinding binding;

        Holder(@NonNull ItemSearchResultBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Poem poem) {
            binding.resultTitle.setText(poem.getTitle());
            binding.resultSub.setText(poem.getAuthorLabel() + " · "
                    + poem.getExcerpt().replace("\n", " "));
            binding.resultMeta.setText(poem.getKind().getLabel());
            binding.getRoot().setOnClickListener(v -> listener.onResultClick(poem));
            binding.getRoot().setContentDescription(poem.getTitle());
        }
    }
}

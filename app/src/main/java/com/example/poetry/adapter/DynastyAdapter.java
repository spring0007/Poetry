package com.example.poetry.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.poetry.R;
import com.example.poetry.data.model.Category;
import com.example.poetry.databinding.ItemDynastyBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 朝代宫格适配器（分类页「按朝代」）。
 */
public class DynastyAdapter extends RecyclerView.Adapter<DynastyAdapter.Holder> {

    public interface Listener {
        void onDynastyClick(@NonNull Category category);
    }

    private final List<Category> data = new ArrayList<>();
    private final Listener listener;

    public DynastyAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void submit(@NonNull List<Category> categories) {
        data.clear();
        data.addAll(categories);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemDynastyBinding binding = ItemDynastyBinding.inflate(
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

        private final ItemDynastyBinding binding;

        Holder(@NonNull ItemDynastyBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Category category) {
            binding.dynastyName.setText(category.getName());
            binding.dynastyCount.setText(itemView.getContext().getString(R.string.count_poems,
                    category.getCount()));
            binding.getRoot().setOnClickListener(v -> listener.onDynastyClick(category));
            binding.getRoot().setContentDescription(category.getName());
        }
    }
}

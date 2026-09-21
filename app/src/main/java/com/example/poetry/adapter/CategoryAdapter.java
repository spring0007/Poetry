package com.example.poetry.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.poetry.data.model.Category;
import com.example.poetry.databinding.ItemCategoryTypeBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 体裁大卡适配器（分类页「按体裁」）。
 * <p>
 * 对应设计稿：柔和底色 + 巨型水印字 + 篇数。
 */
public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.Holder> {

    public interface Listener {
        void onCategoryClick(@NonNull Category category);
    }

    private final List<Category> data = new ArrayList<>();
    private final Listener listener;

    public CategoryAdapter(@NonNull Listener listener) {
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
        ItemCategoryTypeBinding binding = ItemCategoryTypeBinding.inflate(
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

        private final ItemCategoryTypeBinding binding;

        Holder(@NonNull ItemCategoryTypeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Category category) {
            binding.typeName.setText(category.getName());
            binding.typeDesc.setText(category.getDesc());
            binding.typeCount.setText(String.valueOf(category.getCount()));

            if (category.getColorRes() != 0) {
                int color = ContextCompat.getColor(itemView.getContext(), category.getColorRes());
                binding.typeRoot.setCardBackgroundColor(
                        ContextCompat.getColor(itemView.getContext(), category.getLightColorRes()));
                binding.typeName.setTextColor(color);
                binding.typeGo.setTextColor(color);
                // 巨型水印字：同色低透明度
                binding.typeMark.setText(category.getMark());
                binding.typeMark.setTextColor(Color.argb(46, Color.red(color),
                        Color.green(color), Color.blue(color)));
            }
            binding.getRoot().setOnClickListener(v -> listener.onCategoryClick(category));
            binding.getRoot().setContentDescription(category.getName() + "，" + category.getCount() + " 篇");
        }
    }
}

package com.example.poetry.adapter;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.poetry.R;
import com.example.poetry.data.model.Author;
import com.example.poetry.databinding.ItemAuthorBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 热门作者适配器（分类页横向列表）。
 */
public class AuthorAdapter extends RecyclerView.Adapter<AuthorAdapter.Holder> {

    public interface Listener {
        void onAuthorClick(@NonNull Author author);
    }

    /** 头像配色轮换，对应设计稿的柔和色块 */
    private static final int[] AVATAR_COLORS = {
            R.color.zhuhong_100, R.color.daizi_100, R.color.shiqing_100,
            R.color.zhulv_100, R.color.tenghuang_100, R.color.minge_100
    };

    private final List<Author> data = new ArrayList<>();
    private final Listener listener;

    public AuthorAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void submit(@NonNull List<Author> authors) {
        data.clear();
        data.addAll(authors);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAuthorBinding binding = ItemAuthorBinding.inflate(
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

        private final ItemAuthorBinding binding;

        Holder(@NonNull ItemAuthorBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Author author, int position) {
            binding.authorName.setText(author.getName());
            binding.authorCount.setText(itemView.getContext().getString(R.string.count_poems,
                    author.getNPoems()));
            binding.authorAvatar.setText(author.getInitial());

            int tint = ContextCompat.getColor(itemView.getContext(),
                    AVATAR_COLORS[position % AVATAR_COLORS.length]);
            ViewCompat.setBackgroundTintList(binding.authorAvatar, ColorStateList.valueOf(tint));
            binding.authorAvatar.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    R.color.ink_700));

            binding.getRoot().setOnClickListener(v -> listener.onAuthorClick(author));
            binding.getRoot().setContentDescription(author.getLabel());
        }
    }
}

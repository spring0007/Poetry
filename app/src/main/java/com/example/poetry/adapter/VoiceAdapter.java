package com.example.poetry.adapter;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.poetry.R;
import com.example.poetry.data.model.Voice;
import com.example.poetry.databinding.ItemVoiceBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 音色列表适配器（详情页底部弹层）。
 */
public class VoiceAdapter extends RecyclerView.Adapter<VoiceAdapter.Holder> {

    public interface Listener {
        void onVoiceClick(@NonNull Voice voice);

        void onTryClick(@NonNull Voice voice);
    }

    private static final int[] AVATAR_COLORS = {
            R.color.zhuhong_100, R.color.daizi_100, R.color.shiqing_100,
            R.color.zhulv_100, R.color.tenghuang_100
    };

    private final List<Voice> data = new ArrayList<>();
    private final Listener listener;

    public VoiceAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void submit(@NonNull List<Voice> voices) {
        data.clear();
        data.addAll(voices);
        notifyDataSetChanged();
    }

    @NonNull
    public List<Voice> items() {
        return data;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemVoiceBinding binding = ItemVoiceBinding.inflate(
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

        private final ItemVoiceBinding binding;

        Holder(@NonNull ItemVoiceBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Voice voice, int position) {
            binding.voiceName.setText(voice.getName());
            binding.voiceDesc.setText(voice.getDesc());
            binding.voiceTag1.setText(voice.getTag1());
            binding.voiceTag2.setText(voice.getTag2());
            binding.voiceAvatar.setText(voice.getInitial());
            ViewCompat.setBackgroundTintList(binding.voiceAvatar, ColorStateList.valueOf(
                    ContextCompat.getColor(itemView.getContext(),
                            AVATAR_COLORS[position % AVATAR_COLORS.length])));
            binding.voiceAvatar.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    R.color.ink_700));
            binding.voiceCheck.setVisibility(voice.isSelected() ? android.view.View.VISIBLE
                    : android.view.View.INVISIBLE);

            binding.getRoot().setOnClickListener(v -> listener.onVoiceClick(voice));
            binding.voiceTry.setOnClickListener(v -> listener.onTryClick(voice));
        }
    }
}

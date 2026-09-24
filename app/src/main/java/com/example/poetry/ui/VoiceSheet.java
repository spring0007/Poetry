package com.example.poetry.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.poetry.R;
import com.example.poetry.VoiceSettingsActivity;
import com.example.poetry.adapter.VoiceAdapter;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.Voice;
import com.example.poetry.databinding.DialogVoiceSheetBinding;
import com.example.poetry.media.Speaker;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

/**
 * 音色选择底部弹层（详情页 / 我的页共用）。
 * <p>
 * 音色由内置的离线引擎给出，正常情况下是十几个挑了出来的发音人。
 * 语音包没加载成功时这里是空的，弹层不会假装有音色——底下那行说明会讲清楚
 * 为什么没有（见 {@code voice_sheet_single} / {@code voice_sheet_empty}）。
 */
public final class VoiceSheet {

    public interface Callback {
        /** 选中音色 */
        void onSelected(@NonNull Voice voice);

        /** 试听 */
        void onPreview(@NonNull Voice voice);
    }

    private VoiceSheet() {
    }

    public static void show(@NonNull Context context, @NonNull Callback callback) {
        DialogVoiceSheetBinding binding =
                DialogVoiceSheetBinding.inflate(LayoutInflater.from(context));
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        dialog.setContentView(binding.getRoot());

        UserStore store = UserStore.get(context);
        String selectedId = store.getVoiceId();

        List<Voice> voices = Speaker.get().listVoices();
        boolean hasSaved = false;
        for (Voice voice : voices) {
            if (voice.getId().equals(selectedId)) {
                voice.setSelected(true);
                hasSaved = true;
            }
        }
        if (!hasSaved && !voices.isEmpty()) {
            voices.get(0).setSelected(true);
        }

        binding.voiceSheetSub.setText(context.getString(R.string.voice_sheet_sub, voices.size()));

        VoiceAdapter adapter = new VoiceAdapter(new VoiceAdapter.Listener() {
            @Override
            public void onVoiceClick(@NonNull Voice voice) {
                for (Voice item : voices) {
                    item.setSelected(false);
                }
                voice.setSelected(true);
                store.setVoiceId(voice.getId());
                Speaker.get().setVoice(voice.getId());
                callback.onSelected(voice);
                dialog.dismiss();
            }

            @Override
            public void onTryClick(@NonNull Voice voice) {
                Speaker.get().setVoice(voice.getId());
                callback.onPreview(voice);
            }
        });
        adapter.submit(voices);
        binding.voiceList.setLayoutManager(new LinearLayoutManager(context));
        binding.voiceList.setAdapter(adapter);

        View root = binding.getRoot().findViewById(R.id.voiceHint);
        if (root instanceof TextView) {
            // 只有一种声音（内置离线引擎就是这种情形）和一种都没有，要说的话不一样
            ((TextView) root).setText(voices.isEmpty()
                    ? R.string.voice_sheet_empty : R.string.voice_sheet_single);
        }
        if (root != null) {
            root.setVisibility(voices.size() > 1 ? View.GONE : View.VISIBLE);
        }
        dialog.show();
    }
}

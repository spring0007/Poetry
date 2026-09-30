package com.example.poetry.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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
 * 音色由内置的离线引擎给出，正常情况下是十几个挑了出来的发音人；腾讯云在线音色会追加在
 * 列表后面。没开通会员时，云端音色仍然列出来但标成「锁定」——点它不会真的选中，而是弹一个
 * 说明（见 {@link #showMemberRequired}），把付费能力挡在门外。
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

        // 打勾由 Speaker 按「实际会发声的那个」标好了（见 Speaker#listVoices），这里别再用存下来
        // 的音色 id 去改：没开通会员时存着的是被锁住的云端音色，那个勾既画不出来（锁着的那行
        // 不画勾），也不是真正在发声的音色——照它标就等于把整张表的勾都弄没了。
        List<Voice> voices = Speaker.get().listVoices();

        binding.voiceSheetSub.setText(context.getString(R.string.voice_sheet_sub, voices.size()));

        VoiceAdapter adapter = new VoiceAdapter(new VoiceAdapter.Listener() {
            @Override
            public void onVoiceClick(@NonNull Voice voice) {
                if (voice.isLocked()) {
                    showMemberRequired(context);
                    return;
                }
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
                if (voice.isLocked()) {
                    showMemberRequired(context);
                    return;
                }
                Speaker.get().setVoice(voice.getId());
                callback.onPreview(voice);
            }
        });
        adapter.submit(voices);
        binding.voiceList.setLayoutManager(new LinearLayoutManager(context));
        binding.voiceList.setAdapter(adapter);

        // 弹层里只放了「选一个」这一件事；语速、音调、音量、引擎状态在语音设置页。
        // 先收弹层再开页面，否则它留着，用户从设置页返回时还盖在详情页上。
        binding.voiceSettingsButton.setOnClickListener(v -> {
            dialog.dismiss();
            VoiceSettingsActivity.open(context);
        });

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

    /** 选了/试听了被锁的云端音色：说明一下会员限制，不真正选它。 */
    private static void showMemberRequired(@NonNull Context context) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.voice_member_title)
                .setMessage(R.string.voice_member_msg)
                .setPositiveButton(R.string.voice_member_positive, null)
                .show();
    }
}

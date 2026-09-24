package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.GridLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.example.poetry.data.local.UserStore;
import com.example.poetry.databinding.ActivitySkinBinding;
import com.example.poetry.databinding.ItemSkinBinding;
import com.example.poetry.ui.Skin;

/**
 * 皮肤主题页：五套配色任选，选中后整个 App 的纸底、卡片与强调色一起变。
 *
 * 主题只能在 Activity 创建前设置，所以 {@link Skin#apply} 必须排在
 * {@code super.onCreate()} 之前；切换走 {@link Skin#select} 重建页面。
 */
public class SkinActivity extends AppCompatActivity {

    private ActivitySkinBinding binding;
    private UserStore store;

    public static void open(@Nullable Context context) {
        if (context != null) {
            context.startActivity(new Intent(context, SkinActivity.class));
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivitySkinBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        store = UserStore.get(this);

        binding.backButton.setOnClickListener(v -> finish());
        buildCards();
    }

    private void buildCards() {
        String current = store.getSkinId();
        binding.skinGrid.removeAllViews();
        int gap = getResources().getDimensionPixelSize(R.dimen.space_2);

        for (String id : Skin.ids()) {
            ItemSkinBinding item = ItemSkinBinding.inflate(getLayoutInflater(),
                    binding.skinGrid, false);
            int[] palette = Skin.paletteOf(id);
            boolean selected = id.equals(current);

            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(getResources().getDimension(R.dimen.radius_md));
            background.setColor(ContextCompat.getColor(this, palette[0]));
            background.setStroke(selected ? dp(2) : dp(1),
                    ContextCompat.getColor(this, selected ? palette[2] : R.color.line));
            item.getRoot().setBackground(background);

            tint(item.dotPrimary, palette[2]);
            tint(item.dotAccent, palette[3]);

            item.skinName.setText(Skin.nameOf(id));
            item.skinDesc.setText(Skin.descOf(id));
            item.skinTag.setText(selected ? R.string.skin_in_use : R.string.skin_apply);
            item.skinTag.setTextColor(ContextCompat.getColor(this,
                    selected ? palette[2] : R.color.ink_400));
            item.getRoot().setOnClickListener(v -> {
                if (!id.equals(store.getSkinId())) {
                    Skin.select(this, id);
                }
            });

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(gap, gap, gap, gap);
            binding.skinGrid.addView(item.getRoot(), lp);
        }
    }

    private void tint(android.view.View dot, int color) {
        ViewCompat.setBackgroundTintList(dot,
                ColorStateList.valueOf(ContextCompat.getColor(this, color)));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

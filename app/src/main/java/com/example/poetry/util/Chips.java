package com.example.poetry.util;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.example.poetry.R;
import com.google.android.material.chip.Chip;

/**
 * 统一创建筛选 Chip（体裁 / 主题 / 排序）。
 * <p>
 * 颜色一律取 res/color 下的 selector，避免在 Java 里硬编码色值。
 */
public final class Chips {

    private Chips() {
    }

    /**
     * @param ghost true = 弱化的「幽灵 chip」（主题标签用），false = 常规可勾选 chip
     */
    @NonNull
    public static Chip create(@NonNull Context context, @NonNull String text, boolean ghost) {
        Chip chip = new Chip(context);
        chip.setText(text);
        chip.setTextAppearance(R.style.TextAppearance_Poetry_Caption);
        chip.setCheckable(true);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipMinHeight(32f);
        chip.setChipCornerRadius(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 999f, context.getResources().getDisplayMetrics()));

        ColorStateList background = ContextCompat.getColorStateList(context,
                ghost ? R.color.chip_ghost_bg_selector : R.color.chip_bg_selector);
        ColorStateList foreground = ContextCompat.getColorStateList(context,
                ghost ? R.color.chip_ghost_text_selector : R.color.chip_text_selector);
        ColorStateList stroke = ContextCompat.getColorStateList(context, R.color.chip_stroke_selector);

        chip.setChipBackgroundColor(background);
        chip.setTextColor(foreground);
        chip.setChipStrokeColor(stroke);
        chip.setChipStrokeWidth(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 1f, context.getResources().getDisplayMetrics()));
        chip.setRippleColor(ContextCompat.getColorStateList(context, R.color.chip_stroke_selector));
        return chip;
    }
}

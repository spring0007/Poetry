package com.example.poetry.adapter;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.example.poetry.data.model.PoemKind;

/**
 * 体裁配色的查表缓存（主色 + 浅底色）。
 *
 * <p>三处适配器（卡片 / 行卡 / 热度榜）绑每一条都要这两色，原来都是当场
 * {@code ContextCompat.getColor} 再 {@code ColorStateList.valueOf} —— 滚动时一秒上百次
 * 资源查找。体裁是枚举、主题在一次运行里固定，查一次记下来就够。
 *
 * <p>{@code 0} 当「还没查过」的哨兵：体裁色都是实色（A 通道不透明），不会真的取到 0。
 */
public final class KindColors {

    private static final int[] SOLID = new int[PoemKind.values().length];
    private static final int[] LIGHT = new int[PoemKind.values().length];

    private KindColors() {
    }

    /** 体裁主色（色条、徽标文字）。 */
    public static int solid(@NonNull Context context, @NonNull PoemKind kind) {
        return resolve(context, kind, false);
    }

    /** 体裁浅底色（徽标底、水印）。 */
    public static int light(@NonNull Context context, @NonNull PoemKind kind) {
        return resolve(context, kind, true);
    }

    private static synchronized int resolve(@NonNull Context context, @NonNull PoemKind kind,
                                            boolean light) {
        int index = kind.ordinal();
        int[] cache = light ? LIGHT : SOLID;
        int cached = cache[index];
        if (cached != 0) {
            return cached;
        }
        int resolved = ContextCompat.getColor(context,
                light ? kind.getLightColorRes() : kind.getColorRes());
        cache[index] = resolved;
        return resolved;
    }
}

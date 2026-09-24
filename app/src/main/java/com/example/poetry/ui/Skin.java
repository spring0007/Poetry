package com.example.poetry.ui;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.R;
import com.example.poetry.data.local.UserStore;

/**
 * 皮肤（主题配色）管理。
 *
 * 一套皮肤就是一组「纸底 / 卡片 / 主色 / 强调 / 描边」颜色，定义在
 * {@code values/colors.xml}（日间）与 {@code values-night/colors.xml}（夜间），
 * 同名取用，所以切换日夜不需要额外处理；主题样式见 {@code values/themes.xml}。
 *
 * 颜色是通过主题属性（?attr/colorSurface 等）传给界面与 shape 的，因此皮肤只能
 * 在 Activity 创建之前设置——必须在 {@code super.onCreate()} 之前调用
 * {@link #apply(Activity)}，切换后 {@link #select(Activity, String)} 会重建页面。
 */
public final class Skin {

    /** 宣纸：暖白纸 + 朱红（默认） */
    public static final String XUAN = "xuan";
    /** 青瓷：青白纸 + 石青 */
    public static final String QINGCI = "qingci";
    /** 朱砂：米黄纸 + 深朱 */
    public static final String ZHUSHA = "zhusha";
    /** 黛蓝：冷灰蓝纸 + 黛蓝 */
    public static final String DAILAN = "dailan";
    /** 墨韵：素白纸 + 墨黑（极简） */
    public static final String MOYU = "moyu";

    public static final String DEFAULT = XUAN;

    private static final String[] IDS = {XUAN, QINGCI, ZHUSHA, DAILAN, MOYU};

    private Skin() {
    }

    /** 全部皮肤 id，顺序即展示顺序 */
    @NonNull
    public static String[] ids() {
        return IDS;
    }

    /** 皮肤 id 对应的主题样式 */
    public static int themeOf(@Nullable String id) {
        if (QINGCI.equals(id)) {
            return R.style.Theme_Poetry_Qingci;
        }
        if (ZHUSHA.equals(id)) {
            return R.style.Theme_Poetry_Zhusha;
        }
        if (DAILAN.equals(id)) {
            return R.style.Theme_Poetry_Dailan;
        }
        if (MOYU.equals(id)) {
            return R.style.Theme_Poetry_Moyu;
        }
        return R.style.Theme_Poetry_Xuan;
    }

    /** 皮肤名称 */
    public static int nameOf(@Nullable String id) {
        if (QINGCI.equals(id)) {
            return R.string.skin_qingci_name;
        }
        if (ZHUSHA.equals(id)) {
            return R.string.skin_zhusha_name;
        }
        if (DAILAN.equals(id)) {
            return R.string.skin_dailan_name;
        }
        if (MOYU.equals(id)) {
            return R.string.skin_moyu_name;
        }
        return R.string.skin_xuan_name;
    }

    /** 皮肤一句话说明 */
    public static int descOf(@Nullable String id) {
        if (QINGCI.equals(id)) {
            return R.string.skin_qingci_desc;
        }
        if (ZHUSHA.equals(id)) {
            return R.string.skin_zhusha_desc;
        }
        if (DAILAN.equals(id)) {
            return R.string.skin_dailan_desc;
        }
        if (MOYU.equals(id)) {
            return R.string.skin_moyu_desc;
        }
        return R.string.skin_xuan_desc;
    }

    /** 预览色：纸底、卡片、主色、强调色，顺序固定 */
    @NonNull
    public static int[] paletteOf(@Nullable String id) {
        if (QINGCI.equals(id)) {
            return new int[]{R.color.skin_qingci_paper, R.color.skin_qingci_surface,
                    R.color.skin_qingci_primary, R.color.skin_qingci_accent};
        }
        if (ZHUSHA.equals(id)) {
            return new int[]{R.color.skin_zhusha_paper, R.color.skin_zhusha_surface,
                    R.color.skin_zhusha_primary, R.color.skin_zhusha_accent};
        }
        if (DAILAN.equals(id)) {
            return new int[]{R.color.skin_dailan_paper, R.color.skin_dailan_surface,
                    R.color.skin_dailan_primary, R.color.skin_dailan_accent};
        }
        if (MOYU.equals(id)) {
            return new int[]{R.color.skin_moyu_paper, R.color.skin_moyu_surface,
                    R.color.skin_moyu_primary, R.color.skin_moyu_accent};
        }
        return new int[]{R.color.skin_xuan_paper, R.color.skin_xuan_surface,
                R.color.skin_xuan_primary, R.color.skin_xuan_accent};
    }

    /** 在 {@code super.onCreate()} 之前调用，把已选皮肤套到当前页 */
    public static void apply(@NonNull Activity activity) {
        activity.setTheme(themeOf(UserStore.get(activity).getSkinId()));
    }

    /** 记住选择并重建页面，让新皮肤立即生效 */
    public static void select(@NonNull Activity activity, @NonNull String id) {
        UserStore.get(activity).setSkinId(id);
        activity.recreate();
    }
}

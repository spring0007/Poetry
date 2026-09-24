package com.example.poetry.util;

import androidx.annotation.NonNull;

import com.google.android.material.slider.Slider;

/**
 * 步进滑杆（{@code android:stepSize != 0}）的取值收敛。
 * <p>
 * 语速滑杆在 Material 1.12.0 上会存出「不在自己栅格上」的值，然后在下一次布局时把页面
 * 判死。两个独立的坑叠在一起：
 *
 * <ol>
 * <li><b>校验是延迟的。</b>{@link Slider#setValue(float)} 只把值塞进去，不校验；真正的校验
 * 拖到首次布局（{@code onSizeChanged → updateTrackWidth → maybeCalculateTicksCoordinates →
 * validateConfigurationIfDirty}）才做，不在栅格上就抛
 * {@code IllegalStateException: Value(x) must be equal to valueFrom(y) plus a multiple of
 * stepSize(z)}。于是崩点跟写值的那行代码隔着好几层布局回调。</li>
 *
 * <li><b>拖拽算出的值本身就偏出栅格</b>（库的 bug）。{@code BaseSlider#snapPosition} 是
 * {@code int stepCount = (int)((valueTo - valueFrom) / stepSize)}，{@code (1.8f - 0.6f) / 0.1f}
 * 在 float 下是 11.999999，截断成 <b>11</b> 而不是 12。于是拖到第 4 个刻度得到的是
 * {@code 0.6 + 1.2 × 3/11 = 0.92727274}——库自己都不认的值（校验是十进制字符串算术，容差
 * 1e-4 步），存进配置后这个滑杆下次布局必崩。</li>
 * </ol>
 *
 * <p><b>中招的只有语速。</b>把 Material 1.12.0 的字节码拿到 JVM 上跑真正的
 * {@code validateValues()}（见下表），语速那 12 个拖拽取值里有 10 个会被库自己拒掉；
 * 音调 {@code (1.5f - 0.5f) / 0.1f} 在 float 下正好是 {@code 10f}、音量 {@code 1.0f / 0.05f}
 * 是 20，两者都截对了，一个都没被拒。但它们照样走这条收敛——统一的路径比逐杆判断可靠，
 * 而且滑杆的区间以后会改。
 * <pre>
 *   slider              stepCount   拖拽取值中被库拒绝的
 *   语速 0.6~1.8/0.1     11 (应 12)        10 / 12
 *   音调 0.5~1.5/0.1     10 (应 10)         0 / 11
 *   音量 0.0~1.0/0.05    20 (应 20)         0 / 21
 * </pre>
 *
 * 所以两个方向都要收：<b>读配置写进滑杆</b>用 {@link #snap}，<b>用户拖出来的值</b>用
 * {@link #snapAndPushBack}。
 * <p>
 * 栅格的权威是滑杆自己的 {@code valueFrom / valueTo / stepSize}（写在布局里），这里不写死
 * 0.1 之类的常量，换了 XML 就跟着换。
 */
public final class Sliders {

    /**
     * 判定「已经落在栅格上」的容差，单位是**步**。
     * <p>
     * 必须与 Material 的判定一致（{@code BaseSlider.isMultipleOfStepSize} 用 1e-4），**不能更大**：
     * 放过的值最终要交给它校验，容差比它宽就等于把崩溃放回去。真实的偏差只有两种：拖拽刚算出来的
     * 0 或 ≥ 1/110 ≈ 0.009 步（11 格栅格与 10 格栅格最接近时的距离），而这里的浮点误差在 1e-6 步
     * 量级，中间隔着两个数量级。
     */
    private static final float TOLERANCE_STEPS = 1e-4f;

    private Sliders() {
    }

    /** 把 {@code value} 收敛到滑杆自己的区间与步进栅格上；{@code NaN} 落到 {@code valueFrom} */
    public static float snap(@NonNull Slider slider, float value) {
        float from = slider.getValueFrom();
        float to = slider.getValueTo();
        if (Float.isNaN(value)) {
            return from;
        }
        float clamped = Math.min(to, Math.max(from, value));
        float step = slider.getStepSize();
        if (step <= 0f) {
            return clamped;
        }
        float steps = (clamped - from) / step;
        float nearest = Math.round(steps);
        if (Math.abs(steps - nearest) <= TOLERANCE_STEPS) {
            // 已经在栅格上：原样返回。别改写成 from + n*step —— 0.9f 会被算成 0.90000001，
            // 于是「值没变」被判成变了，白白写一次盘
            return clamped;
        }
        // 先夹再对齐、最后再夹一次：valueTo 未必落在栅格上，对齐有可能把它顶出去
        float snapped = from + nearest * step;
        return Math.min(to, Math.max(from, snapped));
    }

    /**
     * 用户拖出来的值：{@link #snap} 之后如果确实偏了，把它推回滑杆。
     * <p>
     * 推回去是**必须**的，不只是为了显示：偏值留在滑杆里，这个页面下一次尺寸变化
     * （转屏、返回键回到本页、Fragment 视图重建）就会在 {@code onSizeChanged} 里校验并抛异常。
     * 推回去会再触发一次程序化回调，但那次的值已经在栅格上，走到 {@code snap} 就原样返回，
     * 不会递归。
     *
     * @return 收敛后的值，调用方直接拿它写配置、下发给引擎
     */
    public static float snapAndPushBack(@NonNull Slider slider, float value) {
        float snapped = snap(slider, value);
        if (snapped != value) {
            slider.setValue(snapped);
        }
        return snapped;
    }
}

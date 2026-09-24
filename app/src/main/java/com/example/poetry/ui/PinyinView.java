package com.example.poetry.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.util.Pinyin;

import java.util.ArrayList;
import java.util.List;

/**
 * 带拼音注音的诗词正文。
 * <p>
 * 横排时拼音在汉字正上方（传统「上下排」注音）；竖排时拼音在汉字右侧，
 * 一句一列、自右向左——与中文古籍的竖排注音一致。
 * <p>
 * 查不到拼音的字（标点、生僻字）只画汉字，不会破坏排版。
 */
public class PinyinView extends View {

    public static final int ORIENT_HORIZONTAL = 0;
    public static final int ORIENT_VERTICAL = 1;

    private static final float PINYIN_RATIO = 0.42f;
    /** 汉字与拼音之间的空隙，按字号比例给，保证放大字体时比例不变 */
    private static final float PY_GAP_RATIO = 0.12f;

    private String text = "";
    private int orientation = ORIENT_HORIZONTAL;
    private float charSize = 24f;
    private float cellPad = 6f;
    private Typeface typeface = Typeface.SERIF;

    private final Paint charPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 横排：一行一组的字；竖排：一列一组的字 */
    private final List<List<Cell>> groups = new ArrayList<>();
    private float contentWidth;
    private float contentHeight;
    private float lastAvailWidth;

    private static final class Cell {
        final char ch;
        final String py;
        float pyWidth;

        Cell(char ch, String py) {
            this.ch = ch;
            this.py = py;
        }
    }

    public PinyinView(@NonNull Context context) {
        super(context);
        init();
    }

    public PinyinView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PinyinView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        cellPad = 4f * density;
        charPaint.setTextAlign(Paint.Align.CENTER);
        pyPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** 正文，按 \n 分行 */
    public void setText(@Nullable String text) {
        this.text = text == null ? "" : text;
        relayout();
    }

    public void setOrientation(int orientation) {
        if (this.orientation == orientation) {
            return;
        }
        this.orientation = orientation;
        relayout();
    }

    /** 汉字字号，单位 px */
    public void setCharSize(float px) {
        if (Math.abs(this.charSize - px) < 0.5f) {
            return;
        }
        this.charSize = px;
        relayout();
    }

    public void setTypeface(@Nullable Typeface typeface) {
        this.typeface = typeface == null ? Typeface.SERIF : typeface;
        relayout();
    }

    public void setTextColor(int color) {
        charPaint.setColor(color);
        invalidate();
    }

    public void setPinyinColor(int color) {
        pyPaint.setColor(color);
        invalidate();
    }

    private void relayout() {
        groups.clear();
        requestLayout();
        invalidate();
    }

    // ------------------------------------------------------------------ 布局

    private float pinyinSize() {
        return charSize * PINYIN_RATIO;
    }

    private void build(float availWidth) {
        groups.clear();
        charPaint.setTextSize(charSize);
        charPaint.setTypeface(typeface);
        pyPaint.setTextSize(pinyinSize());

        String[] lines = text.split("\n");
        for (String line : lines) {
            List<Cell> cells = cellsOf(line);
            if (cells.isEmpty()) {
                continue;
            }
            if (orientation == ORIENT_HORIZONTAL) {
                // 一行放不下就按字折行，保证居中后不会溢出屏幕
                List<Cell> row = new ArrayList<>();
                float rowWidth = 0f;
                for (Cell cell : cells) {
                    float w = cellWidth(cell);
                    if (!row.isEmpty() && rowWidth + w > availWidth) {
                        groups.add(row);
                        row = new ArrayList<>();
                        rowWidth = 0f;
                    }
                    row.add(cell);
                    rowWidth += w;
                }
                if (!row.isEmpty()) {
                    groups.add(row);
                }
            } else {
                groups.add(cells);
            }
        }

        // 量出内容尺寸
        contentWidth = 0f;
        contentHeight = 0f;
        if (orientation == ORIENT_HORIZONTAL) {
            float rowHeight = pinyinBox() + charBox() + rowGap();
            for (List<Cell> row : groups) {
                float w = 0f;
                for (Cell cell : row) {
                    w += cellWidth(cell);
                }
                contentWidth = Math.max(contentWidth, w);
                contentHeight += rowHeight;
            }
            if (!groups.isEmpty()) {
                contentHeight -= rowGap();
            }
        } else {
            for (List<Cell> column : groups) {
                contentWidth += columnWidth(column) + columnGap();
                contentHeight = Math.max(contentHeight, column.size() * verticalCell());
            }
            if (!groups.isEmpty()) {
                contentWidth -= columnGap();
            }
        }
    }

    @NonNull
    private List<Cell> cellsOf(@NonNull String line) {
        List<Cell> cells = new ArrayList<>(line.length());
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\r') {
                continue;
            }
            String py = Pinyin.of(ch);
            Cell cell = new Cell(ch, py);
            cell.pyWidth = py.isEmpty() ? 0f : pyPaint.measureText(py);
            cells.add(cell);
        }
        return cells;
    }

    /** 横排单字占宽：汉字与拼音取较宽者 */
    private float cellWidth(@NonNull Cell cell) {
        return Math.max(charSize, cell.pyWidth) + cellPad;
    }

    /** 竖排单列占宽：汉字列 + 右侧拼音列 */
    private float columnWidth(@NonNull List<Cell> column) {
        float maxPy = 0f;
        for (Cell cell : column) {
            maxPy = Math.max(maxPy, cell.pyWidth);
        }
        float width = charSize + cellPad;
        if (maxPy > 0f) {
            width += charSize * PY_GAP_RATIO + maxPy;
        }
        return width;
    }

    private float pinyinBox() {
        return pinyinSize() * 1.5f;
    }

    private float charBox() {
        return charSize * 1.22f;
    }

    private float rowGap() {
        return charSize * 0.34f;
    }

    private float verticalCell() {
        return charSize * 1.42f;
    }

    private float columnGap() {
        return charSize * 0.55f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        float avail = widthMode == MeasureSpec.UNSPECIFIED
                ? 10000f
                : widthSize - getPaddingLeft() - getPaddingRight();
        avail = Math.max(avail, charSize * 2f);
        lastAvailWidth = avail;
        build(avail);

        // 竖排时列数一多就会超出屏宽，这里按内容实宽上报，让外层横向滚动容器能滚
        int measuredWidth = widthMode == MeasureSpec.EXACTLY
                ? Math.max(widthSize, (int) Math.ceil(contentWidth)
                + getPaddingLeft() + getPaddingRight())
                : (int) Math.ceil(contentWidth) + getPaddingLeft() + getPaddingRight();
        int measuredHeight = (int) Math.ceil(contentHeight)
                + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (groups.isEmpty()) {
            return;
        }
        float avail = getWidth() - getPaddingLeft() - getPaddingRight();
        if (lastAvailWidth <= 0f || Math.abs(lastAvailWidth - avail) > 1f) {
            build(Math.max(avail, charSize * 2f));
        }
        if (orientation == ORIENT_HORIZONTAL) {
            drawHorizontal(canvas, avail);
        } else {
            drawVertical(canvas, avail);
        }
    }

    private void drawHorizontal(@NonNull Canvas canvas, float avail) {
        Paint.FontMetrics charMetrics = charPaint.getFontMetrics();
        Paint.FontMetrics pyMetrics = pyPaint.getFontMetrics();
        float pyBox = pinyinBox();
        float chBox = charBox();
        float rowHeight = pyBox + chBox + rowGap();

        float y = getPaddingTop();
        for (List<Cell> row : groups) {
            float rowWidth = 0f;
            for (Cell cell : row) {
                rowWidth += cellWidth(cell);
            }
            // 竖屏内容居中：整行在可用宽度里居中
            float x = getPaddingLeft() + Math.max(0f, (avail - rowWidth) / 2f);
            for (Cell cell : row) {
                float w = cellWidth(cell);
                float centerX = x + w / 2f;
                if (!cell.py.isEmpty()) {
                    float baseline = y + pyBox / 2f - (pyMetrics.ascent + pyMetrics.descent) / 2f;
                    canvas.drawText(cell.py, centerX, baseline, pyPaint);
                }
                float charTop = y + pyBox;
                float baseline = charTop + chBox / 2f
                        - (charMetrics.ascent + charMetrics.descent) / 2f;
                canvas.drawText(String.valueOf(cell.ch), centerX, baseline, charPaint);
                x += w;
            }
            y += rowHeight;
        }
    }

    private void drawVertical(@NonNull Canvas canvas, float avail) {
        Paint.FontMetrics charMetrics = charPaint.getFontMetrics();
        Paint.FontMetrics pyMetrics = pyPaint.getFontMetrics();
        float cellH = verticalCell();
        float pyGap = charSize * PY_GAP_RATIO;

        // 自右向左：第一行在最右侧
        float right = getPaddingLeft() + Math.min(avail, contentWidth);
        float cursor = right;
        for (List<Cell> column : groups) {
            float colWidth = columnWidth(column);
            float left = cursor - colWidth;
            float maxPy = 0f;
            for (Cell cell : column) {
                maxPy = Math.max(maxPy, cell.pyWidth);
            }
            float charCenterX = left + cellPad / 2f + charSize / 2f;
            float pyCenterX = left + cellPad / 2f + charSize + pyGap + maxPy / 2f;

            float y = getPaddingTop();
            for (Cell cell : column) {
                float baseline = y + cellH / 2f
                        - (charMetrics.ascent + charMetrics.descent) / 2f;
                canvas.drawText(String.valueOf(cell.ch), charCenterX, baseline, charPaint);
                if (!cell.py.isEmpty()) {
                    float pyBaseline = y + cellH / 2f
                            - (pyMetrics.ascent + pyMetrics.descent) / 2f;
                    canvas.drawText(cell.py, pyCenterX, pyBaseline, pyPaint);
                }
                y += cellH;
            }
            cursor = left - columnGap();
        }
    }
}

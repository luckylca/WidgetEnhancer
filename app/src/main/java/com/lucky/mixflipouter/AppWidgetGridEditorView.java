package com.lucky.mixflipouter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Jigsaw-style editor for hosted AppWidget slots: renders the 2 x 3 outer-screen
 * grid and lets the user drag slots freely between cells. A tap opens the slot
 * action dialog through the callback.
 */
final class AppWidgetGridEditorView extends View {
    interface Callback {
        String slotLabel(WidgetComponent slot);
        void onSlotTap(WidgetComponent slot);
        void onSlotsChanged();
    }

    private static final float CELL_GAP = 6f;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int touchSlop;
    private WidgetConfig config;
    private Callback callback;
    private WidgetConfig dragConfig;
    private WidgetComponent dragging;
    private float dragCanvasX;
    private float dragCanvasY;
    private float grabOffsetX;
    private float grabOffsetY;
    private float downRawX;
    private float downRawY;
    private boolean moved;

    AppWidgetGridEditorView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        android.graphics.drawable.GradientDrawable backdrop =
                new android.graphics.drawable.GradientDrawable();
        backdrop.setColor(0xFF17181D);
        float radius = 18 * getResources().getDisplayMetrics().density;
        backdrop.setCornerRadius(radius);
        setBackground(backdrop);
        int pad = Math.round(getResources().getDisplayMetrics().density * 6);
        setPadding(pad, pad, pad, pad);
        gridPaint.setColor(0x2EFFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        slotPaint.setColor(0x59FFFFFF);
        strokePaint.setColor(0x8CFFFFFF);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2f);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    void setConfig(WidgetConfig value) {
        config = value;
        dragging = null;
        invalidate();
    }

    void setCallback(Callback value) {
        callback = value;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width * WidgetConfig.CANVAS_HEIGHT / WidgetConfig.CANVAS_WIDTH);
        setMeasuredDimension(width, height);
    }

    private float cellWidth() {
        return getWidth() / (float) AppWidgetLayoutEngine.GRID_COLS;
    }

    private float cellHeight() {
        return getHeight() / (float) AppWidgetLayoutEngine.GRID_ROWS;
    }

    private float canvasScaleX() {
        return getWidth() / WidgetConfig.CANVAS_WIDTH;
    }

    private float canvasScaleY() {
        return getHeight() / WidgetConfig.CANVAS_HEIGHT;
    }

    private List<WidgetComponent> slots() {
        ArrayList<WidgetComponent> out = new ArrayList<>();
        if (config != null) {
            for (WidgetComponent component : config.components) {
                if (WidgetComponent.TYPE_APPWIDGET.equals(component.type)) out.add(component);
            }
        }
        return out;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int c = 1; c < AppWidgetLayoutEngine.GRID_COLS; c++) {
            float x = c * cellWidth();
            canvas.drawLine(x, 0, x, getHeight(), gridPaint);
        }
        for (int r = 1; r < AppWidgetLayoutEngine.GRID_ROWS; r++) {
            float y = r * cellHeight();
            canvas.drawLine(0, y, getWidth(), y, gridPaint);
        }
        List<WidgetComponent> slots = slots();
        if (slots.isEmpty()) {
            textPaint.setTextSize(getHeight() * 0.045f);
            canvas.drawText("点下方按钮添加小部件", getWidth() / 2f, getHeight() / 2f, textPaint);
            return;
        }
        for (WidgetComponent slot : slots) {
            if (slot == dragging) continue;
            drawSlot(canvas, slot, slot.x * canvasScaleX(), slot.y * canvasScaleY(),
                    slot.width * canvasScaleX(), slot.height * canvasScaleY(), false);
        }
        if (dragging != null) {
            int[] size = AppWidgetLayoutEngine.parseSize(dragging.content);
            float w = (size == null ? 1 : size[0]) * cellWidth() - CELL_GAP * canvasScaleX() * 2;
            float h = (size == null ? 1 : size[1]) * cellHeight() - CELL_GAP * canvasScaleY() * 2;
            drawSlot(canvas, dragging, dragCanvasX * canvasScaleX(),
                    dragCanvasY * canvasScaleY(), w, h, true);
        }
    }

    private void drawSlot(Canvas canvas, WidgetComponent slot, float left, float top,
                          float width, float height, boolean raised) {
        RectF rect = new RectF(left, top, left + width, top + height);
        slotPaint.setColor(raised ? 0x8FFFFFFF : (slot.visible ? 0x59FFFFFF : 0x33FFFFFF));
        float radius = Math.min(width, height) * 0.12f;
        canvas.drawRoundRect(rect, radius, radius, slotPaint);
        canvas.drawRoundRect(rect, radius, radius, strokePaint);
        if (callback != null) {
            float cx = rect.centerX();
            float cy = rect.centerY();
            textPaint.setTextSize(Math.max(12f, Math.min(width, height) * 0.16f));
            canvas.drawText(callback.slotLabel(slot), cx, cy, textPaint);
            textPaint.setTextSize(Math.max(10f, Math.min(width, height) * 0.13f));
            int[] size = AppWidgetLayoutEngine.parseSize(slot.content);
            String sizeText = (size == null ? "?" : size[0] + " × " + size[1])
                    + (slot.visible ? "" : " · 无空位");
            canvas.drawText(sizeText, cx, cy + textPaint.getTextSize() * 1.5f, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (config == null) return super.onTouchEvent(event);
        int action = event.getActionMasked();
        float canvasX = event.getX() / canvasScaleX();
        float canvasY = event.getY() / canvasScaleY();
        if (action == MotionEvent.ACTION_DOWN) {
            dragging = hitSlot(canvasX, canvasY);
            moved = false;
            downRawX = event.getX();
            downRawY = event.getY();
            if (dragging != null) {
                grabOffsetX = canvasX - dragging.x;
                grabOffsetY = canvasY - dragging.y;
                dragCanvasX = dragging.x;
                dragCanvasY = dragging.y;
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
            }
            return dragging != null || super.onTouchEvent(event);
        }
        if (dragging == null) return super.onTouchEvent(event);
        if (action == MotionEvent.ACTION_MOVE) {
            if (!moved && (Math.abs(event.getX() - downRawX) > touchSlop
                    || Math.abs(event.getY() - downRawY) > touchSlop)) {
                moved = true;
            }
            if (moved) {
                dragCanvasX = clamp(canvasX - grabOffsetX, 0,
                        WidgetConfig.CANVAS_WIDTH - dragging.width);
                dragCanvasY = clamp(canvasY - grabOffsetY, 0,
                        WidgetConfig.CANVAS_HEIGHT - dragging.height);
                invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            WidgetComponent slot = dragging;
            dragging = null;
            getParent().requestDisallowInterceptTouchEvent(false);
            if (action == MotionEvent.ACTION_UP && slot != null) {
                if (!moved) {
                    if (callback != null) callback.onSlotTap(slot);
                } else {
                    dropSlot(slot);
                }
            }
            invalidate();
            return true;
        }
        return true;
    }

    private void dropSlot(WidgetComponent slot) {
        int[] size = AppWidgetLayoutEngine.parseSize(slot.content);
        if (size == null) size = new int[]{1, 1};
        float cellW = WidgetConfig.CANVAS_WIDTH / AppWidgetLayoutEngine.GRID_COLS;
        float cellH = WidgetConfig.CANVAS_HEIGHT / AppWidgetLayoutEngine.GRID_ROWS;
        int col = Math.round(dragCanvasX / cellW);
        int row = Math.round(dragCanvasY / cellH);
        col = Math.max(0, Math.min(AppWidgetLayoutEngine.GRID_COLS - size[0], col));
        row = Math.max(0, Math.min(AppWidgetLayoutEngine.GRID_ROWS - size[1], row));
        boolean[][] occupied = new boolean[AppWidgetLayoutEngine.GRID_ROWS]
                [AppWidgetLayoutEngine.GRID_COLS];
        for (WidgetComponent other : slots()) {
            if (other == slot || !other.visible) continue;
            int[] otherSize = AppWidgetLayoutEngine.parseSize(other.content);
            if (otherSize == null) continue;
            int otherCol = Math.round(other.x / cellW);
            int otherRow = Math.round(other.y / cellH);
            if (AppWidgetLayoutEngine.areaFree(occupied, otherCol, otherRow,
                    otherSize[0], otherSize[1])) {
                AppWidgetLayoutEngine.markArea(occupied, otherCol, otherRow,
                        otherSize[0], otherSize[1]);
            }
        }
        if (AppWidgetLayoutEngine.areaFree(occupied, col, row, size[0], size[1])) {
            slot.x = col * cellW + CELL_GAP;
            slot.y = row * cellH + CELL_GAP;
            if (callback != null) callback.onSlotsChanged();
        }
    }

    private WidgetComponent hitSlot(float canvasX, float canvasY) {
        List<WidgetComponent> slots = slots();
        for (int i = slots.size() - 1; i >= 0; i--) {
            WidgetComponent slot = slots.get(i);
            if (canvasX >= slot.x && canvasX <= slot.x + slot.width
                    && canvasY >= slot.y && canvasY <= slot.y + slot.height) {
                return slot;
            }
        }
        return null;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

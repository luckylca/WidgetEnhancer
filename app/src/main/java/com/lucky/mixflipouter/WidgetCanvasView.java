package com.lucky.mixflipouter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Lightweight editor surface using WidgetConfig's platform-neutral 440 x 720 canvas. */
final class WidgetCanvasView extends View {
    interface Listener {
        void onSelectionChanged(WidgetComponent component);
        void onComponentChanged(WidgetComponent component);
    }

    private static final float HANDLE_SIZE = 30f;
    private static final float MIN_SIZE = 40f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF canvasBounds = new RectF();
    private WidgetConfig config;
    private Bitmap mediaPreview;
    private WidgetComponent selected;
    private Listener listener;
    private float lastX;
    private float lastY;
    private boolean resizing;
    private boolean moved;

    WidgetCanvasView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(dp(2));
        selectionPaint.setColor(0xFFFF8A00);
        setFocusable(true);
        setClickable(true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setWidget(WidgetConfig config, Bitmap mediaPreview) {
        this.config = config;
        this.mediaPreview = mediaPreview;
        selected = null;
        invalidate();
        notifySelection();
    }

    void setMediaPreview(Bitmap mediaPreview) {
        this.mediaPreview = mediaPreview;
        invalidate();
    }

    WidgetComponent getSelected() {
        return selected;
    }

    void addComponent(WidgetComponent component) {
        if (config == null) return;
        component.zIndex = nextZIndex();
        config.components.add(component);
        selected = component;
        invalidate();
        notifyChanged();
        notifySelection();
    }

    void duplicateSelected() {
        if (config == null || selected == null) return;
        WidgetComponent copy = selected.copy();
        copy.x = Math.min(WidgetConfig.CANVAS_WIDTH - copy.width, copy.x + 18);
        copy.y = Math.min(WidgetConfig.CANVAS_HEIGHT - copy.height, copy.y + 18);
        copy.zIndex = nextZIndex();
        config.components.add(copy);
        selected = copy;
        invalidate();
        notifyChanged();
        notifySelection();
    }

    void deleteSelected() {
        if (config == null || selected == null) return;
        config.components.remove(selected);
        selected = null;
        invalidate();
        notifyChanged();
        notifySelection();
    }

    void moveLayer(int direction) {
        if (config == null || selected == null || direction == 0) return;
        ArrayList<WidgetComponent> ordered = orderedComponents();
        int index = ordered.indexOf(selected);
        int target = Math.max(0, Math.min(ordered.size() - 1, index + direction));
        if (target == index) return;
        WidgetComponent other = ordered.get(target);
        int old = selected.zIndex;
        selected.zIndex = other.zIndex;
        other.zIndex = old;
        if (selected.zIndex == other.zIndex) {
            selected.zIndex += direction;
        }
        normalizeZIndices();
        invalidate();
        notifyChanged();
    }

    void toggleSelectedLock() {
        if (selected == null) return;
        selected.locked = !selected.locked;
        invalidate();
        notifyChanged();
        notifySelection();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvasBounds.set(0, 0, getWidth(), getHeight());
        float outerRadius = dp(24);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        canvas.drawRoundRect(canvasBounds, outerRadius, outerRadius, paint);
        if (config == null) return;

        int save = canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(canvasBounds, outerRadius, outerRadius, Path.Direction.CW);
        canvas.clipPath(clipPath);
        for (WidgetComponent component : orderedComponents()) {
            if (component.visible) drawComponent(canvas, component);
        }
        canvas.restoreToCount(save);

        if (selected != null && selected.visible) drawSelection(canvas, selected);
    }

    private void drawComponent(Canvas canvas, WidgetComponent component) {
        RectF rect = physicalRect(component);
        paint.setAlpha(Math.round(component.opacity * 255));
        if (WidgetComponent.TYPE_IMAGE.equals(component.type)
                || WidgetComponent.TYPE_VIDEO.equals(component.type)) {
            drawMedia(canvas, rect, component);
        } else if (WidgetComponent.TYPE_BUTTON.equals(component.type)) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xCC252525);
            canvas.drawRoundRect(rect, scaled(component.cornerRadius),
                    scaled(component.cornerRadius), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0x66FFFFFF);
            canvas.drawRoundRect(rect, scaled(component.cornerRadius),
                    scaled(component.cornerRadius), paint);
            drawText(canvas, rect, component, component.content.isEmpty() ? "按钮" : component.content);
        } else {
            String value = component.content;
            if (WidgetComponent.TYPE_TIME.equals(component.type)) {
                String pattern = value.isEmpty() ? "HH:mm" : value;
                try {
                    value = new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date());
                } catch (IllegalArgumentException ignored) {
                    value = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                }
            } else if (WidgetComponent.TYPE_SONG_TITLE.equals(component.type)) {
                value = value.isEmpty() ? "歌曲名称" : value;
            } else if (WidgetComponent.TYPE_ARTIST.equals(component.type)) {
                value = value.isEmpty() ? "歌手" : value;
            }
            drawText(canvas, rect, component, value.isEmpty() ? "文本" : value);
        }
        paint.setAlpha(255);
    }

    private void drawMedia(Canvas canvas, RectF destination, WidgetComponent component) {
        if (mediaPreview == null || mediaPreview.isRecycled()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF363636);
            canvas.drawRoundRect(destination, scaled(component.cornerRadius),
                    scaled(component.cornerRadius), paint);
            drawText(canvas, destination, component,
                    WidgetComponent.TYPE_VIDEO.equals(component.type) ? "▶ 视频" : "图片");
            return;
        }
        int save = canvas.save();
        if (component.cornerRadius > 0) {
            Path mediaClip = new Path();
            mediaClip.addRoundRect(destination, scaled(component.cornerRadius),
                    scaled(component.cornerRadius), Path.Direction.CW);
            canvas.clipPath(mediaClip);
        } else {
            canvas.clipRect(destination);
        }
        Rect source = new Rect(0, 0, mediaPreview.getWidth(), mediaPreview.getHeight());
        RectF target = mediaTarget(new RectF(source), destination, component.fillMode);
        canvas.drawBitmap(mediaPreview, source, target, paint);
        canvas.restoreToCount(save);
    }

    private RectF mediaTarget(RectF source, RectF destination, String fillMode) {
        if ("stretch".equals(fillMode)) return new RectF(destination);
        float sourceAspect = source.width() / source.height();
        float targetAspect = destination.width() / destination.height();
        boolean contain = "contain".equals(fillMode);
        float width;
        float height;
        if ((sourceAspect > targetAspect) == contain) {
            width = destination.width();
            height = width / sourceAspect;
        } else {
            height = destination.height();
            width = height * sourceAspect;
        }
        float centerX = destination.centerX();
        float centerY = destination.centerY();
        return new RectF(centerX - width / 2, centerY - height / 2,
                centerX + width / 2, centerY + height / 2);
    }

    private void drawText(Canvas canvas, RectF rect, WidgetComponent component, String value) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(parseColor(component.color, Color.WHITE));
        paint.setTextSize(Math.max(dp(8), scaled(component.textSize)));
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(value, rect.centerX(), baseline, paint);
    }

    private void drawSelection(Canvas canvas, WidgetComponent component) {
        RectF rect = physicalRect(component);
        selectionPaint.setPathEffect(component.locked
                ? new android.graphics.DashPathEffect(new float[]{dp(5), dp(4)}, 0) : null);
        canvas.drawRect(rect, selectionPaint);
        if (!component.locked) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFF8A00);
            float handle = scaled(HANDLE_SIZE);
            canvas.drawCircle(rect.right, rect.bottom, handle / 2, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (config == null) return false;
        float logicalX = event.getX() * WidgetConfig.CANVAS_WIDTH / Math.max(1, getWidth());
        float logicalY = event.getY() * WidgetConfig.CANVAS_HEIGHT / Math.max(1, getHeight());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                WidgetComponent hit = findTopmost(logicalX, logicalY);
                if (hit != selected) {
                    selected = hit;
                    notifySelection();
                    invalidate();
                }
                moved = false;
                resizing = selected != null && !selected.locked
                        && Math.abs(logicalX - (selected.x + selected.width)) <= HANDLE_SIZE
                        && Math.abs(logicalY - (selected.y + selected.height)) <= HANDLE_SIZE;
                lastX = logicalX;
                lastY = logicalY;
                if (selected != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (selected == null || selected.locked) return true;
                float deltaX = logicalX - lastX;
                float deltaY = logicalY - lastY;
                if (Math.abs(deltaX) + Math.abs(deltaY) > 0.2f) moved = true;
                if (resizing) {
                    selected.width = clamp(selected.width + deltaX, MIN_SIZE,
                            WidgetConfig.CANVAS_WIDTH - selected.x);
                    selected.height = clamp(selected.height + deltaY, MIN_SIZE,
                            WidgetConfig.CANVAS_HEIGHT - selected.y);
                } else {
                    selected.x = clamp(selected.x + deltaX, 0,
                            WidgetConfig.CANVAS_WIDTH - selected.width);
                    selected.y = clamp(selected.y + deltaY, 0,
                            WidgetConfig.CANVAS_HEIGHT - selected.height);
                }
                lastX = logicalX;
                lastY = logicalY;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                if (moved) notifyChanged();
                resizing = false;
                moved = false;
                return true;
            default:
                return true;
        }
    }

    private WidgetComponent findTopmost(float x, float y) {
        ArrayList<WidgetComponent> ordered = orderedComponents();
        for (int i = ordered.size() - 1; i >= 0; i--) {
            WidgetComponent component = ordered.get(i);
            if (!component.visible) continue;
            if (x >= component.x && x <= component.x + component.width
                    && y >= component.y && y <= component.y + component.height) return component;
        }
        return null;
    }

    private RectF physicalRect(WidgetComponent component) {
        float scaleX = getWidth() / WidgetConfig.CANVAS_WIDTH;
        float scaleY = getHeight() / WidgetConfig.CANVAS_HEIGHT;
        return new RectF(component.x * scaleX, component.y * scaleY,
                (component.x + component.width) * scaleX,
                (component.y + component.height) * scaleY);
    }

    private ArrayList<WidgetComponent> orderedComponents() {
        ArrayList<WidgetComponent> ordered = new ArrayList<>();
        if (config != null) ordered.addAll(config.components);
        ordered.sort(Comparator.comparingInt(component -> component.zIndex));
        return ordered;
    }

    private int nextZIndex() {
        int max = -1;
        for (WidgetComponent component : config.components) max = Math.max(max, component.zIndex);
        return max + 1;
    }

    private void normalizeZIndices() {
        List<WidgetComponent> ordered = orderedComponents();
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).zIndex = i;
    }

    private float scaled(float logical) {
        return logical * Math.min(getWidth() / WidgetConfig.CANVAS_WIDTH,
                getHeight() / WidgetConfig.CANVAS_HEIGHT);
    }

    private void notifySelection() {
        if (listener != null) listener.onSelectionChanged(selected);
    }

    private void notifyChanged() {
        if (listener != null && selected != null) listener.onComponentChanged(selected);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int parseColor(String value, int fallback) {
        try {
            return Color.parseColor(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

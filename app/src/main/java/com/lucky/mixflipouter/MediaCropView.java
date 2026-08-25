package com.lucky.mixflipouter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Fixed outer-screen crop frame with direct pan and pinch-to-zoom editing. */
final class MediaCropView extends View {
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cropFrame = new RectF();
    private final Path shadePath = new Path();
    private final ScaleGestureDetector scaleDetector;
    private WidgetComponent component;
    private Bitmap bitmap;
    private float lastX;
    private float lastY;
    private boolean dragging;

    MediaCropView(Context context) {
        super(context);
        setBackgroundColor(0xFF101115);
        setClickable(true);
        shadePaint.setColor(0xB8000000);
        framePaint.setColor(Color.WHITE);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(2));
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (component == null) return false;
                        component.mediaScale = clamp(component.mediaScale * detector.getScaleFactor(),
                                1, MediaTransform.MAX_USER_SCALE);
                        clampOffsets();
                        changed();
                        return true;
                    }
                });
    }

    void setComponent(WidgetComponent component) {
        this.component = component;
        clampOffsets();
        invalidate();
    }

    void setBitmap(Bitmap bitmap) {
        if (this.bitmap != null && this.bitmap != bitmap && !this.bitmap.isRecycled()) {
            this.bitmap.recycle();
        }
        this.bitmap = bitmap;
        clampOffsets();
        invalidate();
    }

    void setMediaRotation(int rotation) {
        if (component == null) return;
        component.mediaRotation = rotation == 90 ? 90 : 0;
        clampOffsets();
        changed();
    }

    void resetTransform() {
        if (component == null) return;
        component.mediaScale = 1f;
        component.mediaOffsetX = 0;
        component.mediaOffsetY = 0;
        changed();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float horizontalMargin = dp(24);
        float verticalMargin = dp(18);
        float availableWidth = Math.max(1, width - horizontalMargin * 2);
        float availableHeight = Math.max(1, height - verticalMargin * 2);
        float frameWidth = Math.min(availableWidth,
                availableHeight * WidgetConfig.CANVAS_WIDTH / WidgetConfig.CANVAS_HEIGHT);
        float frameHeight = frameWidth * WidgetConfig.CANVAS_HEIGHT / WidgetConfig.CANVAS_WIDTH;
        cropFrame.set((width - frameWidth) / 2f, (height - frameHeight) / 2f,
                (width + frameWidth) / 2f, (height + frameHeight) / 2f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null && !bitmap.isRecycled() && component != null) {
            canvas.drawBitmap(bitmap, MediaTransform.bitmapMatrix(
                    bitmap.getWidth(), bitmap.getHeight(), cropFrame, component), bitmapPaint);
        } else {
            Paint empty = new Paint(Paint.ANTI_ALIAS_FLAG);
            empty.setColor(0xFF34363D);
            canvas.drawRect(cropFrame, empty);
            empty.setTextAlign(Paint.Align.CENTER);
            empty.setTextSize(dp(15));
            empty.setColor(0xFFB7B8BE);
            canvas.drawText("请选择图片或视频", cropFrame.centerX(), cropFrame.centerY(), empty);
        }

        shadePath.reset();
        shadePath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        shadePath.addRect(cropFrame, Path.Direction.CCW);
        canvas.drawPath(shadePath, shadePaint);
        canvas.drawRect(cropFrame, framePaint);

        framePaint.setColor(0x66FFFFFF);
        framePaint.setStrokeWidth(dp(1));
        canvas.drawLine(cropFrame.left + cropFrame.width() / 3f, cropFrame.top,
                cropFrame.left + cropFrame.width() / 3f, cropFrame.bottom, framePaint);
        canvas.drawLine(cropFrame.left + cropFrame.width() * 2f / 3f, cropFrame.top,
                cropFrame.left + cropFrame.width() * 2f / 3f, cropFrame.bottom, framePaint);
        canvas.drawLine(cropFrame.left, cropFrame.top + cropFrame.height() / 3f,
                cropFrame.right, cropFrame.top + cropFrame.height() / 3f, framePaint);
        canvas.drawLine(cropFrame.left, cropFrame.top + cropFrame.height() * 2f / 3f,
                cropFrame.right, cropFrame.top + cropFrame.height() * 2f / 3f, framePaint);
        framePaint.setColor(Color.WHITE);
        framePaint.setStrokeWidth(dp(2));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null || component == null) return super.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!cropFrame.contains(event.getX(), event.getY())) return false;
                dragging = true;
                lastX = event.getX();
                lastY = event.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && dragging && !scaleDetector.isInProgress()) {
                    float x = event.getX();
                    float y = event.getY();
                    panBy(x - lastX, y - lastY);
                    lastX = x;
                    lastY = y;
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                dragging = false;
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                // fall through
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void panBy(float deltaX, float deltaY) {
        MediaTransform.Spec spec = spec();
        if (spec == null) return;
        component.mediaOffsetX = spec.maxPanX > 0
                ? clamp(component.mediaOffsetX + deltaX / spec.maxPanX, -1, 1) : 0;
        component.mediaOffsetY = spec.maxPanY > 0
                ? clamp(component.mediaOffsetY + deltaY / spec.maxPanY, -1, 1) : 0;
        changed();
    }

    private void clampOffsets() {
        if (component == null) return;
        component.mediaScale = clamp(component.mediaScale, 1, MediaTransform.MAX_USER_SCALE);
        component.mediaOffsetX = clamp(component.mediaOffsetX, -1, 1);
        component.mediaOffsetY = clamp(component.mediaOffsetY, -1, 1);
    }

    private MediaTransform.Spec spec() {
        if (bitmap == null || bitmap.isRecycled() || cropFrame.isEmpty() || component == null) {
            return null;
        }
        return MediaTransform.calculate(bitmap.getWidth(), bitmap.getHeight(),
                cropFrame.width(), cropFrame.height(), component.mediaRotation,
                component.mediaScale, component.mediaOffsetX, component.mediaOffsetY);
    }

    private void changed() {
        invalidate();
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}

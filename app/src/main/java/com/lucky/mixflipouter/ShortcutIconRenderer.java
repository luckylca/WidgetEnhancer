package com.lucky.mixflipouter;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Loads app artwork and draws resource-independent system-action icons. */
final class ShortcutIconRenderer {
    static Drawable loadAppIcon(Context context, WidgetComponent component) {
        if (!ActionSpec.LAUNCH_APP.equals(component.actionType)) return null;
        String value = component.actionValue == null ? "" : component.actionValue.trim();
        if (value.isEmpty()) return null;
        PackageManager packageManager = context.getPackageManager();
        try {
            ComponentName target = value.contains("/")
                    ? ComponentName.unflattenFromString(value) : null;
            if (target != null) {
                return packageManager.getActivityInfo(target, 0).loadIcon(packageManager);
            }
            return packageManager.getApplicationIcon(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void drawSystemIcon(Canvas canvas, String actionType,
                               float left, float top, float size) {
        int save = canvas.save();
        canvas.translate(left, top);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xD926282D);
        canvas.drawRoundRect(0, 0, size, size, size * 0.24f, size * 0.24f, fill);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.WHITE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(Math.max(2f, size * 0.055f));

        if (ActionSpec.LOCK_SCREEN.equals(actionType)) {
            drawLock(canvas, stroke, size);
        } else if (ActionSpec.isMediaControl(actionType)) {
            drawMedia(canvas, stroke, size, actionType);
        } else if (ActionSpec.VOLUME_UP.equals(actionType)
                || ActionSpec.VOLUME_DOWN.equals(actionType)
                || ActionSpec.MUTE_TOGGLE.equals(actionType)) {
            drawVolume(canvas, stroke, size, actionType);
        } else if (ActionSpec.isFlashlight(actionType)) {
            drawFlashlight(canvas, stroke, size);
        } else if (ActionSpec.DO_NOT_DISTURB_TOGGLE.equals(actionType)) {
            canvas.drawCircle(size * 0.5f, size * 0.5f, size * 0.24f, stroke);
            canvas.drawLine(size * 0.36f, size * 0.5f,
                    size * 0.64f, size * 0.5f, stroke);
        } else if (ActionSpec.AUTO_ROTATE_TOGGLE.equals(actionType)) {
            drawRotate(canvas, stroke, size);
        } else {
            drawGrid(canvas, fill, size);
        }
        canvas.restoreToCount(save);
    }

    private static void drawLock(Canvas canvas, Paint paint, float size) {
        RectF body = new RectF(size * 0.31f, size * 0.45f,
                size * 0.69f, size * 0.73f);
        canvas.drawRoundRect(body, size * 0.055f, size * 0.055f, paint);
        RectF shackle = new RectF(size * 0.38f, size * 0.26f,
                size * 0.62f, size * 0.54f);
        canvas.drawArc(shackle, 180, 180, false, paint);
    }

    private static void drawMedia(Canvas canvas, Paint paint, float size, String actionType) {
        Path path = new Path();
        if (ActionSpec.MEDIA_PLAY_PAUSE.equals(actionType)) {
            path.moveTo(size * 0.41f, size * 0.34f);
            path.lineTo(size * 0.68f, size * 0.5f);
            path.lineTo(size * 0.41f, size * 0.66f);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(path, paint);
            return;
        }
        boolean previous = ActionSpec.MEDIA_PREVIOUS.equals(actionType);
        float direction = previous ? -1f : 1f;
        float center = size * 0.5f;
        path.moveTo(center + direction * size * 0.15f, size * 0.34f);
        path.lineTo(center - direction * size * 0.12f, center);
        path.lineTo(center + direction * size * 0.15f, size * 0.66f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
        float barX = center - direction * size * 0.19f;
        canvas.drawRoundRect(barX - size * 0.025f, size * 0.34f,
                barX + size * 0.025f, size * 0.66f, size * 0.02f, size * 0.02f, paint);
    }

    private static void drawVolume(Canvas canvas, Paint paint, float size, String actionType) {
        Path speaker = new Path();
        speaker.moveTo(size * 0.29f, size * 0.44f);
        speaker.lineTo(size * 0.40f, size * 0.44f);
        speaker.lineTo(size * 0.54f, size * 0.33f);
        speaker.lineTo(size * 0.54f, size * 0.67f);
        speaker.lineTo(size * 0.40f, size * 0.56f);
        speaker.lineTo(size * 0.29f, size * 0.56f);
        speaker.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(speaker, paint);
        paint.setStyle(Paint.Style.STROKE);
        float x = size * 0.70f;
        canvas.drawLine(x - size * 0.07f, size * 0.5f,
                x + size * 0.07f, size * 0.5f, paint);
        if (ActionSpec.VOLUME_UP.equals(actionType)) {
            canvas.drawLine(x, size * 0.43f, x, size * 0.57f, paint);
        } else if (ActionSpec.MUTE_TOGGLE.equals(actionType)) {
            canvas.drawLine(x - size * 0.06f, size * 0.43f,
                    x + size * 0.06f, size * 0.57f, paint);
            canvas.drawLine(x + size * 0.06f, size * 0.43f,
                    x - size * 0.06f, size * 0.57f, paint);
        }
    }

    private static void drawFlashlight(Canvas canvas, Paint paint, float size) {
        Path path = new Path();
        path.moveTo(size * 0.36f, size * 0.30f);
        path.lineTo(size * 0.64f, size * 0.30f);
        path.lineTo(size * 0.59f, size * 0.43f);
        path.lineTo(size * 0.56f, size * 0.70f);
        path.lineTo(size * 0.44f, size * 0.70f);
        path.lineTo(size * 0.41f, size * 0.43f);
        path.close();
        canvas.drawPath(path, paint);
        canvas.drawLine(size * 0.41f, size * 0.43f,
                size * 0.59f, size * 0.43f, paint);
    }

    private static void drawRotate(Canvas canvas, Paint paint, float size) {
        RectF arc = new RectF(size * 0.29f, size * 0.29f,
                size * 0.71f, size * 0.71f);
        canvas.drawArc(arc, 35, 255, false, paint);
        Path arrow = new Path();
        arrow.moveTo(size * 0.67f, size * 0.27f);
        arrow.lineTo(size * 0.72f, size * 0.42f);
        arrow.lineTo(size * 0.57f, size * 0.37f);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(arrow, paint);
    }

    private static void drawGrid(Canvas canvas, Paint paint, float size) {
        paint.setColor(Color.WHITE);
        float cell = size * 0.13f;
        float gap = size * 0.09f;
        float start = (size - cell * 2 - gap) / 2f;
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 2; column++) {
                float x = start + column * (cell + gap);
                float y = start + row * (cell + gap);
                canvas.drawRoundRect(x, y, x + cell, y + cell,
                        cell * 0.25f, cell * 0.25f, paint);
            }
        }
    }

    private ShortcutIconRenderer() {}
}

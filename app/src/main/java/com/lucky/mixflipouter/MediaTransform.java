package com.lucky.mixflipouter;

import android.graphics.Matrix;
import android.graphics.RectF;

/** Shared cover-crop geometry for editor, previews, images and video textures. */
final class MediaTransform {
    static final float MAX_USER_SCALE = 6f;

    private MediaTransform() {
    }

    static Spec calculate(float sourceWidth, float sourceHeight,
                          float destinationWidth, float destinationHeight,
                          int rotation, float userScale, float offsetX, float offsetY) {
        int safeRotation = rotation == 90 ? 90 : 0;
        float safeSourceWidth = Math.max(1, sourceWidth);
        float safeSourceHeight = Math.max(1, sourceHeight);
        float safeDestinationWidth = Math.max(1, destinationWidth);
        float safeDestinationHeight = Math.max(1, destinationHeight);
        float rotatedWidth = safeRotation == 90 ? safeSourceHeight : safeSourceWidth;
        float rotatedHeight = safeRotation == 90 ? safeSourceWidth : safeSourceHeight;
        float zoom = finiteClamp(userScale, 1, MAX_USER_SCALE, 1);
        float scale = Math.max(safeDestinationWidth / rotatedWidth,
                safeDestinationHeight / rotatedHeight) * zoom;
        float scaledWidth = rotatedWidth * scale;
        float scaledHeight = rotatedHeight * scale;
        float maxPanX = Math.max(0, (scaledWidth - safeDestinationWidth) / 2f);
        float maxPanY = Math.max(0, (scaledHeight - safeDestinationHeight) / 2f);
        return new Spec(safeRotation, scale, scaledWidth, scaledHeight,
                maxPanX, maxPanY,
                finiteClamp(offsetX, -1, 1, 0) * maxPanX,
                finiteClamp(offsetY, -1, 1, 0) * maxPanY);
    }

    static Matrix bitmapMatrix(float sourceWidth, float sourceHeight,
                               RectF destination, WidgetComponent component) {
        Spec spec = calculate(sourceWidth, sourceHeight, destination.width(), destination.height(),
                component.mediaRotation, component.mediaScale,
                component.mediaOffsetX, component.mediaOffsetY);
        return mappingMatrix(sourceWidth, sourceHeight, destination, spec);
    }

    static Matrix textureMatrix(float sourceWidth, float sourceHeight,
                                float viewWidth, float viewHeight,
                                WidgetComponent component) {
        RectF destination = new RectF(0, 0, viewWidth, viewHeight);
        Spec spec = calculate(sourceWidth, sourceHeight, viewWidth, viewHeight,
                component.mediaRotation, component.mediaScale,
                component.mediaOffsetX, component.mediaOffsetY);
        return mappingMatrix(viewWidth, viewHeight, destination, spec);
    }

    private static Matrix mappingMatrix(float inputWidth, float inputHeight,
                                        RectF destination, Spec spec) {
        float centerX = destination.centerX() + spec.panX;
        float centerY = destination.centerY() + spec.panY;
        float left = centerX - spec.scaledWidth / 2f;
        float top = centerY - spec.scaledHeight / 2f;
        float right = centerX + spec.scaledWidth / 2f;
        float bottom = centerY + spec.scaledHeight / 2f;
        float[] source = {0, 0, inputWidth, 0, inputWidth, inputHeight, 0, inputHeight};
        float[] target;
        if (spec.rotation == 90) {
            target = new float[]{right, top, right, bottom, left, bottom, left, top};
        } else {
            target = new float[]{left, top, right, top, right, bottom, left, bottom};
        }
        Matrix matrix = new Matrix();
        matrix.setPolyToPoly(source, 0, target, 0, 4);
        return matrix;
    }

    private static float finiteClamp(float value, float min, float max, float fallback) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    static final class Spec {
        final int rotation;
        final float scale;
        final float scaledWidth;
        final float scaledHeight;
        final float maxPanX;
        final float maxPanY;
        final float panX;
        final float panY;

        Spec(int rotation, float scale, float scaledWidth, float scaledHeight,
             float maxPanX, float maxPanY, float panX, float panY) {
            this.rotation = rotation;
            this.scale = scale;
            this.scaledWidth = scaledWidth;
            this.scaledHeight = scaledHeight;
            this.maxPanX = maxPanX;
            this.maxPanY = maxPanY;
            this.panX = panX;
            this.panY = panY;
        }
    }
}

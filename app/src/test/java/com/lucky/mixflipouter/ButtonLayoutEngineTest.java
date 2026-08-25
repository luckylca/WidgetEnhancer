package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ButtonLayoutEngineTest {
    private static final float EPSILON = 0.001f;

    @Test
    public void allSixTemplatesUseTheSpecifiedNormalizedCenters() {
        assertCenters(1, 0.50f, 0.50f);
        assertCenters(2, 0.50f, 0.33f, 0.50f, 0.67f);
        assertCenters(3, 0.50f, 0.20f, 0.50f, 0.50f, 0.50f, 0.80f);
        assertCenters(4,
                0.28f, 0.33f, 0.72f, 0.33f,
                0.28f, 0.67f, 0.72f, 0.67f);
        assertCenters(5,
                0.28f, 0.18f, 0.72f, 0.18f,
                0.50f, 0.50f,
                0.28f, 0.82f, 0.72f, 0.82f);
        assertCenters(6,
                0.28f, 0.18f, 0.72f, 0.18f,
                0.28f, 0.50f, 0.72f, 0.50f,
                0.28f, 0.82f, 0.72f, 0.82f);
    }

    @Test
    public void iconsGetSmallerAsButtonCountIncreases() {
        float previous = Float.MAX_VALUE;
        for (int count = 1; count <= ButtonLayoutEngine.MAX_BUTTONS; count++) {
            ButtonLayoutEngine.Layout layout = layout(count);
            assertTrue(layout.iconSize < previous);
            previous = layout.iconSize;
        }
        assertEquals(160f, layout(6).iconSize, EPSILON);
    }

    @Test
    public void visualIconAndTouchTargetAreSeparate() {
        for (int count = 1; count <= ButtonLayoutEngine.MAX_BUTTONS; count++) {
            for (ButtonLayoutEngine.Item item : layout(count).items) {
                assertTrue(item.width > item.iconSize);
                assertTrue(item.height > item.iconSize);
            }
        }
    }

    @Test
    public void templatesScaleWithoutChangingNormalizedCenters() {
        ButtonLayoutEngine.Layout base = layout(6);
        ButtonLayoutEngine.Layout scaled = ButtonLayoutEngine.layout(6, 220f, 360f);
        assertEquals(base.iconSize / 2f, scaled.iconSize, EPSILON);
        for (int index = 0; index < base.items.size(); index++) {
            assertEquals(base.items.get(index).centerX / 2f,
                    scaled.items.get(index).centerX, EPSILON);
            assertEquals(base.items.get(index).centerY / 2f,
                    scaled.items.get(index).centerY, EPSILON);
        }
    }

    private static void assertCenters(int count, float... normalizedCoordinates) {
        ButtonLayoutEngine.Layout layout = layout(count);
        assertEquals(count, layout.items.size());
        for (int index = 0; index < count; index++) {
            ButtonLayoutEngine.Item item = layout.items.get(index);
            assertEquals(normalizedCoordinates[index * 2] * WidgetConfig.CANVAS_WIDTH,
                    item.centerX, EPSILON);
            assertEquals(normalizedCoordinates[index * 2 + 1] * WidgetConfig.CANVAS_HEIGHT,
                    item.centerY, EPSILON);
        }
    }

    private static ButtonLayoutEngine.Layout layout(int count) {
        return ButtonLayoutEngine.layout(
                count, WidgetConfig.CANVAS_WIDTH, WidgetConfig.CANVAS_HEIGHT);
    }
}

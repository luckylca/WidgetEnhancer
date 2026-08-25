package com.lucky.mixflipouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fixed, normalized shortcut layouts for the portrait 2 x 3 widget. */
final class ButtonLayoutEngine {
    static final int MAX_BUTTONS = 6;

    private static final Template[] TEMPLATES = {
            null,
            template(248f, 320f, 320f,
                    0.50f, 0.50f),
            template(210f, 300f, 244f,
                    0.50f, 0.33f,
                    0.50f, 0.67f),
            template(184f, 260f, 216f,
                    0.50f, 0.20f,
                    0.50f, 0.50f,
                    0.50f, 0.80f),
            template(176f, 194f, 244f,
                    0.28f, 0.33f,
                    0.72f, 0.33f,
                    0.28f, 0.67f,
                    0.72f, 0.67f),
            template(164f, 190f, 220f,
                    0.28f, 0.18f,
                    0.72f, 0.18f,
                    0.50f, 0.50f,
                    0.28f, 0.82f,
                    0.72f, 0.82f),
            template(160f, 194f, 230f,
                    0.28f, 0.18f,
                    0.72f, 0.18f,
                    0.28f, 0.50f,
                    0.72f, 0.50f,
                    0.28f, 0.82f,
                    0.72f, 0.82f)
    };

    static Layout layout(int count, float width, float height) {
        if (count < 1 || count > MAX_BUTTONS) {
            throw new IllegalArgumentException("Button count must be between 1 and 6");
        }
        if (!(width > 0f) || !(height > 0f)) {
            throw new IllegalArgumentException("Widget dimensions must be positive");
        }
        Template template = TEMPLATES[count];
        float scale = Math.min(
                width / WidgetConfig.CANVAS_WIDTH,
                height / WidgetConfig.CANVAS_HEIGHT);
        float touchWidth = template.touchWidth * scale;
        float touchHeight = template.touchHeight * scale;
        float iconSize = template.iconSize * scale;
        ArrayList<Item> items = new ArrayList<>(count);
        for (Point point : template.points) {
            float centerX = point.x * width;
            float centerY = point.y * height;
            items.add(new Item(
                    centerX - touchWidth / 2f,
                    centerY - touchHeight / 2f,
                    touchWidth,
                    touchHeight,
                    centerX,
                    centerY,
                    iconSize));
        }
        return new Layout(Collections.unmodifiableList(items), iconSize);
    }

    private static Template template(float iconSize, float touchWidth, float touchHeight,
                                     float... coordinates) {
        ArrayList<Point> points = new ArrayList<>(coordinates.length / 2);
        for (int index = 0; index < coordinates.length; index += 2) {
            points.add(new Point(coordinates[index], coordinates[index + 1]));
        }
        return new Template(Collections.unmodifiableList(points), iconSize, touchWidth, touchHeight);
    }

    static final class Layout {
        final List<Item> items;
        final float iconSize;

        Layout(List<Item> items, float iconSize) {
            this.items = items;
            this.iconSize = iconSize;
        }
    }

    static final class Item {
        final float x;
        final float y;
        final float width;
        final float height;
        final float centerX;
        final float centerY;
        final float iconSize;

        Item(float x, float y, float width, float height,
             float centerX, float centerY, float iconSize) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.centerX = centerX;
            this.centerY = centerY;
            this.iconSize = iconSize;
        }
    }

    private static final class Template {
        final List<Point> points;
        final float iconSize;
        final float touchWidth;
        final float touchHeight;

        Template(List<Point> points, float iconSize, float touchWidth, float touchHeight) {
            this.points = points;
            this.iconSize = iconSize;
            this.touchWidth = touchWidth;
            this.touchHeight = touchHeight;
        }
    }

    private static final class Point {
        final float x;
        final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private ButtonLayoutEngine() {}
}

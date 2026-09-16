package com.lucky.mixflipouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** First-fit packing of hosted AppWidget slots onto the outer-screen 2 x 3 grid. */
final class AppWidgetLayoutEngine {
    static final int GRID_COLS = 2;
    static final int GRID_ROWS = 3;
    static final int[][] SIZE_PRESETS = {
            {1, 1}, {2, 1}, {1, 2}, {2, 2}, {1, 3}, {2, 3}
    };

    static final class Placement {
        final int col;
        final int row;
        final int cols;
        final int rows;
        final boolean fits;

        Placement(int col, int row, int cols, int rows, boolean fits) {
            this.col = col;
            this.row = row;
            this.cols = cols;
            this.rows = rows;
            this.fits = fits;
        }
    }

    static List<Placement> layout(List<int[]> sizes) {
        boolean[][] occupied = new boolean[GRID_ROWS][GRID_COLS];
        ArrayList<Placement> placements = new ArrayList<>();
        if (sizes == null) return Collections.unmodifiableList(placements);
        for (int[] size : sizes) {
            int cols = size == null ? 0 : size[0];
            int rows = size == null ? 0 : size[1];
            if (!validSize(cols, rows)) {
                placements.add(new Placement(0, 0, cols, rows, false));
                continue;
            }
            int[] origin = firstFit(occupied, cols, rows);
            if (origin == null) {
                placements.add(new Placement(0, 0, cols, rows, false));
                continue;
            }
            markArea(occupied, origin[0], origin[1], cols, rows);
            placements.add(new Placement(origin[0], origin[1], cols, rows, true));
        }
        return Collections.unmodifiableList(placements);
    }

    static boolean validSize(int cols, int rows) {
        return cols >= 1 && cols <= GRID_COLS && rows >= 1 && rows <= GRID_ROWS;
    }

    static int[] parseSize(String content) {
        if (content == null) return null;
        String trimmed = content.trim().toLowerCase();
        int split = trimmed.indexOf('x');
        if (split <= 0 || split >= trimmed.length() - 1) return null;
        try {
            int cols = Integer.parseInt(trimmed.substring(0, split).trim());
            int rows = Integer.parseInt(trimmed.substring(split + 1).trim());
            return validSize(cols, rows) ? new int[]{cols, rows} : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static String formatSize(int cols, int rows) {
        return cols + "x" + rows;
    }

    static String sizeLabel(String content) {
        int[] size = parseSize(content);
        return size == null ? "未设置尺寸" : size[0] + " × " + size[1];
    }

    /** Maps a provider's declared minimum pixel size onto the outer-screen 2 x 3 grid. */
    static int[] autoSize(int minWidthPx, int minHeightPx, float density) {
        float safeDensity = Math.max(0.1f, density);
        int cols = (int) Math.ceil(minWidthPx / safeDensity / 220f);
        int rows = (int) Math.ceil(minHeightPx / safeDensity / 240f);
        cols = Math.max(1, Math.min(GRID_COLS, cols));
        rows = Math.max(1, Math.min(GRID_ROWS, rows));
        return new int[]{cols, rows};
    }

    /**
     * Resolves a provider's declared minimum size to dp. Stock Android declares
     * min sizes in dp, but MIUI reports raw pixels; values too large to be
     * plausible dp are converted using the display density.
     */
    static int[] naturalSizeDp(int minWidth, int minHeight, float density,
                               int screenWidthDp, int screenHeightDp) {
        if (minWidth <= 0 || minHeight <= 0) return null;
        boolean pixelLike = minWidth > screenWidthDp || minHeight > screenHeightDp
                || (minWidth > 320 && minHeight > 320);
        if (!pixelLike) return new int[]{minWidth, minHeight};
        float safeDensity = Math.max(0.1f, density);
        return new int[]{Math.round(minWidth / safeDensity),
                Math.round(minHeight / safeDensity)};
    }

    /**
     * Uniform scale for a hosted AppWidget inside a slot, based on the widget's
     * natural width in launcher cells: widgets up to GRID_COLS wide are scaled to
     * fit completely (centered by the caller); wider widgets give each natural
     * cell one grid cell of width, so exactly GRID_COLS of their cells stay
     * visible and the rest is clipped (left-aligned by the caller).
     */
    static float slotScale(float slotWidth, float slotHeight,
                           float naturalWidth, float naturalHeight, int naturalCellsWide) {
        if (slotWidth <= 0 || slotHeight <= 0 || naturalWidth <= 0 || naturalHeight <= 0) {
            return 1f;
        }
        // Every widget scales to fit its slot completely; nothing is clipped.
        return Math.min(slotWidth / naturalWidth, slotHeight / naturalHeight);
    }

    /** Launcher cells wide a widget is designed for; estimated when undeclared. */
    static int naturalCellsWide(int declaredTargetCellWidth, int naturalWidthDp) {
        if (declaredTargetCellWidth > 0) return declaredTargetCellWidth;
        if (naturalWidthDp <= 0) return 1;
        // MIUI launchers use ~80dp columns (verified against 4x1 bar widgets).
        return Math.max(1, Math.round(naturalWidthDp / 80f));
    }

    static float cellWidth(float canvasWidth) {
        return canvasWidth / GRID_COLS;
    }

    static float cellHeight(float canvasHeight) {
        return canvasHeight / GRID_ROWS;
    }

    static int[] firstFit(boolean[][] occupied, int cols, int rows) {
        for (int row = 0; row + rows <= GRID_ROWS; row++) {
            for (int col = 0; col + cols <= GRID_COLS; col++) {
                if (areaFree(occupied, col, row, cols, rows)) return new int[]{col, row};
            }
        }
        return null;
    }

    static boolean areaFree(boolean[][] occupied, int col, int row, int cols, int rows) {
        if (col < 0 || row < 0 || col + cols > GRID_COLS || row + rows > GRID_ROWS) {
            return false;
        }
        for (int r = row; r < row + rows; r++) {
            for (int c = col; c < col + cols; c++) {
                if (occupied[r][c]) return false;
            }
        }
        return true;
    }

    static void markArea(boolean[][] occupied, int col, int row, int cols, int rows) {
        for (int r = row; r < row + rows; r++) {
            for (int c = col; c < col + cols; c++) occupied[r][c] = true;
        }
    }

    private AppWidgetLayoutEngine() {}
}

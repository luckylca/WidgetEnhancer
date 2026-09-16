package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class AppWidgetLayoutEngineTest {
    @Test
    public void slotScaleFitsWidgetsUpToTwoCellsWide() {
        // 2x3 widget in a 2x3 slot: fit completely, nothing clipped.
        assertEquals(1.5f, AppWidgetLayoutEngine.slotScale(300, 450, 200, 300, 2), 0.001f);
        // 1x1 widget in a 2x1 slot: fit, upscaled, centered by the caller.
        assertEquals(4f / 3f, AppWidgetLayoutEngine.slotScale(400, 200, 100, 150, 1), 0.001f);
        // Same aspect at the grid width: exact fit.
        assertEquals(2f, AppWidgetLayoutEngine.slotScale(400, 200, 200, 100, 2), 0.001f);
    }

    @Test
    public void slotScaleFitsWidgetsWiderThanTheGridCompletely() {
        // 4x1 bar in a 2x1 slot: scaled down to fit completely, nothing clipped.
        float scale = AppWidgetLayoutEngine.slotScale(400, 200, 340, 47, 4);
        assertEquals(400f / 340f, scale, 0.001f);
        assertTrue(340 * scale <= 400f + 0.01f);
        assertTrue(47 * scale <= 200f + 0.01f);
        // Degenerate inputs fall back to no scaling.
        assertEquals(1f, AppWidgetLayoutEngine.slotScale(0, 200, 100, 100, 1), 0.001f);
        assertEquals(1f, AppWidgetLayoutEngine.slotScale(400, 200, 0, 100, 1), 0.001f);
    }

    @Test
    public void slotScaleFitsTallOverflowWidgetsCompletely() {
        // 4x2 widget in a 2x1 slot: also a complete fit.
        float scale = AppWidgetLayoutEngine.slotScale(400, 200, 260, 119, 4);
        assertEquals(400f / 260f, scale, 0.001f);
        assertTrue(260 * scale <= 400f + 0.01f);
        assertTrue(119 * scale <= 200f + 0.01f);
    }

    @Test
    public void naturalCellsWidePrefersDeclaredTargetCells() {
        assertEquals(4, AppWidgetLayoutEngine.naturalCellsWide(4, 340));
        assertEquals(1, AppWidgetLayoutEngine.naturalCellsWide(0, 0));
        // Undeclared: estimate from dp width (~85dp launcher columns).
        assertEquals(4, AppWidgetLayoutEngine.naturalCellsWide(0, 340));
        assertEquals(2, AppWidgetLayoutEngine.naturalCellsWide(0, 150));
        assertEquals(1, AppWidgetLayoutEngine.naturalCellsWide(0, 80));
    }

    @Test
    public void naturalSizeDpConvertsMiuiPixelsButKeepsStockDp() {
        // Outer screen reference: density 3.25, 372 x 428 dp.
        // Stock Android declares dp directly and stays untouched.
        assertEquals(250, AppWidgetLayoutEngine.naturalSizeDp(250, 110, 3.25f, 372, 428)[0]);
        assertEquals(110, AppWidgetLayoutEngine.naturalSizeDp(250, 110, 3.25f, 372, 428)[1]);
        // MIUI reports pixels: wider than the screen in "dp" means pixels.
        assertEquals(300, AppWidgetLayoutEngine.naturalSizeDp(975, 468, 3.25f, 372, 428)[0]);
        assertEquals(144, AppWidgetLayoutEngine.naturalSizeDp(975, 468, 3.25f, 372, 428)[1]);
        assertEquals(344, AppWidgetLayoutEngine.naturalSizeDp(1118, 154, 3.25f, 372, 428)[0]);
        assertEquals(47, AppWidgetLayoutEngine.naturalSizeDp(1118, 154, 3.25f, 372, 428)[1]);
        // Both dimensions implausibly large for dp (e.g. square 1x1 MIUI widget).
        assertEquals(110, AppWidgetLayoutEngine.naturalSizeDp(358, 358, 3.25f, 372, 428)[0]);
        // A genuine large dp widget (5x5 cells) is kept as-is.
        assertEquals(320, AppWidgetLayoutEngine.naturalSizeDp(320, 320, 3.25f, 372, 428)[0]);
        assertNull(AppWidgetLayoutEngine.naturalSizeDp(0, 110, 3.25f, 372, 428));
    }

    @Test
    public void parseSizeAcceptsValidPresets() {
        assertEquals(2, AppWidgetLayoutEngine.parseSize("2x2")[0]);
        assertEquals(3, AppWidgetLayoutEngine.parseSize("1x3")[1]);
        assertEquals(2, AppWidgetLayoutEngine.parseSize(" 2X1 ")[0]);
        assertNull(AppWidgetLayoutEngine.parseSize(""));
        assertNull(AppWidgetLayoutEngine.parseSize(null));
        assertNull(AppWidgetLayoutEngine.parseSize("3x1"));
        assertNull(AppWidgetLayoutEngine.parseSize("2x4"));
        assertNull(AppWidgetLayoutEngine.parseSize("abc"));
        assertNull(AppWidgetLayoutEngine.parseSize("2"));
    }

    @Test
    public void firstFitPacksRowMajorWithoutOverlap() {
        List<AppWidgetLayoutEngine.Placement> placements = AppWidgetLayoutEngine.layout(
                Arrays.asList(new int[]{1, 1}, new int[]{1, 1}, new int[]{2, 1}));
        assertEquals(3, placements.size());
        assertTrue(placements.get(0).fits);
        assertEquals(0, placements.get(0).col);
        assertEquals(0, placements.get(0).row);
        assertTrue(placements.get(1).fits);
        assertEquals(1, placements.get(1).col);
        assertEquals(0, placements.get(1).row);
        assertTrue(placements.get(2).fits);
        assertEquals(0, placements.get(2).col);
        assertEquals(1, placements.get(2).row);
    }

    @Test
    public void firstFitSkipsToNextOpenArea() {
        List<AppWidgetLayoutEngine.Placement> placements = AppWidgetLayoutEngine.layout(
                Arrays.asList(new int[]{2, 3}, new int[]{1, 1}));
        assertTrue(placements.get(0).fits);
        assertFalse(placements.get(1).fits);
    }

    @Test
    public void oversizeAndInvalidSlotsDoNotFit() {
        List<AppWidgetLayoutEngine.Placement> placements = AppWidgetLayoutEngine.layout(
                Arrays.asList(new int[]{0, 2}, new int[]{2, 3}));
        assertFalse(placements.get(0).fits);
        assertTrue(placements.get(1).fits);
    }

    @Test
    public void appWidgetLayoutAssignsFramesAndHidesOverflow() {
        WidgetConfig config = WidgetTypeRegistry.create(WidgetTypeRegistry.APPWIDGET);
        config.components.add(WidgetComponent.appWidget("com.a/.W", 2, 2));
        config.components.add(WidgetComponent.appWidget("com.b/.W", 2, 2));
        config.components.add(WidgetComponent.appWidget("com.c/.W", 2, 2));
        config.components.add(WidgetComponent.appWidget("com.d/.W", 2, 2));
        WidgetTypeRegistry.buildAppWidgetLayout(config);

        WidgetComponent first = config.components.get(0);
        assertEquals(WidgetComponent.TYPE_APPWIDGET, first.type);
        assertTrue(first.visible);
        assertEquals(6f, first.x, 0.001f);
        assertEquals(6f, first.y, 0.001f);
        assertEquals(WidgetConfig.CANVAS_WIDTH - 12f, first.width, 0.001f);
        assertEquals(WidgetConfig.CANVAS_HEIGHT * 2f / 3f - 12f, first.height, 0.001f);
        assertTrue(config.components.get(0).visible);
        assertFalse(config.components.get(1).visible);
        assertFalse(config.components.get(2).visible);
        assertFalse(config.components.get(3).visible);
        assertEquals(WidgetTypeRegistry.APPWIDGET, WidgetTypeRegistry.resolve(config));
    }

    @Test
    public void appWidgetLayoutPreservesDraggedPositions() {
        WidgetConfig config = WidgetTypeRegistry.create(WidgetTypeRegistry.APPWIDGET);
        WidgetComponent moved = WidgetComponent.appWidget("com.a/.W", 1, 1);
        moved.x = 226; // right column
        moved.y = 486; // bottom row
        config.components.add(moved);
        WidgetComponent other = WidgetComponent.appWidget("com.b/.W", 2, 1);
        config.components.add(other);
        WidgetTypeRegistry.buildAppWidgetLayout(config);

        assertEquals(1, Math.round(moved.x / 220f));
        assertEquals(2, Math.round(moved.y / 240f));
        assertTrue(moved.visible);
        assertTrue(other.visible);
        assertEquals(6f, other.x, 0.001f);
        assertEquals(6f, other.y, 0.001f);
    }

    @Test
    public void appWidgetLayoutFirstFitsWhenDraggedCellOccupied() {
        WidgetConfig config = WidgetTypeRegistry.create(WidgetTypeRegistry.APPWIDGET);
        WidgetComponent first = WidgetComponent.appWidget("com.a/.W", 2, 2);
        config.components.add(first);
        WidgetComponent second = WidgetComponent.appWidget("com.b/.W", 2, 1);
        second.x = 6;
        second.y = 6;
        config.components.add(second);
        WidgetTypeRegistry.buildAppWidgetLayout(config);

        assertTrue(first.visible);
        assertTrue(second.visible);
        assertEquals(2, Math.round(second.y / 240f));
    }

    @Test
    public void autoSizeMapsProviderPixelsToGridCells() {
        float density = 2.75f;
        assertEquals(1, AppWidgetLayoutEngine.autoSize(358, 358, density)[0]);
        assertEquals(1, AppWidgetLayoutEngine.autoSize(358, 358, density)[1]);
        assertEquals(2, AppWidgetLayoutEngine.autoSize(975, 358, density)[0]);
        assertEquals(1, AppWidgetLayoutEngine.autoSize(975, 358, density)[1]);
        assertEquals(2, AppWidgetLayoutEngine.autoSize(975, 813, density)[0]);
        assertEquals(2, AppWidgetLayoutEngine.autoSize(975, 813, density)[1]);
        assertEquals(1, AppWidgetLayoutEngine.autoSize(0, 0, density)[0]);
        assertEquals(1, AppWidgetLayoutEngine.autoSize(0, 0, density)[1]);
        assertEquals(2, AppWidgetLayoutEngine.autoSize(99999, 358, density)[0]);
        assertEquals(3, AppWidgetLayoutEngine.autoSize(358, 99999, density)[1]);
    }

    @Test
    public void appWidgetComponentRoundTripKeepsIdProviderAndSize() throws Exception {
        WidgetComponent source = WidgetComponent.appWidget("com.a/.Widget", 1, 3);
        source.appWidgetId = 42;

        WidgetComponent restored = WidgetComponent.fromJson(source.toJson());

        assertEquals(WidgetComponent.TYPE_APPWIDGET, restored.type);
        assertEquals(ActionSpec.HOST_APPWIDGET, restored.actionType);
        assertEquals("com.a/.Widget", restored.actionValue);
        assertEquals("1x3", restored.content);
        assertEquals(42, restored.appWidgetId);
        assertEquals(-1, WidgetComponent.fromJson(
                WidgetComponent.appWidget("com.a/.Widget", 2, 2).toJson()).appWidgetId);
    }

    @Test
    public void appWidgetComponentRoundTripKeepsCornerFlag() throws Exception {
        WidgetComponent source = WidgetComponent.appWidget("com.a/.Widget", 2, 1);
        source.cornerEnabled = false;

        WidgetComponent restored = WidgetComponent.fromJson(source.toJson());

        assertFalse(restored.cornerEnabled);
        // Corners default on, including legacy configs without the key.
        WidgetComponent legacy = WidgetComponent.fromJson(
                WidgetComponent.appWidget("com.a/.Widget", 2, 1).toJson());
        assertTrue(legacy.cornerEnabled);
    }

    @Test
    public void notificationTypeBuildsSingleFullCanvasLayer() {
        WidgetConfig config = WidgetTypeRegistry.create(WidgetTypeRegistry.NOTIFICATIONS);
        assertEquals(1, config.components.size());
        WidgetComponent layer = config.components.get(0);
        assertEquals(WidgetComponent.TYPE_NOTIFICATION_LIST, layer.type);
        assertEquals(WidgetConfig.CANVAS_WIDTH, layer.width, 0.001f);
        assertEquals(WidgetConfig.CANVAS_HEIGHT, layer.height, 0.001f);
        assertEquals(WidgetTypeRegistry.NOTIFICATIONS, WidgetTypeRegistry.resolve(config));
        WidgetTypeRegistry.normalize(config);
        assertEquals(1, config.components.size());
        assertEquals(WidgetComponent.TYPE_NOTIFICATION_LIST, config.components.get(0).type);
    }

    @Test
    public void lyricScaleAppliesToMusicLayoutAndSurvivesRoundTrip() throws Exception {
        WidgetConfig config = WidgetTypeRegistry.create(WidgetTypeRegistry.MUSIC);
        config.lyricScale = 1.5f;
        WidgetTypeRegistry.ensureThreeLineMusicLayout(config);
        for (WidgetComponent component : config.components) {
            if (WidgetComponent.TYPE_LYRIC_CURRENT.equals(component.type)) {
                assertEquals(48f, component.textSize, 0.001f);
            }
            if (WidgetComponent.TYPE_LYRIC_PREVIOUS.equals(component.type)) {
                assertEquals(33f, component.textSize, 0.001f);
            }
            if (WidgetComponent.TYPE_SONG_TITLE.equals(component.type)) {
                assertEquals(24f, component.textSize, 0.001f);
            }
        }

        WidgetConfig restored = WidgetConfig.fromJson(config.toJson());
        assertEquals(1.5f, restored.lyricScale, 0.001f);
        for (WidgetComponent component : restored.components) {
            if (WidgetComponent.TYPE_LYRIC_CURRENT.equals(component.type)) {
                assertEquals(48f, component.textSize, 0.001f);
            }
        }
    }

    @Test
    public void lyricScaleDefaultsToOneForLegacyConfigs() throws Exception {
        WidgetConfig config = WidgetConfig.fromJson(
                WidgetTypeRegistry.create(WidgetTypeRegistry.MUSIC).toJson());
        assertEquals(1f, config.lyricScale, 0.001f);
        for (WidgetComponent component : config.components) {
            if (WidgetComponent.TYPE_LYRIC_CURRENT.equals(component.type)) {
                assertEquals(32f, component.textSize, 0.001f);
            }
        }
    }

    @Test
    public void mamlSlotRoundTripKeepsLabelAndType() throws Exception {
        WidgetComponent source = WidgetComponent.mamlSlot("每日步数", 2, 2);
        WidgetComponent restored = WidgetComponent.fromJson(source.toJson());
        assertEquals(WidgetComponent.TYPE_APPWIDGET, restored.type);
        assertEquals(ActionSpec.HOST_MAML, restored.actionType);
        assertEquals("每日步数", restored.actionValue);
        assertEquals("2x2", restored.content);
        assertEquals(-1, restored.appWidgetId);
    }

    @Test
    public void registryExposesNewTypes() {
        assertEquals("notifications", WidgetTypeRegistry.NOTIFICATIONS);
        assertEquals("appwidget", WidgetTypeRegistry.APPWIDGET);
        assertEquals("maml", WidgetTypeRegistry.MAML);
        assertTrue(WidgetTypeRegistry.get(WidgetTypeRegistry.NOTIFICATIONS) != null);
        assertTrue(WidgetTypeRegistry.get(WidgetTypeRegistry.APPWIDGET) != null);
        assertTrue(WidgetTypeRegistry.get(WidgetTypeRegistry.MAML) != null);
        assertEquals(6, WidgetTypeRegistry.all().size());
    }

    @Test
    public void mamlTypeBuildsEmptyNativeLayout() {
        WidgetConfig config = WidgetTypeRegistry.create(WidgetTypeRegistry.MAML);
        assertTrue(config.components.isEmpty());
        assertEquals("none", config.mediaType);
        assertEquals(WidgetTypeRegistry.MAML, WidgetTypeRegistry.resolve(config));
        WidgetTypeRegistry.normalize(config);
        assertTrue(config.components.isEmpty());
    }
}

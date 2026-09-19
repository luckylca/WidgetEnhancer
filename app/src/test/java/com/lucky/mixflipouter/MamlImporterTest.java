package com.lucky.mixflipouter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MamlImporterTest {
    @Test
    public void parsesWidgetEntrySizes() {
        assertArrayEquals(new int[]{2, 2}, MamlImporter.parseWidgetEntrySize("widget_2x2"));
        assertArrayEquals(new int[]{2, 3}, MamlImporter.parseWidgetEntrySize("widget_2x3"));
        assertArrayEquals(new int[]{1, 1}, MamlImporter.parseWidgetEntrySize("widget_1x1"));
        // Oversized entries still parse — the slot display defaults to 2x3 later.
        assertArrayEquals(new int[]{4, 2}, MamlImporter.parseWidgetEntrySize("widget_4x2"));
        assertArrayEquals(new int[]{4, 4}, MamlImporter.parseWidgetEntrySize("widget_4x4"));
    }

    @Test
    public void rejectsNonWidgetEntries() {
        assertNull(MamlImporter.parseWidgetEntrySize("widget_2x3.png"));
        assertNull(MamlImporter.parseWidgetEntrySize("widget_2x2_dark.png"));
        assertNull(MamlImporter.parseWidgetEntrySize("description.xml"));
        assertNull(MamlImporter.parseWidgetEntrySize("manifest.xml"));
        assertNull(MamlImporter.parseWidgetEntrySize(null));
    }

    @Test
    public void rejectsImplausibleSizes() {
        assertNull(MamlImporter.parseWidgetEntrySize("widget_0x0"));
        assertNull(MamlImporter.parseWidgetEntrySize("widget_10x1"));
        assertNull(MamlImporter.parseWidgetEntrySize("widget_2x"));
    }

    @Test
    public void largestFittingPicksBiggestInsideGrid() {
        List<int[]> sizes = Arrays.asList(new int[]{1, 1}, new int[]{2, 2}, new int[]{4, 4});
        assertArrayEquals(new int[]{2, 2}, MamlImporter.largestFitting(sizes));
    }

    @Test
    public void largestFittingReturnsNullWhenAllExceedGrid() {
        List<int[]> sizes = Arrays.asList(new int[]{4, 2}, new int[]{4, 4});
        assertNull(MamlImporter.largestFitting(sizes));
        assertNull(MamlImporter.largestFitting(Collections.<int[]>emptyList()));
    }

    @Test
    public void resolveSizePrefersExactSlotMatch() {
        List<int[]> sizes = Arrays.asList(new int[]{4, 4}, new int[]{2, 2});
        assertArrayEquals(new int[]{2, 2}, MamlImporter.pickResolveSize(sizes, 2, 2));
    }

    @Test
    public void resolveSizeFallsBackToLargest() {
        List<int[]> sizes = Arrays.asList(new int[]{4, 2}, new int[]{2, 1});
        // Slot defaults to 2x3 for oversized packages; resolve the real 4x2.
        assertArrayEquals(new int[]{4, 2}, MamlImporter.pickResolveSize(sizes, 2, 3));
        assertNull(MamlImporter.pickResolveSize(Collections.<int[]>emptyList(), 2, 3));
    }
}

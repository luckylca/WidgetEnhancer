package com.lucky.mixflipouter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    @Test
    public void extractsThumbnailForAnyWidgetSizeAndSkipsDarkVariant() throws Exception {
        File archive = File.createTempFile("maml-preview-", ".mtz");
        byte[] lightPreview = new byte[]{1, 2, 3, 4};
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("description.xml"));
            zip.write("<Widget/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("preview/widget_2x2_dark.png"));
            zip.write(new byte[]{9, 9, 9});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("preview/widget_2x2.png"));
            zip.write(lightPreview);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("widget_2x2"));
            zip.write(new byte[]{0});
            zip.closeEntry();
        }
        try {
            assertArrayEquals(lightPreview, MamlImporter.validate(archive));
            assertArrayEquals(lightPreview, MamlImporter.extractPreview(archive));
        } finally {
            archive.delete();
        }
    }

    @Test
    public void cacheSignatureIgnoresPreviewAndWidgetSizeWhenContentMatches() throws Exception {
        File first = createPackage("widget_2x2", new byte[]{1, 2, 3},
                "preview/widget_2x2.png", new byte[]{4, 5});
        File second = createPackage("widget_4x2", new byte[]{1, 2, 3},
                "preview/widget_4x2.png", new byte[]{8, 9});
        try {
            assertEquals(MamlImporter.contentSignature(first),
                    MamlImporter.contentSignature(second));
            assertEquals(4, MamlImporter.preferredDisplayArea(first));
            assertEquals(-1, MamlImporter.preferredDisplayArea(second));
            assertEquals("Weather", MamlImporter.displayName(first));
        } finally {
            first.delete();
            second.delete();
        }
    }

    private static File createPackage(String widgetEntry, byte[] widget,
                                      String previewEntry, byte[] preview) throws Exception {
        File archive = File.createTempFile("maml-signature-", ".mtz");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("description.xml"));
            zip.write("<theme><title locale=\"en_US\">Weather</title></theme>"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(widgetEntry));
            zip.write(widget);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(previewEntry));
            zip.write(preview);
            zip.closeEntry();
        }
        return archive;
    }
}

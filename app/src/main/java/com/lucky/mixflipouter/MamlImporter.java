package com.lucky.mixflipouter;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports an external MAML widget package (.zip/.mtz, e.g. extracted from the
 * theme store) as a system-rendered outer-screen widget. The raw package is
 * kept next to the widget config so FlipHome can pick it up.
 */
final class MamlImporter {
    private static final long MAX_ZIP_BYTES = 64L * 1024 * 1024;

    /**
     * Validates and stores a MAML package for one grid slot inside an appwidget
     * page. Returns the slot's display size: the largest widget size in the
     * package that fits the grid, or the full-page 2x3 default when every size
     * in the package exceeds it (rendering then resolves the package's real
     * size and scales it into the slot).
     */
    static int[] importSlot(Context context, WidgetRepository repository,
                            String widgetId, String componentId, Uri source) throws Exception {
        validate(context, source);
        List<int[]> sizes;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) throw new IllegalStateException("无法读取所选文件");
            sizes = scanSizes(in);
        }
        if (sizes.isEmpty()) {
            throw new IllegalArgumentException("包里没有可用的小部件（缺少 widget_AxB）");
        }
        copyTo(context, source, repository.mamlSlotFile(widgetId, componentId));
        int[] display = largestFitting(sizes);
        return display == null
                ? new int[]{AppWidgetLayoutEngine.GRID_COLS, AppWidgetLayoutEngine.GRID_ROWS}
                : display;
    }

    /** All widget_AxB sizes declared in the package stream; may exceed the grid. */
    static List<int[]> scanSizes(InputStream raw) throws Exception {
        List<int[]> sizes = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                String base = name.contains("/")
                        ? name.substring(name.lastIndexOf('/') + 1) : name;
                int[] size = parseWidgetEntrySize(base);
                if (size != null) sizes.add(size);
                zip.closeEntry();
            }
        }
        return sizes;
    }

    static List<int[]> scanSizes(File zipFile) throws Exception {
        try (InputStream in = new FileInputStream(zipFile)) {
            return scanSizes(in);
        }
    }

    /** Largest size that fits the outer-screen grid, or null when none does. */
    static int[] largestFitting(List<int[]> sizes) {
        int[] best = null;
        for (int[] size : sizes) {
            if (!AppWidgetLayoutEngine.validSize(size[0], size[1])) continue;
            if (best == null || size[0] * size[1] > best[0] * best[1]) best = size;
        }
        return best;
    }

    /**
     * Size to resolve inside the package for a slot with grid size
     * (slotCols, slotRows): an exact match when the package carries it,
     * otherwise the largest size available — FlipHome's host view scales it.
     */
    static int[] pickResolveSize(List<int[]> sizes, int slotCols, int slotRows) {
        int[] best = null;
        for (int[] size : sizes) {
            if (size[0] == slotCols && size[1] == slotRows) return size;
            if (best == null || size[0] * size[1] > best[0] * best[1]) best = size;
        }
        return best;
    }

    /** Parses a "widget_2x2"-style entry base name into {cols, rows} (1..9), else null. */
    static int[] parseWidgetEntrySize(String baseName) {
        if (baseName == null || !baseName.startsWith("widget_")) return null;
        String spec = baseName.substring("widget_".length());
        int split = spec.indexOf('x');
        if (split <= 0 || split >= spec.length() - 1) return null;
        try {
            int cols = Integer.parseInt(spec.substring(0, split));
            int rows = Integer.parseInt(spec.substring(split + 1));
            return cols >= 1 && cols <= 9 && rows >= 1 && rows <= 9
                    ? new int[]{cols, rows} : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static WidgetConfig importZip(Context context, WidgetRepository repository,
                                  Uri source, String displayName) throws Exception {
        byte[] preview = validate(context, source);

        WidgetConfig draft = WidgetTypeRegistry.create(WidgetTypeRegistry.MAML);
        String base = displayName == null ? "" : displayName.trim();
        if (base.isEmpty()) base = "ZIP 小部件";
        base = base.replaceAll("\\.(zip|mtz)$", "");
        draft.name = base;
        WidgetConfig created = repository.createFromTemplate(draft);
        try {
            copyTo(context, source, repository.mamlFile(created.id));
            if (preview != null && preview.length > 0) {
                try (FileOutputStream out = new FileOutputStream(
                        repository.mamlPreviewFile(created.id), false)) {
                    out.write(preview);
                }
            }
            repository.save(created);
            return created;
        } catch (Throwable error) {
            repository.delete(created.id);
            if (error instanceof Exception) throw (Exception) error;
            throw new IllegalStateException("导入失败", error);
        }
    }

    private static boolean isPreviewEntry(String lowerName) {
        String base = lowerName.contains("/")
                ? lowerName.substring(lowerName.lastIndexOf('/') + 1) : lowerName;
        return base.startsWith("widget_2x3")
                && (base.endsWith(".png") || base.endsWith(".webp"));
    }

    /** Returns the 2x3 preview image bytes if the package carries one. */
    private static byte[] validate(Context context, Uri source) throws Exception {
        byte[] preview = null;
        boolean hasXml = false;
        long total = 0;
        try (InputStream raw = context.getContentResolver().openInputStream(source)) {
            if (raw == null) throw new IllegalStateException("无法读取所选文件");
            try (ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    total += entry.getSize() > 0 ? entry.getSize() : 4096;
                    if (total > MAX_ZIP_BYTES) {
                        throw new IllegalArgumentException("文件过大，不支持导入");
                    }
                    String name = entry.getName();
                    if (entry.isDirectory()) continue;
                    String lower = name.toLowerCase();
                    if (lower.endsWith(".xml")) hasXml = true;
                    if (preview == null && isPreviewEntry(lower)) {
                        preview = readEntry(zip, 2L * 1024 * 1024);
                    }
                    zip.closeEntry();
                }
            }
        }
        if (!hasXml) throw new IllegalArgumentException("不是有效的 MAML 小部件包");
        return preview;
    }

    private static byte[] readEntry(ZipInputStream zip, long limit) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = zip.read(buffer)) >= 0) {
            total += count;
            if (total > limit) return out.toByteArray();
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static void copyTo(Context context, Uri source, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (InputStream in = context.getContentResolver().openInputStream(source);
             FileOutputStream out = new FileOutputStream(temporary, false)) {
            if (in == null) throw new IllegalStateException("无法读取所选文件");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = in.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_ZIP_BYTES) {
                    throw new IllegalArgumentException("文件过大，不支持导入");
                }
                out.write(buffer, 0, count);
            }
            out.getFD().sync();
        } catch (Throwable error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IllegalStateException("无法保存小部件包");
        }
    }

    private MamlImporter() {}
}

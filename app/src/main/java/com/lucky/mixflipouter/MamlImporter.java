package com.lucky.mixflipouter;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Enumeration;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Imports an external MAML widget package (.zip/.mtz, e.g. extracted from the
 * theme store) as a system-rendered outer-screen widget. The raw package is
 * kept next to the widget config so FlipHome can pick it up.
 */
final class MamlImporter {
    static final long MAX_ZIP_BYTES = 64L * 1024 * 1024;

    /**
     * Validates and stores a MAML package for one grid slot inside an appwidget
     * page. Returns the slot's display size: the largest widget size in the
     * package that fits the grid, or the full-page 2x3 default when every size
     * in the package exceeds it (rendering then resolves the package's real
     * size and scales it into the slot).
     */
    static int[] importSlot(Context context, WidgetRepository repository,
                            String widgetId, String componentId, Uri source) throws Exception {
        byte[] preview = validate(context, source);
        List<int[]> sizes;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) throw new IllegalStateException("无法读取所选文件");
            sizes = scanSizes(in);
        }
        if (sizes.isEmpty()) {
            throw new IllegalArgumentException("包里没有可用的小部件（缺少 widget_AxB）");
        }
        copyTo(context, source, repository.mamlSlotFile(widgetId, componentId));
        repository.writeMamlSlotPreview(widgetId, componentId, preview);
        int[] display = largestFitting(sizes);
        return display == null
                ? new int[]{AppWidgetLayoutEngine.GRID_COLS, AppWidgetLayoutEngine.GRID_ROWS}
                : display;
    }

    static int[] importSlot(WidgetRepository repository, String widgetId,
                            String componentId, File source) throws Exception {
        byte[] preview = validate(source);
        List<int[]> sizes = scanSizes(source);
        if (sizes.isEmpty()) {
            throw new IllegalArgumentException("包里没有可用的小部件（缺少 widget_AxB）");
        }
        copyFile(source, repository.mamlSlotFile(widgetId, componentId));
        repository.writeMamlSlotPreview(widgetId, componentId, preview);
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
        String extension;
        if (base.endsWith(".png")) extension = ".png";
        else if (base.endsWith(".webp")) extension = ".webp";
        else return false;
        String widgetName = base.substring(0, base.length() - extension.length());
        if (widgetName.endsWith("_dark")) return false;
        if (widgetName.endsWith("_light")) {
            widgetName = widgetName.substring(0, widgetName.length() - "_light".length());
        }
        return parseWidgetEntrySize(widgetName) != null;
    }

    /** Returns a PNG/WebP thumbnail for any widget_AxB size, if present. */
    private static byte[] validate(Context context, Uri source) throws Exception {
        try (InputStream raw = context.getContentResolver().openInputStream(source)) {
            if (raw == null) throw new IllegalStateException("无法读取所选文件");
            return validate(raw);
        }
    }

    static byte[] validate(File source) throws Exception {
        try (InputStream raw = new FileInputStream(source)) {
            return validate(raw);
        }
    }

    /** Reads a package thumbnail without decompressing the rest of the archive. */
    static byte[] extractPreview(File source) throws Exception {
        try (ZipFile zip = new ZipFile(source)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !isPreviewEntry(
                        entry.getName().toLowerCase(java.util.Locale.ROOT))) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = in.read(buffer)) >= 0) {
                        if (out.size() + count > 2 * 1024 * 1024) {
                            throw new IllegalArgumentException("小部件预览图过大");
                        }
                        out.write(buffer, 0, count);
                    }
                    return out.toByteArray();
                }
            }
        }
        return null;
    }

    /** Stable identity for equal widgets, ignoring thumbnail artwork and size labels. */
    static String contentSignature(File source) throws Exception {
        ArrayList<String> entries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(source)) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/');
                String lowerPath = path.toLowerCase(java.util.Locale.ROOT);
                String base = path.contains("/")
                        ? path.substring(path.lastIndexOf('/') + 1) : path;
                String lowerBase = base.toLowerCase(java.util.Locale.ROOT);
                if (lowerPath.startsWith("preview/") || "meta.json".equals(lowerBase)) continue;
                int[] widgetSize = parseWidgetEntrySize(base);
                if (widgetSize != null) {
                    String parent = path.contains("/")
                            ? path.substring(0, path.lastIndexOf('/') + 1) : "";
                    path = parent + "widget";
                }
                entries.add(path + ":" + entry.getSize() + ":" + entry.getCrc());
            }
        }
        Collections.sort(entries, Comparator.naturalOrder());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String entry : entries) {
            digest.update(entry.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return toHex(digest.digest());
    }

    /** Largest display size this archive can occupy in the outer widget grid. */
    static int preferredDisplayArea(File source) throws Exception {
        ArrayList<int[]> sizes = new ArrayList<>();
        try (ZipFile zip = new ZipFile(source)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String base = entry.getName();
                if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                if (base.contains("/")) base = base.substring(base.lastIndexOf('/') + 1);
                int[] size = parseWidgetEntrySize(base);
                if (size != null) sizes.add(size);
            }
        }
        int[] best = largestFitting(sizes);
        return best == null ? -1 : best[0] * best[1];
    }

    /** Human-readable MAML package name from its description.xml title. */
    static String displayName(File source) throws Exception {
        try (ZipFile zip = new ZipFile(source)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()
                        || !entry.getName().toLowerCase(java.util.Locale.ROOT)
                        .endsWith("description.xml")
                        || entry.getSize() > 256 * 1024) continue;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = readEntry(in, 256 * 1024);
                }
                String xml = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "<title(?:\\s+locale=[\\\"']([^\\\"']+)[\\\"'])?[^>]*>(.*?)</title>",
                        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                        .matcher(xml);
                String fallback = "";
                while (matcher.find()) {
                    String title = unescapeXml(matcher.group(2).trim());
                    if (title.isEmpty()) continue;
                    String locale = matcher.group(1);
                    if (locale == null || locale.isEmpty()) return title;
                    if (locale.toLowerCase(java.util.Locale.ROOT).startsWith("zh")) {
                        return title;
                    }
                    if (fallback.isEmpty()) fallback = title;
                }
                return fallback;
            }
        }
        return "";
    }

    private static String unescapeXml(String value) {
        return value.replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">") .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    private static String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(java.util.Locale.ROOT,
                "%02x", item & 0xff));
        return value.toString();
    }

    private static byte[] validate(InputStream raw) throws Exception {
        byte[] preview = null;
        boolean hasXml = false;
        long total = 0;
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
        if (!hasXml) throw new IllegalArgumentException("不是有效的 MAML 小部件包");
        return preview;
    }

    private static void copyFile(File source, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        File temporary = new File(parent, target.getName() + ".tmp");
        try (InputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(temporary, false)) {
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

    private static byte[] readEntry(InputStream zip, long limit) throws Exception {
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

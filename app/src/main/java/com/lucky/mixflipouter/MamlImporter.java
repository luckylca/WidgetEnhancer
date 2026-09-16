package com.lucky.mixflipouter;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports an external MAML widget package (.zip/.mtz, e.g. extracted from the
 * theme store) as a system-rendered outer-screen widget. The raw package is
 * kept next to the widget config so FlipHome can pick it up.
 */
final class MamlImporter {
    private static final long MAX_ZIP_BYTES = 64L * 1024 * 1024;

    /** Validates and stores a MAML package for one grid slot inside an appwidget page. */
    static void importSlot(Context context, WidgetRepository repository,
                           String widgetId, String componentId, Uri source) throws Exception {
        validate(context, source);
        copyTo(context, source, repository.mamlSlotFile(widgetId, componentId));
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

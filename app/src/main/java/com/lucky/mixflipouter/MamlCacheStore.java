package com.lucky.mixflipouter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

/** App-private copy of MAML packages discovered in the launcher cache. */
final class MamlCacheStore {
    private static final String PREFS = "maml_cache_v1";
    private static final String NAME_SUFFIX = ".name";
    private static final String FINGERPRINT_SUFFIX = ".fingerprint";

    static final class Item {
        final String id;
        final String name;

        Item(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class RankedItem {
        final Item item;
        final String signature;
        final int displayArea;

        RankedItem(Item item, String signature, int displayArea) {
            this.item = item;
            this.signature = signature;
            this.displayArea = displayArea;
        }
    }

    static List<Item> list(Context context) {
        SharedPreferences prefs = prefs(context);
        ArrayList<Item> items = new ArrayList<>();
        File directory = cacheDir(context);
        File[] packages = directory.listFiles((dir, name) -> name.endsWith(".mtz"));
        if (packages == null) return items;
        ArrayList<RankedItem> ranked = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (File archive : packages) {
            String fileName = archive.getName();
            String id = fileName.substring(0, fileName.length() - ".mtz".length());
            if (!id.matches("[a-f0-9]{64}") || !seenIds.add(id)) continue;
            String name = prefs.getString(id + NAME_SUFFIX, "");
            ensurePreview(context, id, archive);
            if (name.isEmpty()) {
                try { name = MamlImporter.displayName(archive); }
                catch (Throwable ignored) {}
            }
            if (name == null || name.isEmpty()) name = "缓存小部件 " + id.substring(0, 6);
            String signature;
            int displayArea;
            try {
                signature = MamlImporter.contentSignature(archive);
                displayArea = MamlImporter.preferredDisplayArea(archive);
            } catch (Throwable ignored) {
                signature = id;
                displayArea = -1;
            }
            ranked.add(new RankedItem(new Item(id, name), signature, displayArea));
        }
        Collections.sort(ranked, (first, second) ->
                Integer.compare(second.displayArea, first.displayArea));
        Map<String, RankedItem> unique = new LinkedHashMap<>();
        for (RankedItem candidate : ranked) {
            if (!unique.containsKey(candidate.signature)) {
                unique.put(candidate.signature, candidate);
            }
        }
        for (RankedItem candidate : unique.values()) items.add(candidate.item);
        Collections.sort(items, Comparator.comparing(item -> item.name,
                String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    private static void ensurePreview(Context context, String id, File archive) {
        File preview = previewFile(context, id);
        if (preview.isFile()) return;
        try {
            byte[] bytes = MamlImporter.extractPreview(archive);
            if (bytes == null || bytes.length == 0) return;
            File temporary = new File(preview.getParentFile(), preview.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temporary, false)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (!temporary.renameTo(preview)) temporary.delete();
        } catch (Throwable ignored) {
            // Keep the package visible even if it contains no usable thumbnail.
        }
    }

    static File packageFile(Context context, String id) {
        return new File(cacheDir(context), id + ".mtz");
    }

    static File previewFile(Context context, String id) {
        return new File(cacheDir(context), id + ".preview");
    }

    static boolean hasFingerprint(Context context, String id, String fingerprint) {
        return fingerprint != null && fingerprint.equals(
                prefs(context).getString(id + FINGERPRINT_SUFFIX, null))
                && packageFile(context, id).isFile();
    }

    static boolean publish(Context context, String id, String name, String fingerprint,
                           ParcelFileDescriptor descriptor) throws Exception {
        if (id == null || !id.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("缓存小部件标识无效");
        }
        if (fingerprint == null || fingerprint.length() > 256) {
            throw new IllegalArgumentException("缓存小部件版本无效");
        }
        File directory = cacheDir(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建缓存目录");
        }
        if (hasFingerprint(context, id, fingerprint)) {
            descriptor.close();
            return false;
        }

        File temporary = new File(directory, id + ".mtz.tmp");
        File previewTemporary = new File(directory, id + ".preview.tmp");
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             FileOutputStream out = new FileOutputStream(temporary, false)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = in.read(buffer)) >= 0) {
                total += count;
                if (total > MamlImporter.MAX_ZIP_BYTES) {
                    throw new IllegalArgumentException("缓存小部件过大，不支持导入");
                }
                out.write(buffer, 0, count);
            }
            out.getFD().sync();
        } catch (Throwable error) {
            temporary.delete();
            throw error;
        }

        byte[] preview;
        try {
            preview = MamlImporter.validate(temporary);
            if (MamlImporter.scanSizes(temporary).isEmpty()) {
                throw new IllegalArgumentException("缓存里没有可用的小部件（缺少 widget_AxB）");
            }
        } catch (Throwable error) {
            temporary.delete();
            throw error;
        }
        File packageFile = packageFile(context, id);
        if (!temporary.renameTo(packageFile)) {
            temporary.delete();
            throw new IllegalStateException("无法保存缓存小部件");
        }
        File previewFile = previewFile(context, id);
        if (preview != null && preview.length > 0) {
            try (FileOutputStream out = new FileOutputStream(previewTemporary, false)) {
                out.write(preview);
                out.getFD().sync();
            }
            if (!previewTemporary.renameTo(previewFile)) {
                previewTemporary.delete();
                packageFile.delete();
                throw new IllegalStateException("无法保存小部件预览图");
            }
        } else {
            previewFile.delete();
            previewTemporary.delete();
        }
        prefs(context).edit()
                .putString(id + NAME_SUFFIX, name == null || name.trim().isEmpty()
                        ? "缓存小部件" : name.trim())
                .putString(id + FINGERPRINT_SUFFIX, fingerprint)
                .apply();
        return true;
    }

    private static File cacheDir(Context context) {
        return new File(context.getFilesDir(), "maml-cache");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private MamlCacheStore() {}
}

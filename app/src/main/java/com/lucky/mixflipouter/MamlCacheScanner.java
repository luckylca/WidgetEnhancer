package com.lucky.mixflipouter;

import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Runs in the launcher process, where its private MAML cache is readable. */
final class MamlCacheScanner {
    private static final String TAG = "MixFlipCustom";
    private static final int MAX_CANDIDATES = 200;
    private static final int MAX_SCAN_DEPTH = 4;
    private static final int MAX_SCAN_FILES = 10_000;

    private static final class Candidate {
        final File source;
        final String id;
        final String name;
        final String fingerprint;

        Candidate(File source, String id, String name, String fingerprint) {
            this.source = source;
            this.id = id;
            this.name = name;
            this.fingerprint = fingerprint;
        }
    }

    static void sync(Context sourceContext) {
        if (sourceContext == null) return;
        try {
            Set<String> seen = new HashSet<>();
            ArrayList<Candidate> candidates = new ArrayList<>();
            File credentialRoot = new File(sourceContext.getFilesDir(), "maml/res/0");
            collect(credentialRoot, 0, candidates, seen);
            try {
                Context deviceContext = sourceContext.createDeviceProtectedStorageContext();
                File deviceRoot = new File(deviceContext.getFilesDir(), "maml/res/0");
                collect(deviceRoot, 0, candidates, seen);
            } catch (Throwable ignored) {
            }

            for (Candidate candidate : candidates) {
                try {
                    Bundle request = new Bundle();
                    request.putString("fingerprint", candidate.fingerprint);
                    Bundle known = sourceContext.getContentResolver().call(
                            Contract.PROVIDER_URI, "has_maml_cache", candidate.id, request);
                    if (known != null && known.getBoolean("known")) continue;
                    publish(sourceContext, candidate);
                } catch (Throwable error) {
                    Log.w(TAG, "MAML cache sync failed for " + candidate.name, error);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "MAML cache scan failed", error);
        }
    }

    private static void collect(File directory, int depth, List<Candidate> output,
                                Set<String> seen) throws Exception {
        if (directory == null || !directory.isDirectory() || isSymlink(directory)
                || output.size() >= MAX_CANDIDATES) return;
        String canonical = directory.getCanonicalPath();
        if (!seen.add(canonical)) return;

        if (looksLikePackage(directory)) {
            output.add(candidate(directory));
            return;
        }

        File[] children = directory.listFiles();
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getName,
                String.CASE_INSENSITIVE_ORDER));
        for (File child : children) {
            if (output.size() >= MAX_CANDIDATES) return;
            if (isSymlink(child)) continue;
            if (child.isDirectory() && depth < MAX_SCAN_DEPTH) {
                collect(child, depth + 1, output, seen);
            } else if (child.isFile() && isPackageArchive(child)) {
                output.add(candidate(child));
            }
        }
    }

    private static boolean looksLikePackage(File directory) {
        File[] children = directory.listFiles();
        if (children == null) return false;
        boolean hasXml = false;
        boolean hasWidgetEntry = false;
        for (File child : children) {
            if (isSymlink(child)) continue;
            if (child.isFile()) {
                String lower = child.getName().toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".xml")) hasXml = true;
                if (MamlImporter.parseWidgetEntrySize(child.getName()) != null) {
                    hasWidgetEntry = true;
                }
            } else if (child.isDirectory()
                    && MamlImporter.parseWidgetEntrySize(child.getName()) != null) {
                hasWidgetEntry = true;
            }
            if (hasXml && hasWidgetEntry) return true;
        }
        return false;
    }

    private static boolean isPackageArchive(File file) {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".mtz") || name.endsWith(".zip");
    }

    private static Candidate candidate(File source) throws Exception {
        String path = source.getCanonicalPath();
        String id = sha256(path);
        String fingerprint = fingerprint(source);
        String name = source.isDirectory() ? source.getName()
                : source.getName().replaceAll("\\.(?i:mtz|zip)$", "");
        if (name.isEmpty() || "0".equals(name)) name = "外屏缓存小部件";
        return new Candidate(source, id, name, fingerprint);
    }

    private static String fingerprint(File source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        update(digest, source.getCanonicalPath());
        if (source.isFile()) {
            update(digest, source.length() + ":" + source.lastModified());
        } else {
            ArrayList<File> files = new ArrayList<>();
            collectFiles(source, source, files, 0);
            Collections.sort(files, Comparator.comparing(file -> {
                try { return source.toPath().relativize(file.toPath()).toString(); }
                catch (Throwable ignored) { return file.getAbsolutePath(); }
            }));
            for (File file : files) {
                String relative;
                try { relative = source.toPath().relativize(file.toPath()).toString(); }
                catch (Throwable ignored) { relative = file.getAbsolutePath(); }
                update(digest, relative + ":" + file.length() + ":" + file.lastModified());
            }
        }
        // Force a refresh for copies made before thumbnails of every widget size were read.
        return "cache-v2:" + hex(digest.digest());
    }

    private static void collectFiles(File root, File directory, List<File> files, int depth) {
        if (depth > MAX_SCAN_DEPTH || files.size() >= MAX_SCAN_FILES) return;
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (files.size() >= MAX_SCAN_FILES || isSymlink(child)) return;
            if (child.isFile()) files.add(child);
            else if (child.isDirectory()) collectFiles(root, child, files, depth + 1);
        }
    }

    private static void publish(Context context, Candidate candidate) throws Exception {
        File archive = candidate.source;
        boolean temporary = false;
        if (candidate.source.isDirectory()) {
            archive = new File(context.getCacheDir(), "maml-cache-" + candidate.id + ".mtz");
            zipDirectory(candidate.source, archive);
            temporary = true;
        }
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                archive, ParcelFileDescriptor.MODE_READ_ONLY)) {
            Bundle request = new Bundle();
            request.putString("name", candidate.name);
            request.putString("fingerprint", candidate.fingerprint);
            request.putParcelable("archive", descriptor);
            Bundle result = context.getContentResolver().call(
                    Contract.PROVIDER_URI, "publish_maml_cache", candidate.id, request);
            if (result == null || !result.getBoolean("ok")) {
                throw new IOException(result == null
                        ? "缓存小部件同步失败" : result.getString("message", "缓存小部件同步失败"));
            }
        } finally {
            if (temporary) archive.delete();
        }
    }

    private static void zipDirectory(File root, File output) throws Exception {
        File parent = output.getParentFile();
        if (parent != null) parent.mkdirs();
        File temporary = new File(output.getParentFile(), output.getName() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(temporary, false))) {
            long[] total = {0};
            int[] count = {0};
            addDirectory(zip, root, root, total, count);
        } catch (Throwable error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(output)) {
            temporary.delete();
            throw new IOException("无法打包缓存小部件");
        }
    }

    private static void addDirectory(ZipOutputStream zip, File root, File directory,
                                     long[] total, int[] count) throws Exception {
        if (count[0] > MAX_SCAN_FILES) throw new IOException("缓存小部件文件过多");
        File[] children = directory.listFiles();
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getName,
                String.CASE_INSENSITIVE_ORDER));
        byte[] buffer = new byte[64 * 1024];
        for (File child : children) {
            if (isSymlink(child)) continue;
            if (child.isDirectory()) {
                String name = root.toPath().relativize(child.toPath()).toString()
                        .replace(File.separatorChar, '/') + "/";
                zip.putNextEntry(new ZipEntry(name));
                zip.closeEntry();
                count[0]++;
                addDirectory(zip, root, child, total, count);
                continue;
            }
            if (!child.isFile()) continue;
            String name = root.toPath().relativize(child.toPath()).toString()
                    .replace(File.separatorChar, '/');
            zip.putNextEntry(new ZipEntry(name));
            try (FileInputStream input = new FileInputStream(child)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total[0] += read;
                    if (total[0] > MamlImporter.MAX_ZIP_BYTES) {
                        throw new IOException("缓存小部件过大，不支持导入");
                    }
                    zip.write(buffer, 0, read);
                }
            }
            zip.closeEntry();
            count[0]++;
        }
    }

    private static boolean isSymlink(File file) {
        try { return java.nio.file.Files.isSymbolicLink(file.toPath()); }
        catch (Throwable ignored) { return false; }
    }

    private static String sha256(String value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private MamlCacheScanner() {}
}

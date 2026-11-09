package com.lucky.mixflipouter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Packages provider snapshots without reformatting JSONL records. */
final class LogArchiveExporter {
    private static final String LOG_NAME = "widgetenhancer\\.log(?:\\.[1-4])?";

    static JSONObject write(File outputZip, File snapshotDirectory, JSONArray fileMetadata,
                            String moduleVersion, String flushWarning) throws Exception {
        File parent = outputZip.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建临时导出目录");
        }
        long exportedAt = System.currentTimeMillis();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                new FileOutputStream(outputZip)))) {
            JSONArray manifestFiles = addSnapshotFiles(zip, snapshotDirectory, fileMetadata,
                    "", false);
            int exported = countExported(manifestFiles);
            JSONObject manifest = new JSONObject()
                    .put("format", "mixflip-runtime-logs")
                    .put("schemaVersion", 1)
                    .put("logSystemVersion", "1")
                    .put("moduleVersion", moduleVersion == null ? "unknown" : moduleVersion)
                    .put("recordFormat", "JSONL (original bytes)")
                    .put("exportedAt", Instant.ofEpochMilli(exportedAt).toString())
                    .put("exportedAtEpochMs", exportedAt)
                    .put("totalFileCount", manifestFiles.length())
                    .put("exportedFileCount", exported)
                    .put("flushWarning", flushWarning == null ? "" : flushWarning)
                    .put("files", manifestFiles);
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (Throwable error) {
            if (outputZip.exists()) outputZip.delete();
            throw error;
        }
        return new JSONObject().put("exportedAtEpochMs", exportedAt)
                .put("fileCount", fileMetadata.length()).put("totalEntries", fileMetadata.length());
    }

    /** Adds the stable snapshot files. Strict callers abort on any unavailable file. */
    static JSONArray addSnapshotFiles(ZipOutputStream zip, File snapshotDirectory,
                                      JSONArray fileMetadata, String entryPrefix,
                                      boolean requireEveryFile) throws Exception {
        JSONArray manifestFiles = new JSONArray();
        Set<String> seen = new HashSet<>();
        File canonicalDirectory = snapshotDirectory.getCanonicalFile();
        for (int i = 0; i < fileMetadata.length(); i++) {
            JSONObject source = fileMetadata.optJSONObject(i);
            if (source == null) continue;
            JSONObject status = baseStatus(source);
            String name = source.optString("name", "");
            if (!name.matches(LOG_NAME) || !seen.add(name)) {
                fail(status, "invalid_name", "Unexpected or duplicate log file name");
                manifestFiles.put(status);
                if (requireEveryFile) throw new IOException("日志快照文件名无效：" + name);
                continue;
            }
            if (!"ready".equals(source.optString("status", "ready"))) {
                fail(status, source.optString("status", "snapshot_failed"),
                        source.optString("error", "Snapshot unavailable"));
                manifestFiles.put(status);
                if (requireEveryFile) throw new IOException("日志快照不可用：" + name);
                continue;
            }
            long expectedBytes = source.optLong("bytes", -1);
            File file = new File(canonicalDirectory, name).getCanonicalFile();
            if (!canonicalDirectory.equals(file.getParentFile()) || !file.isFile()
                    || expectedBytes < 0 || file.length() < expectedBytes) {
                fail(status, "read_failed", "Snapshot file is missing or shorter than expected");
                manifestFiles.put(status);
                if (requireEveryFile) throw new IOException("读取日志快照失败：" + name);
                continue;
            }
            long[] range = timestampRange(file, expectedBytes);
            if (range[0] > 0) status.put("firstTimestamp", range[0]);
            if (range[1] > 0) status.put("lastTimestamp", range[1]);
            InputStream opened;
            try {
                opened = new BufferedInputStream(new FileInputStream(file));
            } catch (Throwable error) {
                fail(status, "read_failed", error.getClass().getSimpleName() + ": "
                        + (error.getMessage() == null ? "open failed" : error.getMessage()));
                manifestFiles.put(status);
                if (requireEveryFile) throw new IOException("打开日志快照失败：" + name, error);
                continue;
            }
            try (InputStream input = opened) {
                zip.putNextEntry(new ZipEntry(entryPrefix + name));
                long copied = copyExactly(input, zip, expectedBytes);
                zip.closeEntry();
                status.put("status", "exported")
                        .put("entryIncluded", true)
                        .put("exportedBytes", copied)
                        .put("sizeBytes", copied);
            } catch (Throwable error) {
                // Once a ZIP entry has started it cannot be removed safely. Abort and delete
                // the entire archive instead of returning a successful-looking partial entry.
                throw new IOException("日志 ZIP 写入失败：" + name, error);
            }
            manifestFiles.put(status);
        }
        return manifestFiles;
    }

    private static JSONObject baseStatus(JSONObject source) throws Exception {
        return new JSONObject().put("name", source.optString("name", ""))
                .put("sourceBytes", source.optLong("bytes", 0))
                .put("exportedBytes", 0).put("sizeBytes", 0)
                .put("entryIncluded", false)
                .put("firstTimestamp", JSONObject.NULL)
                .put("lastTimestamp", JSONObject.NULL);
    }

    private static void fail(JSONObject status, String state, String error) throws Exception {
        status.put("status", state).put("error", error);
    }

    private static int countExported(JSONArray files) {
        int count = 0;
        for (int i = 0; i < files.length(); i++) {
            if ("exported".equals(files.optJSONObject(i).optString("status"))) count++;
        }
        return count;
    }

    private static long copyExactly(InputStream input, ZipOutputStream zip, long expectedBytes)
            throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long remaining = expectedBytes;
        long copied = 0;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new IOException("Snapshot ended before its captured length");
            if (count == 0) continue;
            zip.write(buffer, 0, count);
            copied += count;
            remaining -= count;
        }
        return copied;
    }

    private static long[] timestampRange(File file, long bytes) {
        long first = Long.MAX_VALUE;
        long last = 0;
        try (InputStream raw = new LimitedInputStream(new FileInputStream(file), bytes);
             BufferedReader reader = new BufferedReader(new InputStreamReader(raw,
                     StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    long timestamp = new JSONObject(line).optLong("timestamp", 0);
                    if (timestamp > 0) {
                        first = Math.min(first, timestamp);
                        last = Math.max(last, timestamp);
                    }
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return new long[]{first == Long.MAX_VALUE ? 0 : first, last};
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream input, long length) {
            super(input);
            remaining = Math.max(0, length);
        }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value >= 0) remaining--;
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int count = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }

    private LogArchiveExporter() { }
}

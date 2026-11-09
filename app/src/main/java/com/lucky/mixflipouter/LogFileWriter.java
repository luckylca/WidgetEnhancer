package com.lucky.mixflipouter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/** Single-process JSONL writer with bounded five-file rotation. */
final class LogFileWriter {
    static final String BASE_NAME = "widgetenhancer.log";
    static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;
    static final int ROTATED_FILES = 4;
    private static final String TAG = "WidgetEnhancerLogFile";
    private final File directory;
    private final long maxFileBytes;
    private final LinkedHashSet<String> knownEventIds = new LinkedHashSet<>();
    private static final int EVENT_ID_CACHE_LIMIT = 32768;

    LogFileWriter(File filesDir) throws IOException {
        this(filesDir, MAX_FILE_BYTES);
    }

    LogFileWriter(File filesDir, long maxFileBytes) throws IOException {
        if (maxFileBytes <= 0) throw new IllegalArgumentException("maxFileBytes must be positive");
        this.maxFileBytes = maxFileBytes;
        directory = new File(filesDir, "logs");
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create private logs directory");
        }
        rebuildEventIndex();
    }

    synchronized void append(List<String> lines, boolean urgent) throws IOException {
        if (lines == null || lines.isEmpty()) return;
        List<String> uniqueLines = new ArrayList<>(lines.size());
        Set<String> batchEventIds = new java.util.HashSet<>();
        for (String line : lines) {
            String eventId = eventId(line);
            if (eventId != null && (knownEventIds.contains(eventId)
                    || !batchEventIds.add(eventId))) continue;
            uniqueLines.add(line);
        }
        if (uniqueLines.isEmpty()) return;
        File active = file(BASE_NAME);
        long projected = active.length();
        for (String line : uniqueLines) {
            projected += line.getBytes(StandardCharsets.UTF_8).length + 1;
        }
        if (active.length() > 0 && projected > maxFileBytes) {
            rotate();
            rebuildEventIndex();
        }
        try (FileOutputStream raw = new FileOutputStream(file(BASE_NAME), true);
             BufferedOutputStream out = new BufferedOutputStream(raw, 32 * 1024)) {
            for (String line : uniqueLines) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                rememberEventId(line);
            }
            out.flush();
            if (urgent) raw.getFD().sync();
        }
    }

    synchronized void flush() throws IOException {
        // Each append closes and flushes its stream; opening the active file also checks access.
        File active = file(BASE_NAME);
        if (active.exists()) {
            try (FileOutputStream out = new FileOutputStream(active, true)) {
                out.flush();
            }
        }
    }

    synchronized List<File> listFiles() {
        List<File> result = new ArrayList<>();
        for (int i = ROTATED_FILES; i >= 1; i--) {
            File rotated = file(BASE_NAME + "." + i);
            if (rotated.isFile()) result.add(rotated);
        }
        File active = file(BASE_NAME);
        if (active.isFile()) result.add(active);
        return result;
    }

    /** Copies a consistent, immutable export snapshot while serializing against rotation. */
    synchronized JSONArray snapshotFiles(File destination) throws Exception {
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("Cannot create log export snapshot directory");
        }
        JSONArray output = new JSONArray();
        for (File source : listFiles()) {
            JSONObject item = new JSONObject();
            try {
                File snapshot = new File(destination, source.getName());
                if (snapshot.exists() && !snapshot.delete()) {
                    throw new IOException("Cannot replace snapshot file");
                }
                try {
                    Files.createLink(snapshot.toPath(), source.toPath());
                } catch (Throwable hardLinkUnavailable) {
                    Files.copy(source.toPath(), snapshot.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                item.put("name", source.getName());
                item.put("bytes", source.length());
                item.put("status", "ready");
            } catch (Throwable error) {
                item.put("name", source.getName());
                item.put("bytes", source.length());
                item.put("status", "snapshot_failed");
                item.put("error", error.getClass().getSimpleName() + ": "
                        + (error.getMessage() == null ? "copy failed" : error.getMessage()));
            }
            output.put(item);
        }
        return output;
    }

    synchronized File findFile(String name) {
        if (name == null || !(BASE_NAME.equals(name) || name.matches(
                "widgetenhancer\\.log\\.[1-4]"))) return null;
        File candidate = file(name);
        return candidate.isFile() ? candidate : null;
    }

    synchronized boolean containsEventId(String eventId) {
        return eventId != null && !eventId.isEmpty() && knownEventIds.contains(eventId);
    }

    synchronized byte[] readChunk(String name, long offset, int length) throws IOException {
        File source = findFile(name);
        if (source == null || offset < 0 || length < 0 || length > 128 * 1024) {
            throw new IOException("Invalid log read request");
        }
        byte[] buffer = new byte[(int) Math.min(length, Math.max(0, source.length() - offset))];
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source))) {
            long remaining = offset;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) break;
                remaining -= skipped;
            }
            int count = 0;
            while (count < buffer.length) {
                int read = in.read(buffer, count, buffer.length - count);
                if (read < 0) break;
                count += read;
            }
            if (count == buffer.length) return buffer;
            byte[] actual = new byte[count];
            System.arraycopy(buffer, 0, actual, 0, count);
            return actual;
        }
    }

    synchronized List<String> readRecentLines(int limit) throws IOException {
        ArrayDeque<String> lines = new ArrayDeque<>(Math.max(1, limit));
        for (File file : listFiles()) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (limit <= 0) continue;
                    while (lines.size() >= limit) lines.removeFirst();
                    lines.addLast(line);
                }
            }
        }
        return new ArrayList<>(lines);
    }

    private File file(String name) { return new File(directory, name); }

    private void rebuildEventIndex() throws IOException {
        knownEventIds.clear();
        for (File source : listFiles()) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(source),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) rememberEventId(line);
            }
        }
    }

    private void rememberEventId(String line) {
        try {
            String id = new org.json.JSONObject(line).optString("eventId", "");
            if (id.isEmpty()) return;
            knownEventIds.remove(id);
            knownEventIds.add(id);
            while (knownEventIds.size() > EVENT_ID_CACHE_LIMIT) {
                java.util.Iterator<String> iterator = knownEventIds.iterator();
                iterator.next();
                iterator.remove();
            }
        } catch (org.json.JSONException ignored) { }
    }

    private static String eventId(String line) {
        try {
            String value = new org.json.JSONObject(line).optString("eventId", "");
            return value.isEmpty() ? null : value;
        } catch (org.json.JSONException ignored) { return null; }
    }

    private void rotate() throws IOException {
        for (int i = ROTATED_FILES; i >= 1; i--) {
            File source = file(i == 1 ? BASE_NAME : BASE_NAME + "." + (i - 1));
            File target = file(BASE_NAME + "." + i);
            if (target.exists() && !target.delete()) {
                throw new IOException("Cannot delete old rotated log");
            }
            if (source.exists() && !source.renameTo(target)) {
                throw new IOException("Cannot rotate active log");
            }
        }
    }
}

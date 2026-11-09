package com.lucky.mixflipouter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Bounded, durable handoff store used while the main logger endpoint is unavailable. */
final class LogFallbackStore {
    static final long MAX_BYTES = 4L * 1024L * 1024L;
    static final int MAX_EVENTS = 4096;
    private static final Object LOCK = new Object();
    private static final String PENDING = "pending.jsonl";
    private static final String STATS = "stats.json";

    static JSONObject append(File directory, List<String> input, String reason) throws Exception {
        synchronized (LOCK) {
            ensureDirectory(directory);
            List<String> rows = readRows(directory);
            Set<String> ids = eventIds(rows);
            long bytes = byteCount(rows);
            long stored = 0;
            long lost = 0;
            String lossReason = normalizeReason(reason);
            for (String original : input) {
                String line = withEventId(original);
                String id = eventId(line);
                if (id != null && ids.contains(id)) {
                    stored++;
                    continue;
                }
                long lineBytes = utf8Bytes(line);
                if (lineBytes > MAX_BYTES) {
                    lost++;
                    continue;
                }
                while ((rows.size() >= MAX_EVENTS || bytes + lineBytes > MAX_BYTES)
                        && isPriority(line)) {
                    int removable = findLowPriority(rows);
                    if (removable < 0) break;
                    String removed = rows.remove(removable);
                    bytes -= utf8Bytes(removed) + 1;
                    String removedId = eventId(removed);
                    if (removedId != null) ids.remove(removedId);
                    lost++;
                    lossReason = "fallback_capacity_eviction";
                }
                if (rows.size() >= MAX_EVENTS || bytes + lineBytes > MAX_BYTES) {
                    lost++;
                    continue;
                }
                rows.add(line);
                bytes += lineBytes + 1;
                if (id != null) ids.add(id);
                stored++;
            }
            writeRows(directory, rows);
            if (lost > 0) incrementLoss(directory, lossReason, lost);
            JSONObject result = new JSONObject().put("accepted", stored == input.size())
                    .put("storedCount", stored).put("lostCount", lost)
                    .put("pendingCount", rows.size()).put("pendingBytes", bytes);
            if (lost > 0) result.put("lossReason", lossReason);
            return result;
        }
    }

    static JSONObject peek(File directory, int maxBytes) throws Exception {
        synchronized (LOCK) {
            List<String> rows = readRows(directory);
            JSONArray selected = new JSONArray();
            JSONArray ids = new JSONArray();
            long bytes = 0;
            for (String line : rows) {
                long size = utf8Bytes(line) + 1;
                if (size > maxBytes && selected.length() == 0) {
                    // A single event is bounded to 512 KiB and may exceed a smaller request.
                    if (maxBytes < LogBatchCodec.MAX_EVENT_BYTES + 1024) break;
                }
                if (bytes + size > Math.max(maxBytes, LogBatchCodec.MAX_EVENT_BYTES + 1024L)) break;
                selected.put(line);
                String id = eventId(line);
                if (id != null) ids.put(id);
                bytes += size;
            }
            return new JSONObject().put("events", selected).put("eventIds", ids)
                    .put("selectedBytes", bytes).put("pendingCount", rows.size());
        }
    }

    static JSONObject acknowledge(File directory, Set<String> acknowledged) throws Exception {
        synchronized (LOCK) {
            List<String> rows = readRows(directory);
            List<String> remaining = new ArrayList<>(rows.size());
            long removed = 0;
            for (String line : rows) {
                String id = eventId(line);
                if (id != null && acknowledged.contains(id)) removed++;
                else remaining.add(line);
            }
            writeRows(directory, remaining);
            return new JSONObject().put("removed", removed)
                    .put("pendingCount", remaining.size()).put("pendingBytes", byteCount(remaining));
        }
    }

    static JSONObject stats(File directory) throws Exception {
        synchronized (LOCK) {
            List<String> rows = readRows(directory);
            JSONObject stats = readStats(directory);
            stats.put("pendingCount", rows.size()).put("pendingBytes", byteCount(rows));
            return stats;
        }
    }

    static JSONObject recordLoss(File directory, String reason, long count) throws Exception {
        synchronized (LOCK) {
            ensureDirectory(directory);
            incrementLoss(directory, reason, count);
            return stats(directory);
        }
    }

    private static void ensureDirectory(File directory) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new java.io.IOException("Cannot create persistent log fallback directory");
        }
    }

    private static List<String> readRows(File directory) throws Exception {
        List<String> rows = new ArrayList<>();
        File source = new File(directory, PENDING);
        if (!source.isFile()) return rows;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                try { new JSONObject(line); rows.add(line); }
                catch (Throwable ignored) { incrementLoss(directory, "fallback_corrupt_line", 1); }
            }
        }
        return rows;
    }

    private static void writeRows(File directory, List<String> rows) throws Exception {
        File target = new File(directory, PENDING);
        File temporary = new File(directory, PENDING + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            for (String row : rows) {
                output.write(row.getBytes(StandardCharsets.UTF_8));
                output.write('\n');
            }
            output.flush();
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable error) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JSONObject readStats(File directory) {
        File file = new File(directory, STATS);
        if (!file.isFile()) return new JSONObject();
        try {
            return new JSONObject(new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8));
        } catch (Throwable ignored) { return new JSONObject(); }
    }

    private static void writeStats(File directory, JSONObject stats) throws Exception {
        File temporary = new File(directory, STATS + ".tmp");
        File target = new File(directory, STATS);
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(stats.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable error) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void incrementLoss(File directory, String reason, long count) throws Exception {
        if (count <= 0) return;
        JSONObject stats = readStats(directory);
        JSONObject reasons = stats.optJSONObject("lossesByReason");
        if (reasons == null) reasons = new JSONObject();
        String key = normalizeReason(reason);
        reasons.put(key, reasons.optLong(key, 0) + count);
        stats.put("lossCount", stats.optLong("lossCount", 0) + count)
                .put("lastLossReason", key)
                .put("lastLossAtEpochMs", System.currentTimeMillis())
                .put("lossesByReason", reasons);
        writeStats(directory, stats);
    }

    private static String withEventId(String line) throws Exception {
        JSONObject row = new JSONObject(line);
        if (row.optString("eventId", "").isEmpty()) row.put("eventId", UUID.randomUUID().toString());
        return row.toString();
    }

    private static Set<String> eventIds(List<String> rows) {
        Set<String> ids = new HashSet<>();
        for (String row : rows) {
            String id = eventId(row);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static String eventId(String line) {
        try {
            String id = new JSONObject(line).optString("eventId", "");
            return id.isEmpty() ? null : id;
        } catch (Throwable ignored) { return null; }
    }

    private static int findLowPriority(List<String> rows) {
        for (int i = 0; i < rows.size(); i++) if (!isPriority(rows.get(i))) return i;
        return -1;
    }

    private static boolean isPriority(String line) {
        try {
            JSONObject row = new JSONObject(line);
            String level = row.optString("level");
            String event = row.optString("eventType");
            return "WARN".equals(level) || "ERROR".equals(level)
                    || "hook_failure".equals(event)
                    || ("feature_state".equals(event)
                    && row.optString("message").contains("FAILED"));
        } catch (Throwable ignored) { return false; }
    }

    private static long byteCount(List<String> rows) {
        long bytes = 0;
        for (String row : rows) bytes += utf8Bytes(row) + 1;
        return bytes;
    }

    private static long utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) return "unspecified";
        return reason.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private LogFallbackStore() { }
}

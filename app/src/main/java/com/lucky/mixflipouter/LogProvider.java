package com.lucky.mixflipouter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/** Independent, authenticated, single-writer endpoint for module runtime logs. */
public final class LogProvider extends ContentProvider {
    static final Uri URI = Uri.parse("content://com.lucky.mixflipouter.logs");
    private static final String TAG = "WidgetEnhancerLogProvider";
    private static final int QUEUE_CAPACITY = 2048;
    private static final long QUEUE_MAX_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_BATCH = 64;
    private static final int MAX_COMPRESSED_BATCH_BYTES = 900 * 1024;
    private static final int MAX_UNCOMPRESSED_BATCH_BYTES = 1024 * 1024;
    private static final int EVENT_ACK_CAPACITY = 8192;
    private static final int FALLBACK_CAPACITY = 64;
    private static final long FALLBACK_MAX_BYTES = 2L * 1024L * 1024L;
    private static final String[] TARGET_PACKAGES = {
            "com.lucky.mixflipouter", "com.miui.fliphome", "android",
            "com.tencent.wetype", "com.bytedance.android.doubaoime",
            "com.android.systemui", "com.miui.voiceassist", "com.netease.cloudmusic",
            "com.miui.home", "com.miui.personalassistant"
    };
    private final Object queueLock = new Object();
    private final Object drainLock = new Object();
    private final Object eventLock = new Object();
    private final ArrayDeque<LogRecord> queue = new ArrayDeque<>();
    private final ArrayDeque<String> fallback = new ArrayDeque<>();
    private final LinkedHashMap<String, Boolean> eventAcks = new LinkedHashMap<>(256, 0.75f, true);
    private long fallbackBytes;
    private ScheduledExecutorService writerExecutor;
    private Context deviceContext;
    private volatile LogFileWriter writer;
    private long dropped;
    private long writeFailures;
    private long overflowCount;
    private long queuedBytes;
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong persistedCount = new AtomicLong();
    private volatile long lastFallbackAt;
    private final AtomicBoolean fallbackReplayRunning = new AtomicBoolean();

    @Override public boolean onCreate() {
        try {
            Context base = getContext();
            deviceContext = base.createDeviceProtectedStorageContext();
            try { writer = new LogFileWriter(deviceContext.getFilesDir()); }
            catch (Throwable error) { fallback("writer_init_failed", error); }
            writerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "widgetenhancer-log-writer");
                thread.setDaemon(true);
                return thread;
            });
            writerExecutor.scheduleWithFixedDelay(this::drainQuietly, 250, 250,
                    TimeUnit.MILLISECONDS);
            writerExecutor.scheduleWithFixedDelay(this::replayFallbackQuietly, 1000, 1500,
                    TimeUnit.MILLISECONDS);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Log provider initialization failed", error);
            return false;
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("ingest".equals(method)) return ingest(extras);
        enforceModuleCaller();
        try {
            if ("flush".equals(method)) {
                drainAndFlush();
                return result("ok", true);
            }
            if ("begin_log_export".equals(method)) return beginLogExport();
            if ("end_log_export".equals(method)) return endLogExport(extras);
            if ("get_recent_logs".equals(method)) return recentLogs(extras);
            if ("get_log_files".equals(method)) return logFiles();
            if ("read_log_chunk".equals(method)) return readChunk(extras);
            if ("get_process_status".equals(method)) return processStatus();
            if ("get_hook_status".equals(method)) return hookStatus();
            if ("get_queue_stats".equals(method)) return queueStats();
        } catch (Throwable error) {
            Log.e(TAG, "Request failed: " + method, error);
            return result("error", error.getClass().getSimpleName());
        }
        return super.call(method, arg, extras);
    }

    private Bundle ingest(Bundle extras) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        String payload = extras == null ? null : extras.getString("events");
        if (extras != null && extras.getByteArray("eventsGzip") != null) {
            payload = inflate(extras.getByteArray("eventsGzip"));
        }
        if (payload == null || payload.length() > MAX_UNCOMPRESSED_BATCH_BYTES) {
            throw new IllegalArgumentException("Invalid log batch");
        }
        try {
            JSONArray rows = new JSONArray(payload);
            if (rows.length() > MAX_BATCH) throw new IllegalArgumentException("Batch too large");
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            String callingPackage = null;
            try { callingPackage = getCallingPackage(); }
            catch (Throwable ignored) { }
            String actualProcess = readCallerProcessName(pid);
            boolean acceptedAll = true;
            boolean enqueuedAny = false;
            boolean urgent = false;
            List<String> batchEventIds = new ArrayList<>();
            List<String> rawLines = new ArrayList<>(rows.length());
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row == null) continue;
                String claimedPackage = row.optString("packageName", "");
                String process = row.optString("processName", "");
                validateCallerIdentity(uid, packages, callingPackage, actualProcess,
                        claimedPackage, process);
                if (row.optString("eventId", "").isEmpty()) {
                    row.put("eventId", UUID.randomUUID().toString());
                }
                String line = row.toString();
                rawLines.add(line);
                String level = row.optString("level");
                String eventId = row.optString("eventId", "");
                boolean isUrgent = "ERROR".equals(level) || "WARN".equals(level)
                        || "hook_failure".equals(row.optString("eventType"));
                urgent |= isUrgent;
                batchEventIds.add(eventId);
                boolean accepted;
                synchronized (eventLock) {
                    Boolean knownPersisted = eventAcks.get(eventId);
                    if (Boolean.TRUE.equals(knownPersisted)) continue;
                    if (getWriter().containsEventId(eventId)) {
                        eventAcks.put(eventId, true);
                        continue;
                    }
                    if (Boolean.FALSE.equals(knownPersisted) && containsPendingEvent(eventId)) {
                        enqueuedAny = true;
                        continue;
                    }
                    accepted = enqueue(new LogRecord(line, level, eventId));
                    if (accepted) eventAcks.put(eventId, false);
                }
                acceptedAll &= accepted;
                if (accepted) {
                    enqueuedAny = true;
                    acceptedCount.incrementAndGet();
                }
                if ("logger_queue_overflow".equals(row.optString("eventType"))) {
                    android.content.SharedPreferences prefs = deviceContext.getSharedPreferences(
                            "logger_process_status", 0);
                    prefs.edit().putLong("clientQueueDrops", prefs.getLong("clientQueueDrops", 0)
                            + row.optLong("droppedCount", 1)).apply();
                }
                if ("process_start".equals(row.optString("eventType"))) {
                    updateProcessStatus(claimedPackage, process,
                            row.optLong("timestamp", 0));
                }
            }
            Bundle result = new Bundle();
            boolean persisted = false;
            boolean durableFallback = false;
            if (enqueuedAny || acceptedAll) {
                try {
                    drainAndFlush();
                    persisted = batchEventIds.isEmpty() || allPersisted(batchEventIds);
                    if (!persisted) acceptedAll = false;
                } catch (Throwable error) {
                    fallback(urgent ? "urgent_log_persistence_failed"
                            : "log_batch_persistence_failed", error);
                    durableFallback = persistFallbackLines(rawLines, "provider_write_failure");
                    acceptedAll &= durableFallback;
                }
            }
            result.putBoolean("accepted", acceptedAll);
            result.putBoolean("persisted", persisted);
            result.putBoolean("durableFallback", durableFallback);
            if (!acceptedAll) result.putString("failureReason", "provider_queue_or_write_rejected");
            result.putInt("received", rows.length());
            return result;
        } catch (SecurityException | IllegalArgumentException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalArgumentException("Malformed log batch", error);
        }
    }

    static void validateCallerIdentity(int uid, String[] packages, String callingPackage,
                                       String actualProcess, String claimed, String process) {
        LogCallerIdentity.validate(uid, packages, callingPackage, actualProcess, claimed, process);
    }

    private String readCallerProcessName(int pid) {
        if (pid <= 0) return null;
        try (FileInputStream input = new FileInputStream("/proc/" + pid + "/cmdline")) {
            byte[] bytes = new byte[256];
            int count = input.read(bytes);
            if (count <= 0) return null;
            int length = 0;
            while (length < count && bytes[length] != 0) length++;
            return new String(bytes, 0, length, StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean enqueue(LogRecord record) {
        synchronized (queueLock) {
            long recordBytes = record.line.getBytes(StandardCharsets.UTF_8).length;
            if (recordBytes > QUEUE_MAX_BYTES) {
                dropped++;
                overflowCount++;
                fallback("oversized_provider_event bytes=" + recordBytes, null);
                return false;
            }
            if (queue.size() >= QUEUE_CAPACITY || queuedBytes + recordBytes > QUEUE_MAX_BYTES) {
                overflowCount++;
                if (record.priority()) {
                    Iterator<LogRecord> iterator = queue.iterator();
                    boolean evicted = false;
                    while (iterator.hasNext()) {
                        LogRecord old = iterator.next();
                        if (!old.priority()) {
                            iterator.remove();
                            queuedBytes -= old.line.getBytes(StandardCharsets.UTF_8).length;
                            dropped++;
                            evicted = true;
                            break;
                        }
                    }
                    if (!evicted) {
                        dropped++;
                        fallback("priority_queue_overflow", null);
                        return false;
                    }
                } else {
                    dropped++;
                    return false;
                }
                fallback("queue_overflow dropped=" + dropped, null);
            }
            queue.addLast(record);
            queuedBytes += recordBytes;
            return true;
        }
    }

    private Bundle beginLogExport() throws Exception {
        String flushWarning = "";
        try { drainAndFlush(); }
        catch (Throwable error) {
            flushWarning = error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "flush failed" : error.getMessage());
            fallback("log_export_flush_failed", error);
        }
        File root = new File(deviceContext.getCacheDir(), "log_export_snapshots");
        pruneExportSnapshots(root);
        String exportId = UUID.randomUUID().toString();
        File directory = new File(root, exportId);
        JSONArray files = getWriter().snapshotFiles(directory);
        Bundle response = new Bundle();
        response.putString("exportId", exportId);
        response.putString("snapshotPath", directory.getAbsolutePath());
        response.putString("files", files.toString());
        response.putString("flushWarning", flushWarning);
        return response;
    }

    private Bundle endLogExport(Bundle extras) {
        String exportId = extras == null ? "" : extras.getString("exportId", "");
        if (!exportId.matches("[A-Fa-f0-9-]{36}")) {
            throw new IllegalArgumentException("Invalid export id");
        }
        File root = new File(deviceContext.getCacheDir(), "log_export_snapshots");
        deleteTree(new File(root, exportId));
        return result("deleted", true);
    }

    private void pruneExportSnapshots(File root) {
        File[] snapshots = root.listFiles();
        if (snapshots == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (File snapshot : snapshots) {
            if (snapshot.lastModified() < cutoff) deleteTree(snapshot);
        }
    }

    private void deleteTree(File path) {
        if (path == null || !path.exists()) return;
        if (path.isDirectory()) {
            File[] children = path.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        path.delete();
    }

    private void drainQuietly() {
        try { drainBatch(false); }
        catch (Throwable error) { fallback("drain_failed", error); }
    }

    private void drainAndFlush() throws Exception {
        synchronized (drainLock) {
            while (drainBatchLocked()) { }
            LogFileWriter active = getWriter();
            if (active != null) active.flush();
        }
        replayFallbackQuietly();
    }

    private void drainBatch(boolean wait) throws Exception {
        synchronized (drainLock) { drainBatchLocked(); }
    }

    private boolean drainBatchLocked() throws Exception {
        List<LogRecord> records = new ArrayList<>(128);
        synchronized (queueLock) {
            while (!queue.isEmpty() && records.size() < 128) {
                LogRecord record = queue.removeFirst();
                records.add(record);
                queuedBytes -= record.line.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        List<String> lines = new ArrayList<>(records.size());
        boolean urgent = false;
        for (LogRecord record : records) {
            lines.add(record.line);
            urgent |= "ERROR".equals(record.level);
        }
        List<String> backup = new ArrayList<>(32);
        synchronized (fallback) {
            Iterator<String> iterator = fallback.iterator();
            while (iterator.hasNext() && backup.size() < 32) backup.add(iterator.next());
        }
        for (String line : backup) {
            lines.add(asFallbackJson(line));
            urgent = true;
        }
        if (lines.isEmpty()) return false;
        try {
            LogFileWriter active = getWriter();
            if (active == null) throw new IllegalStateException("Log writer unavailable");
            List<String> uniqueLines = new ArrayList<>(lines.size());
            for (String line : lines) {
                String eventId = eventId(line);
                if (eventId != null && active.containsEventId(eventId)) {
                    markLinePersisted(line);
                } else {
                    uniqueLines.add(line);
                }
            }
            if (!uniqueLines.isEmpty()) active.append(uniqueLines, urgent);
            persistedCount.addAndGet(records.size());
            for (String line : lines) markLinePersisted(line);
            if (!backup.isEmpty()) synchronized (fallback) {
                for (int i = 0; i < backup.size() && !fallback.isEmpty(); i++) {
                    fallbackBytes -= fallback.removeFirst().getBytes(StandardCharsets.UTF_8).length;
                }
            }
            return !isQueueEmpty();
        } catch (Throwable error) {
            writeFailures++;
            for (LogRecord record : records) addFallback(record.line);
            List<String> durableLines = new ArrayList<>(records.size());
            for (LogRecord record : records) durableLines.add(record.line);
            if (!durableLines.isEmpty()) {
                persistFallbackLines(durableLines, "provider_file_write_failure");
            }
            if (writeFailures == 1 || writeFailures % 20 == 0) {
                fallback("file_write_failed count=" + writeFailures, error);
            }
            throw error;
        }
    }

    private boolean isQueueEmpty() { synchronized (queueLock) { return queue.isEmpty(); } }

    private boolean containsPendingEvent(String eventId) {
        synchronized (queueLock) {
            for (LogRecord record : queue) if (eventId.equals(record.eventId)) return true;
        }
        synchronized (fallback) {
            for (String line : fallback) if (eventId.equals(eventId(line))) return true;
        }
        return false;
    }

    private String eventId(String line) {
        try { return new JSONObject(line).optString("eventId", ""); }
        catch (Throwable ignored) { return ""; }
    }

    private boolean persistFallbackLines(List<String> lines, String reason) {
        if (lines == null || lines.isEmpty()) return true;
        try {
            JSONObject stored = LogFallbackStore.append(localFallbackDirectory(), lines, reason);
            return stored.optBoolean("accepted", false)
                    && stored.optLong("storedCount", -1) == lines.size();
        } catch (Throwable error) {
            fallback("persistent_fallback_unavailable reason=" + reason, error);
            return false;
        }
    }

    private File localFallbackDirectory() {
        return new File(deviceContext.getFilesDir(), "logger_fallback");
    }

    private void replayFallbackQuietly() {
        // First recover the logger's own device-protected durable store. This path is
        // available during Direct Boot and after the logger process restarts.
        try { replayLocalFallback(); }
        catch (Throwable error) {
            fallback("local_persistent_fallback_replay_failed", error);
            return;
        }
        // Keep compatibility with fallback rows written by previous module builds.
        // ConfigProvider is not Direct-Boot-aware even though its backing store is DE.
        try {
            android.os.UserManager manager = (android.os.UserManager)
                    deviceContext.getSystemService(Context.USER_SERVICE);
            if (manager != null && !manager.isUserUnlocked()) return;
        } catch (Throwable error) {
            return;
        }
        if (!fallbackReplayRunning.compareAndSet(false, true)) return;
        try {
            Bundle request = new Bundle();
            request.putInt("maxBytes", 512 * 1024);
            Bundle response = deviceContext.getContentResolver().call(Contract.PROVIDER_URI,
                    "read_logger_fallback", null, request);
            if (response == null) return;
            JSONObject pending = new JSONObject(response.getString("fallback", "{}"));
            JSONArray events = pending.optJSONArray("events");
            if (events == null || events.length() == 0) return;
            List<String> rows = new ArrayList<>(events.length());
            Set<String> idsToAck = new HashSet<>();
            boolean urgent = false;
            LogFileWriter active = getWriter();
            for (int i = 0; i < events.length(); i++) {
                String line = events.optString(i, "");
                JSONObject row = new JSONObject(line);
                String id = row.optString("eventId", "");
                if (id.isEmpty()) continue;
                idsToAck.add(id);
                if (!active.containsEventId(id)) rows.add(line);
                String level = row.optString("level");
                urgent |= "ERROR".equals(level) || "WARN".equals(level)
                        || "hook_failure".equals(row.optString("eventType"));
            }
            synchronized (drainLock) {
                List<String> newRows = new ArrayList<>(rows.size());
                for (String row : rows) {
                    if (!active.containsEventId(eventId(row))) newRows.add(row);
                }
                if (!newRows.isEmpty()) active.append(newRows, urgent);
                active.flush();
                persistedCount.addAndGet(newRows.size());
                for (String id : idsToAck) {
                    rememberEvent(id, true);
                }
            }
            Bundle ack = new Bundle();
            JSONArray ids = new JSONArray();
            for (String id : idsToAck) ids.put(id);
            ack.putString("eventIds", ids.toString());
            deviceContext.getContentResolver().call(Contract.PROVIDER_URI,
                    "ack_logger_fallback", null, ack);
        } catch (Throwable error) {
            fallback("persistent_fallback_replay_failed", error);
        } finally {
            fallbackReplayRunning.set(false);
        }
    }

    private void replayLocalFallback() throws Exception {
        if (!fallbackReplayRunning.compareAndSet(false, true)) return;
        try {
            JSONObject pending = LogFallbackStore.peek(localFallbackDirectory(), 512 * 1024);
            JSONArray events = pending.optJSONArray("events");
            if (events == null || events.length() == 0) return;
            List<String> rows = new ArrayList<>(events.length());
            Set<String> idsToAck = new HashSet<>();
            boolean urgent = false;
            LogFileWriter active = getWriter();
            for (int i = 0; i < events.length(); i++) {
                String line = events.optString(i, "");
                JSONObject row = new JSONObject(line);
                String id = row.optString("eventId", "");
                if (id.isEmpty()) continue;
                idsToAck.add(id);
                if (!active.containsEventId(id)) rows.add(line);
                String level = row.optString("level");
                urgent |= "ERROR".equals(level) || "WARN".equals(level)
                        || "hook_failure".equals(row.optString("eventType"));
            }
            synchronized (drainLock) {
                List<String> uniqueRows = new ArrayList<>(rows.size());
                for (String row : rows) {
                    if (!active.containsEventId(eventId(row))) uniqueRows.add(row);
                }
                if (!uniqueRows.isEmpty()) active.append(uniqueRows, urgent);
                active.flush();
                persistedCount.addAndGet(uniqueRows.size());
                for (String id : idsToAck) rememberEvent(id, true);
            }
            LogFallbackStore.acknowledge(localFallbackDirectory(), idsToAck);
        } finally {
            fallbackReplayRunning.set(false);
        }
    }

    private LogFileWriter getWriter() throws Exception {
        LogFileWriter active = writer;
        if (active != null) return active;
        synchronized (this) {
            if (writer == null) writer = new LogFileWriter(deviceContext.getFilesDir());
            return writer;
        }
    }

    private synchronized void updateProcessStatus(String pkg, String process, long timestamp) {
        try {
            int boot = Settings.Global.getInt(deviceContext.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
            if (boot < 0) return;
            String prefix = "process." + pkg + ".";
            android.content.SharedPreferences prefs = deviceContext.getSharedPreferences(
                    "logger_process_status", 0);
            int oldBoot = prefs.getInt(prefix + "boot", -1);
            long now = timestamp > 0 ? timestamp : System.currentTimeMillis();
            int loads = oldBoot == boot ? prefs.getInt(prefix + "loadsThisBoot", 0) : 0;
            Set<String> allProcesses = new HashSet<>(prefs.getStringSet(
                    prefix + "observedProcesses", new HashSet<>()));
            Set<String> thisBootProcesses = oldBoot == boot
                    ? new HashSet<>(prefs.getStringSet(prefix + "observedProcessesThisBoot",
                            new HashSet<>())) : new HashSet<>();
            allProcesses.add(process);
            thisBootProcesses.add(process);
            android.content.SharedPreferences.Editor edit = prefs.edit();
            if (oldBoot != boot) {
                edit.putLong(prefix + "firstThisBoot", now);
            }
            if (!prefs.contains(prefix + "firstEver")) edit.putLong(prefix + "firstEver", now);
            edit.putLong(prefix + "last", now).putInt(prefix + "boot", boot)
                    .putInt(prefix + "loadsThisBoot", loads + 1)
                    .putStringSet(prefix + "observedProcesses", allProcesses)
                    .putStringSet(prefix + "observedProcessesThisBoot", thisBootProcesses)
                    .apply();
        } catch (Throwable error) { fallback("process_status_update_failed", error); }
    }

    private Bundle processStatus() throws Exception {
        int boot = Settings.Global.getInt(deviceContext.getContentResolver(),
                Settings.Global.BOOT_COUNT, -1);
        android.content.SharedPreferences prefs = deviceContext.getSharedPreferences(
                "logger_process_status", 0);
        JSONArray output = new JSONArray();
        for (String pkg : TARGET_PACKAGES) {
            String prefix = "process." + pkg + ".";
            int reportedBoot = prefs.getInt(prefix + "boot", -1);
            JSONArray observedProcesses = new JSONArray();
            for (String process : prefs.getStringSet(prefix + "observedProcesses",
                    new HashSet<>())) observedProcesses.put(process);
            JSONArray observedProcessesThisBoot = new JSONArray();
            if (boot >= 0 && boot == reportedBoot) {
                for (String process : prefs.getStringSet(prefix + "observedProcessesThisBoot",
                        new HashSet<>())) observedProcessesThisBoot.put(process);
            }
            JSONObject item = new JSONObject().put("packageName", pkg)
                    .put("observedThisBoot", boot >= 0 && boot == reportedBoot)
                    .put("bootCountKnown", boot >= 0)
                    .put("firstObservedAt", prefs.getLong(prefix + "firstEver", 0))
                    .put("firstObservedThisBoot", prefs.getLong(prefix + "firstThisBoot", 0))
                    .put("lastObservedAt", prefs.getLong(prefix + "last", 0))
                    .put("loadsThisBoot", boot >= 0 && boot == reportedBoot
                            ? prefs.getInt(prefix + "loadsThisBoot", 0) : 0)
                    .put("observedProcesses", observedProcesses)
                    .put("observedProcessesThisBoot", observedProcessesThisBoot);
            output.put(item);
        }
        Bundle result = new Bundle();
        result.putString("processes", output.toString());
        result.putInt("bootCount", boot);
        return result;
    }

    private Bundle hookStatus() throws Exception {
        JSONArray output = new JSONArray();
        LogFileWriter active = getWriter();
        if (active != null) {
            for (String line : active.readRecentLines(4000)) {
                JSONObject row;
                try { row = new JSONObject(line); } catch (Throwable ignored) { continue; }
                String type = row.optString("eventType");
                if (type.startsWith("hook_") || "feature_state".equals(type)) output.put(row);
            }
        }
        Bundle result = new Bundle();
        result.putString("hooks", output.toString());
        return result;
    }

    private Bundle recentLogs(Bundle extras) throws Exception {
        int limit = extras == null ? 300 : Math.max(1, Math.min(1000,
                extras.getInt("limit", 300)));
        JSONArray output = new JSONArray();
        LogFileWriter active = getWriter();
        if (active != null) {
            for (String line : active.readRecentLines(limit)) {
                try { output.put(new JSONObject(line)); } catch (Throwable ignored) { }
            }
        }
        Bundle result = new Bundle();
        result.putString("logs", output.toString());
        result.putLong("dropped", dropped);
        result.putLong("writeFailures", writeFailures);
        result.putLong("overflowCount", overflowCount);
        result.putLong("providerAccepted", acceptedCount.get());
        result.putLong("persisted", persistedCount.get());
        return result;
    }

    private Bundle logFiles() throws Exception {
        JSONArray output = new JSONArray();
        LogFileWriter active = getWriter();
        if (active != null) for (File file : active.listFiles()) {
            output.put(new JSONObject().put("name", file.getName()).put("bytes", file.length()));
        }
        Bundle result = new Bundle();
        result.putString("files", output.toString());
        return result;
    }

    private Bundle readChunk(Bundle extras) throws Exception {
        if (extras == null) throw new IllegalArgumentException("Missing chunk request");
        byte[] bytes = getWriter().readChunk(extras.getString("name"),
                extras.getLong("offset", 0), extras.getInt("length", 64 * 1024));
        Bundle result = new Bundle();
        result.putByteArray("data", bytes);
        return result;
    }

    private Bundle queueStats() {
        Bundle result = new Bundle();
        synchronized (queueLock) {
            result.putInt("queued", queue.size());
            result.putLong("queuedBytes", queuedBytes);
        }
        result.putLong("dropped", dropped);
        result.putLong("writeFailures", writeFailures);
        result.putLong("overflowCount", overflowCount);
        result.putLong("providerAccepted", acceptedCount.get());
        result.putLong("persisted", persistedCount.get());
        android.content.SharedPreferences prefs = deviceContext.getSharedPreferences(
                "logger_process_status", 0);
        result.putLong("clientQueueDrops", prefs.getLong("clientQueueDrops", 0));
        synchronized (fallback) { result.putInt("fallbackLinesInMemory", fallback.size()); }
        try {
            JSONObject localStats = LogFallbackStore.stats(localFallbackDirectory());
            result.putLong("localFallbackPending", localStats.optLong("pendingCount", 0));
            result.putLong("localFallbackPendingBytes", localStats.optLong("pendingBytes", 0));
            result.putLong("localFallbackLossCount", localStats.optLong("lossCount", 0));
            result.putString("localFallbackLastLossReason",
                    localStats.optString("lastLossReason", ""));
        } catch (Throwable error) {
            result.putLong("localFallbackPending", -1);
            result.putLong("localFallbackPendingBytes", -1);
            result.putLong("localFallbackLossCount", -1);
            result.putString("localFallbackLastLossReason", "unavailable");
            fallback("local_fallback_stats_failed", error);
        }
        try {
            android.os.UserManager manager = (android.os.UserManager)
                    deviceContext.getSystemService(Context.USER_SERVICE);
            if (manager == null || !manager.isUserUnlocked()) throw new SecurityException(
                    "Credential-protected fallback is unavailable before unlock");
            Bundle stored = deviceContext.getContentResolver().call(Contract.PROVIDER_URI,
                    "get_logger_fallback_stats", null, null);
            if (stored != null) {
                JSONObject stats = new JSONObject(stored.getString("fallback", "{}"));
                result.putLong("fallbackPending", stats.optLong("pendingCount", 0)
                        + Math.max(0, result.getLong("localFallbackPending", 0)));
                result.putLong("fallbackPendingBytes", stats.optLong("pendingBytes", 0)
                        + Math.max(0, result.getLong("localFallbackPendingBytes", 0)));
                result.putLong("fallbackLossCount", stats.optLong("lossCount", 0)
                        + Math.max(0, result.getLong("localFallbackLossCount", 0)));
                String previousLoss = stats.optString("lastLossReason", "");
                if (previousLoss.isEmpty()) previousLoss = result.getString(
                        "localFallbackLastLossReason", "");
                result.putString("fallbackLastLossReason", previousLoss);
            }
        } catch (Throwable ignored) {
            result.putLong("fallbackPending", result.getLong("localFallbackPending", -1));
            result.putLong("fallbackPendingBytes",
                    result.getLong("localFallbackPendingBytes", -1));
            result.putLong("fallbackLossCount", result.getLong("localFallbackLossCount", -1));
            result.putString("fallbackLastLossReason",
                    result.getString("localFallbackLastLossReason", ""));
        }
        return result;
    }

    private String inflate(byte[] compressed) {
        if (compressed == null || compressed.length > MAX_COMPRESSED_BATCH_BYTES) {
            throw new IllegalArgumentException("Invalid compressed log batch");
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_UNCOMPRESSED_BATCH_BYTES) {
                    throw new IllegalArgumentException("Expanded log batch is too large");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalArgumentException("Cannot unpack log batch", error);
        }
    }

    private void enforceModuleCaller() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("Diagnostics log reads are module-app only");
        }
    }

    private Boolean knownEvent(String eventId) {
        if (eventId == null || eventId.isEmpty()) return null;
        synchronized (eventLock) { return eventAcks.get(eventId); }
    }

    private boolean allPersisted(List<String> eventIds) {
        synchronized (eventLock) {
            for (String eventId : eventIds) {
                if (!Boolean.TRUE.equals(eventAcks.get(eventId))) return false;
            }
            return true;
        }
    }

    private void rememberEvent(String eventId, boolean persisted) {
        if (eventId == null || eventId.isEmpty()) return;
        synchronized (eventLock) {
            eventAcks.put(eventId, persisted);
            while (eventAcks.size() > EVENT_ACK_CAPACITY) {
                Iterator<String> oldest = eventAcks.keySet().iterator();
                if (!oldest.hasNext()) break;
                oldest.next();
                oldest.remove();
            }
        }
    }

    private void markLinePersisted(String line) {
        try { rememberEvent(new JSONObject(line).optString("eventId", ""), true); }
        catch (Throwable ignored) { }
    }

    private Bundle result(String key, boolean value) {
        Bundle result = new Bundle(); result.putBoolean(key, value); return result;
    }

    private Bundle result(String key, String value) {
        Bundle result = new Bundle(); result.putString(key, value); return result;
    }

    private void fallback(String message, Throwable error) {
        long now = System.currentTimeMillis();
        String line;
        try {
            JSONObject row = new JSONObject().put("timestamp", now)
                    .put("time", java.time.Instant.ofEpochMilli(now).toString())
                    .put("level", "ERROR").put("eventType", "logger_internal")
                    .put("message", message).put("packageName", "com.lucky.mixflipouter")
                    .put("processName", "com.lucky.mixflipouter:logger")
                    .put("pid", Process.myPid()).put("threadId", Thread.currentThread().getId())
                    .put("threadName", Thread.currentThread().getName())
                    .put("moduleVersion", BuildConfig.VERSION_NAME + " ("
                            + BuildConfig.VERSION_CODE + ")").put("targetAppVersion", "unknown")
                    .put("sessionId", "logger-process");
            if (error != null) {
                StringWriter stack = new StringWriter();
                error.printStackTrace(new PrintWriter(stack));
                row.put("stackTrace", stack.toString());
            }
            line = row.toString();
        } catch (Throwable ignored) {
            line = "logger_internal: " + message + (error == null ? "" : ": " + error);
        }
        addFallback(line);
        if (now - lastFallbackAt >= 5000L) {
            lastFallbackAt = now;
            if (error == null) Log.e(TAG, line); else Log.e(TAG, line, error);
        }
    }

    private String asFallbackJson(String line) {
        try {
            JSONObject parsed = new JSONObject(line);
            parsed.put("fallbackCopy", true);
            return parsed.toString();
        } catch (Throwable ignored) {
            try {
                long now = System.currentTimeMillis();
                return new JSONObject().put("timestamp", now)
                        .put("time", java.time.Instant.ofEpochMilli(now).toString())
                        .put("level", "ERROR").put("eventType", "logger_internal")
                        .put("message", line).put("packageName", "com.lucky.mixflipouter")
                        .put("processName", "com.lucky.mixflipouter:logger")
                        .put("pid", Process.myPid()).put("threadId", Thread.currentThread().getId())
                        .put("moduleVersion", BuildConfig.VERSION_NAME + " ("
                                + BuildConfig.VERSION_CODE + ")").put("targetAppVersion", "unknown")
                        .put("sessionId", "logger-process").toString();
            } catch (Throwable impossible) { return "{}"; }
        }
    }

    private void addFallback(String line) {
        long lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
        if (lineBytes > FALLBACK_MAX_BYTES) {
            line = "{\"timestamp\":" + System.currentTimeMillis()
                    + ",\"level\":\"ERROR\",\"eventType\":\"logger_internal\","
                    + "\"message\":\"Fallback record omitted because it exceeded the byte limit\","
                    + "\"originalBytes\":" + lineBytes + "}";
            lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
        }
        long evicted = 0;
        synchronized (fallback) {
            while (!fallback.isEmpty() && (fallback.size() >= FALLBACK_CAPACITY
                    || fallbackBytes + lineBytes > FALLBACK_MAX_BYTES)) {
                fallbackBytes -= fallback.removeFirst().getBytes(StandardCharsets.UTF_8).length;
                evicted++;
            }
            fallback.addLast(line);
            fallbackBytes += lineBytes;
        }
        if (evicted > 0) {
            synchronized (queueLock) { dropped += evicted; }
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }

    private static final class LogRecord {
        final String line;
        final String level;
        final String eventId;
        LogRecord(String line, String level, String eventId) {
            this.line = line;
            this.level = level;
            this.eventId = eventId;
        }
        boolean priority() { return "WARN".equals(level) || "ERROR".equals(level); }
    }
}

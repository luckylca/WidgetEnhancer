package com.lucky.mixflipouter;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;


/** Bounded, asynchronous, process-local transport to the private log provider. */
final class LogClient {
    private static final String TAG = "WidgetEnhancerLogClient";
    private static final int CAPACITY = 512;
    private static final int MAX_QUEUE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_FAILURE_CYCLES = 3;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> QUEUE = new ArrayDeque<>();
    private static final AtomicBoolean WORKER_STARTED = new AtomicBoolean();
    private static volatile ContentResolver contentResolver;
    private static volatile UserManager userManager;
    private static volatile boolean userManagerInitialized;
    private static List<String> lockedRetryBatch;
    private static long dropped;
    private static long queuedBytes;
    private static JSONObject dropTemplate;
    private static long transportFailures;
    private static long lostSinceFlush;
    private static long totalLost;
    private static String lastFailureReason = "";
    private static boolean sending;

    interface ProviderCall {
        ProviderReply call(String method, byte[] payload, String reason) throws Exception;
    }

    enum Delivery { MAIN_PERSISTED, FALLBACK_PERSISTED, FAILED }

    static final class ProviderReply {
        final boolean accepted;
        final boolean persisted;
        final boolean durableFallback;
        final String failureReason;

        ProviderReply(boolean accepted, boolean persisted, boolean durableFallback,
                      String failureReason) {
            this.accepted = accepted;
            this.persisted = persisted;
            this.durableFallback = durableFallback;
            this.failureReason = failureReason == null ? "" : failureReason;
        }
    }

    static void initialize(Context value) {
        Context application = value.getApplicationContext() == null
                ? value : value.getApplicationContext();
        contentResolver = application.getContentResolver();
        try { userManager = (UserManager) value.getSystemService(Context.USER_SERVICE); }
        catch (Throwable ignored) { userManager = null; }
        userManagerInitialized = true;
        if (WORKER_STARTED.compareAndSet(false, true)) {
            Thread worker = new Thread(LogClient::runWorker, "widgetenhancer-log-client");
            worker.setDaemon(true);
            worker.start();
        }
    }

    static void enqueue(JSONObject event) {
        if (event == null) return;
        boundEvent(event);
        String line = event.toString();
        long lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
        synchronized (LOCK) {
            if (lineBytes > MAX_QUEUE_BYTES) {
                dropped++;
                lastFailureReason = "oversized_event";
                try {
                    dropTemplate = new JSONObject(event.toString());
                    dropTemplate.remove("stackTrace");
                    dropTemplate.put("message", "Oversized event dropped; bytes=" + lineBytes);
                } catch (Throwable ignored) { }
                LOCK.notifyAll();
                return;
            }
            if (QUEUE.size() >= CAPACITY || queuedBytes + lineBytes > MAX_QUEUE_BYTES) {
                if (isPriority(event)) {
                    String removable = findLowPriority();
                    if (removable != null) {
                        QUEUE.remove(removable);
                        queuedBytes -= removable.getBytes(StandardCharsets.UTF_8).length;
                    }
                    else {
                        dropped++;
                        lastFailureReason = "priority_queue_overflow";
                        dropTemplate = event;
                        LOCK.notifyAll();
                        return;
                    }
                } else {
                    dropped++;
                    lastFailureReason = "process_queue_overflow";
                    dropTemplate = event;
                    LOCK.notifyAll();
                    return;
                }
                dropped++;
                if (lastFailureReason.isEmpty()) lastFailureReason = "process_queue_overflow";
                dropTemplate = event;
            }
            QUEUE.addLast(line);
            queuedBytes += lineBytes;
            LOCK.notifyAll();
        }
    }

    static void boundEvent(JSONObject event) {
        try {
            String serialized = event.toString();
            int originalBytes = serialized.getBytes(StandardCharsets.UTF_8).length;
            if (originalBytes <= LogBatchCodec.MAX_EVENT_BYTES) return;
            String stack = event.optString("stackTrace", "");
            if (!stack.isEmpty()) {
                int half = 48 * 1024;
                String marker = "\n… stack trace truncated for bounded IPC transport …\n";
                String kept = stack.length() <= half * 2 ? stack
                        : stack.substring(0, half) + marker
                        + stack.substring(stack.length() - half);
                event.put("stackTrace", kept);
                event.put("stackTraceTruncated", !kept.equals(stack));
            }
            String message = event.optString("message", "");
            if (message.length() > 12 * 1024) {
                event.put("message", message.substring(0, 12 * 1024)
                        + "… message truncated for bounded IPC transport");
            }
            event.put("originalEventBytes", originalBytes);
            event.put("eventTruncated", true);
            serialized = event.toString();
            while (serialized.getBytes(StandardCharsets.UTF_8).length
                    > LogBatchCodec.MAX_EVENT_BYTES && event.has("stackTrace")) {
                String current = event.optString("stackTrace", "");
                if (current.length() < 1024) {
                    event.remove("stackTrace");
                    event.put("stackTraceTruncated", true);
                } else {
                    int nextLength = current.length() / 2;
                    event.put("stackTrace", current.substring(0, nextLength));
                    event.put("stackTraceTruncated", true);
                }
                serialized = event.toString();
            }
            if (serialized.getBytes(StandardCharsets.UTF_8).length
                    > LogBatchCodec.MAX_EVENT_BYTES) {
                event.put("message", event.optString("message", "")
                        .substring(0, Math.min(1024, event.optString("message", "").length())));
            }
        } catch (Throwable ignored) { }
    }

    static boolean flush(long timeoutMs) {
        if (!WORKER_STARTED.get() || contentResolver == null) return false;
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        synchronized (LOCK) {
            LOCK.notifyAll();
            while (!QUEUE.isEmpty() || lockedRetryBatch != null || sending || dropped > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return false;
                try { LOCK.wait(remaining); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (lostSinceFlush > 0) {
                lostSinceFlush = 0;
                return false;
            }
            return true;
        }
    }

    private static String findLowPriority() {
        for (String line : QUEUE) {
            try {
                String level = new JSONObject(line).optString("level");
                if ("DEBUG".equals(level) || "INFO".equals(level)) return line;
            } catch (Throwable ignored) {
                return line;
            }
        }
        return null;
    }

    private static void runWorker() {
        while (true) {
            List<String> batch = new ArrayList<>(64);
            synchronized (LOCK) {
                if (lockedRetryBatch != null) {
                    batch = lockedRetryBatch;
                    lockedRetryBatch = null;
                }
                if (dropped > 0 && dropTemplate != null && QUEUE.size() < CAPACITY) {
                    try {
                        JSONObject summary = new JSONObject(dropTemplate.toString());
                        summary.put("timestamp", System.currentTimeMillis());
                        summary.put("level", "WARN");
                        summary.put("eventType", "logger_queue_overflow");
                        summary.put("message", "Process-local log queue dropped records: "
                                + lastFailureReason);
                        summary.put("droppedCount", dropped);
                        summary.put("lossReason", lastFailureReason);
                        summary.remove("stackTrace");
                        String summaryLine = summary.toString();
                        long summaryBytes = summaryLine.getBytes(StandardCharsets.UTF_8).length;
                        if (queuedBytes + summaryBytes <= MAX_QUEUE_BYTES) {
                            QUEUE.addLast(summaryLine);
                            queuedBytes += summaryBytes;
                            dropped = 0;
                            dropTemplate = null;
                            lastFailureReason = "";
                        }
                    } catch (Throwable ignored) { }
                }
                while (batch.isEmpty() && QUEUE.isEmpty()) {
                    try { LOCK.wait(); } catch (InterruptedException ignored) { }
                }
                if (batch.isEmpty()) {
                    long jsonBytes = 2;
                    while (!QUEUE.isEmpty() && batch.size() < 64) {
                        String line = QUEUE.removeFirst();
                        long lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
                        long separatorBytes = batch.isEmpty() ? 0 : 1;
                        if (jsonBytes + separatorBytes + lineBytes
                                > LogBatchCodec.MAX_JSON_BYTES) {
                            QUEUE.addFirst(line);
                            queuedBytes += lineBytes;
                            break;
                        }
                        batch.add(line);
                        queuedBytes -= lineBytes;
                        jsonBytes += separatorBytes + lineBytes;
                    }
                }
                sending = true;
            }
            Delivery delivery = deliverBatch(batch, LogClient::callProvider);
            boolean sent = delivery != Delivery.FAILED;
            boolean terminalDrop = false;
            long lostNow = 0;
            synchronized (LOCK) {
                if (!sent) {
                    transportFailures++;
                    if (transportFailures >= MAX_FAILURE_CYCLES && isUserUnlocked()) {
                        lostNow = batch.size();
                        totalLost += lostNow;
                        lostSinceFlush += lostNow;
                        lastFailureReason = deliveryReason(batch);
                        terminalDrop = true;
                    } else if (!isUserUnlocked()) {
                        // The credential-protected fallback endpoint is unavailable during
                        // Direct Boot. Keep one bounded batch and retry after user unlock.
                        lockedRetryBatch = batch;
                    } else {
                        for (int i = batch.size() - 1; i >= 0; i--) {
                            String line = batch.get(i);
                            long bytes = line.getBytes(StandardCharsets.UTF_8).length;
                            if (QUEUE.size() < CAPACITY && queuedBytes + bytes <= MAX_QUEUE_BYTES) {
                                QUEUE.addFirst(line);
                                queuedBytes += bytes;
                            } else {
                                dropped++;
                                lastFailureReason = "retry_queue_overflow";
                                try { dropTemplate = new JSONObject(batch.get(i)); }
                                catch (Throwable ignored) { }
                            }
                        }
                    }
                } else transportFailures = 0;
                sending = false;
                LOCK.notifyAll();
            }
            if (!sent) {
                if (terminalDrop) recordFinalLoss(batch, lostNow, deliveryReason(batch));
                try { Thread.sleep(Math.min(4000L, 250L << Math.min(transportFailures, 4))); }
                catch (InterruptedException ignored) { }
            }
        }
    }

    static Delivery deliverBatch(List<String> lines, ProviderCall call) {
        byte[] compressed;
        try {
            compressed = LogBatchCodec.encode(lines);
        } catch (Throwable error) {
            fallback("unable to compress log batch: " + error.getClass().getSimpleName());
            return Delivery.FAILED;
        }
        String reason = "provider_unavailable";
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                ProviderReply result = call.call("ingest", compressed, "");
                if (result != null && result.accepted && result.persisted) {
                    return Delivery.MAIN_PERSISTED;
                }
                if (result != null && result.durableFallback) {
                    return Delivery.FALLBACK_PERSISTED;
                }
                if (result != null && !result.accepted) {
                    reason = result.failureReason.isEmpty()
                            ? "provider_queue_rejected" : result.failureReason;
                    break;
                }
                reason = "provider_not_persisted";
            } catch (SecurityException | IllegalArgumentException error) {
                reason = "permanent_provider_rejection_" + error.getClass().getSimpleName();
                break;
            } catch (Throwable error) {
                reason = "provider_ipc_" + error.getClass().getSimpleName();
            }
            if (attempt < 2) {
                try { Thread.sleep(100L << attempt); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return Delivery.FAILED;
                }
            }
        }
        if (userManagerInitialized && !isUserUnlocked()) {
            lastFailureReason = "direct_boot_config_provider_unavailable";
            return Delivery.FAILED;
        }
        try {
            JSONArray events = new JSONArray();
            for (String line : lines) events.put(new JSONObject(line));
            ProviderReply stored = call.call("append_fallback",
                    events.toString().getBytes(StandardCharsets.UTF_8), reason);
            if (stored != null && stored.accepted) {
                return Delivery.FALLBACK_PERSISTED;
            }
            if (stored != null) reason = stored.failureReason.isEmpty()
                    ? "fallback_capacity_or_write_failure" : stored.failureReason;
        } catch (SecurityException | IllegalArgumentException error) {
            reason = "permanent_fallback_rejection_" + error.getClass().getSimpleName();
        } catch (Throwable error) {
            reason = "fallback_unavailable_" + error.getClass().getSimpleName();
        }
        lastFailureReason = reason;
        return Delivery.FAILED;
    }

    private static Bundle callProvider(String method, Bundle extras) throws Exception {
        ContentResolver active = contentResolver;
        if (active == null) throw new IllegalStateException("No logger ContentResolver");
        if ("ingest".equals(method)) return active.call(LogProvider.URI, method, null, extras);
        if ("append_fallback".equals(method)) {
            return active.call(Contract.PROVIDER_URI, "append_logger_fallback", null, extras);
        }
        if ("record_loss".equals(method)) {
            return active.call(Contract.PROVIDER_URI, "record_logger_loss", null, extras);
        }
        throw new IllegalArgumentException("Unknown logger transport method");
    }

    private static ProviderReply callProvider(String method, byte[] payload, String reason)
            throws Exception {
        Bundle extras = new Bundle();
        String actualMethod = method;
        if ("ingest".equals(method)) {
            extras.putByteArray("eventsGzip", payload);
        } else if ("append_fallback".equals(method)) {
            actualMethod = "append_logger_fallback";
            extras.putString("events", new String(payload, StandardCharsets.UTF_8));
            extras.putString("reason", reason);
        } else {
            throw new IllegalArgumentException("Unknown logger transport method");
        }
        if (!isUserUnlocked()) {
            throw new IllegalStateException(
                    "Credential-protected ConfigProvider is unavailable before user unlock");
        }
        ContentResolver active = contentResolver;
        if (active == null) throw new IllegalStateException("No logger ContentResolver");
        Bundle result = active.call("ingest".equals(method) ? LogProvider.URI : Contract.PROVIDER_URI,
                actualMethod, null, extras);
        if (result == null) return null;
        return new ProviderReply(result.getBoolean("accepted", false),
                result.getBoolean("persisted", false), result.getBoolean("durableFallback", false),
                result.getString("failureReason", ""));
    }

    private static String deliveryReason(List<String> lines) {
        String reason = lastFailureReason;
        if (reason != null && !reason.isEmpty()) return reason;
        return "transport_exhausted_after_" + MAX_FAILURE_CYCLES + "_cycles";
    }

    private static boolean isUserUnlocked() {
        if (!userManagerInitialized) return true;
        UserManager manager = userManager;
        if (manager == null) return false;
        try {
            return manager.isUserUnlocked();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void recordFinalLoss(List<String> batch, long count, String reason) {
        try {
            JSONObject first = new JSONObject(batch.get(0));
            Bundle extras = new Bundle();
            extras.putString("packageName", first.optString("packageName", ""));
            extras.putString("processName", first.optString("processName", ""));
            extras.putString("reason", reason);
            extras.putLong("count", count);
            Bundle result = callProvider("record_loss", extras);
            if (result != null) return;
        } catch (Throwable ignored) { }
        fallback("permanently lost log batch: count=" + count + ", reason=" + reason);
    }

    private static boolean isPriority(JSONObject event) {
        String level = event.optString("level");
        String eventType = event.optString("eventType");
        return "WARN".equals(level) || "ERROR".equals(level)
                || "hook_failure".equals(eventType)
                || ("feature_state".equals(eventType)
                && event.optString("message").contains("FAILED"));
    }

    static long totalLostForTest() { synchronized (LOCK) { return totalLost; } }

    private static void fallback(String message) {
        try {
            Log.e(TAG, message);
            ModuleLogger.xposedFallback(TAG + ": " + message);
        } catch (Throwable ignored) { }
    }

    private LogClient() { }
}

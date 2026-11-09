package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;

public final class LogClientQueueTest {
    private Object lock;
    private ArrayDeque<String> queue;

    @Before
    public void resetQueue() throws Exception {
        lock = field("LOCK").get(null);
        queue = (ArrayDeque<String>) field("QUEUE").get(null);
        synchronized (lock) {
            queue.clear();
            field("queuedBytes").setLong(null, 0L);
            field("dropped").setLong(null, 0L);
            field("dropTemplate").set(null, null);
            field("transportFailures").setLong(null, 0L);
            field("lostSinceFlush").setLong(null, 0L);
            field("totalLost").setLong(null, 0L);
            field("lastFailureReason").set(null, "");
            field("sending").setBoolean(null, false);
        }
    }

    @After
    public void clearQueue() throws Exception {
        resetQueue();
    }

    @Test
    public void queueCapacityIsBoundedAndWarnEvictsLowPriority() throws Exception {
        for (int i = 0; i < 512; i++) LogClient.enqueue(event("INFO", "info-" + i, ""));
        LogClient.enqueue(event("WARN", "priority-warning", ""));

        assertEquals(512, queue.size());
        assertTrue(queue.peekFirst().contains("info-1"));
        assertTrue(queue.peekLast().contains("priority-warning"));
        assertEquals(1L, field("dropped").getLong(null));
    }

    @Test
    public void queueByteBudgetDropsRecordsWithoutExceedingLimit() throws Exception {
        char[] chars = new char[40_000];
        java.util.Arrays.fill(chars, 'x');
        String largeMessage = new String(chars);
        for (int i = 0; i < 120; i++) {
            LogClient.enqueue(event("INFO", "payload-" + i, largeMessage));
        }

        assertTrue(queue.size() < 512);
        assertTrue(field("queuedBytes").getLong(null) <= 4L * 1024L * 1024L);
        assertTrue(field("dropped").getLong(null) > 0);
    }

    @Test
    public void transientIpcFailureRetriesAndEventuallyPersistsToMainLog() throws Exception {
        int[] calls = {0};
        LogClient.Delivery delivery = LogClient.deliverBatch(
                java.util.Collections.singletonList(event("ERROR", "retry", "").toString()),
                (method, payload, reason) -> {
                    assertEquals("ingest", method);
                    calls[0]++;
                    if (calls[0] < 3) throw new java.io.IOException("temporary provider outage");
                    return new LogClient.ProviderReply(true, true, false, "");
                });

        assertEquals(LogClient.Delivery.MAIN_PERSISTED, delivery);
        assertEquals(3, calls[0]);
    }

    @Test
    public void exhaustedIpcRetriesPersistBatchToBoundedFallback() throws Exception {
        int[] providerCalls = {0};
        int[] fallbackCalls = {0};
        LogClient.Delivery delivery = LogClient.deliverBatch(
                java.util.Collections.singletonList(event("ERROR", "fallback", "").toString()),
                (method, payload, reason) -> {
                    if ("ingest".equals(method)) {
                        providerCalls[0]++;
                        throw new java.io.IOException("logger process unavailable");
                    }
                    assertEquals("append_fallback", method);
                    fallbackCalls[0]++;
                    return new LogClient.ProviderReply(true, false, true, "");
                });

        assertEquals(LogClient.Delivery.FALLBACK_PERSISTED, delivery);
        assertEquals(3, providerCalls[0]);
        assertEquals(1, fallbackCalls[0]);
    }

    @Test
    public void exhaustedLoggerAndFallbackCallsReturnFinalFailure() throws Exception {
        int[] calls = {0};
        LogClient.Delivery delivery = LogClient.deliverBatch(
                java.util.Collections.singletonList(event("WARN", "loss", "").toString()),
                (method, payload, reason) -> {
                    calls[0]++;
                    throw new java.io.IOException("storage unavailable");
                });

        assertEquals(LogClient.Delivery.FAILED, delivery);
        assertEquals(4, calls[0]);
    }

    private JSONObject event(String level, String title, String message) throws Exception {
        return new JSONObject().put("level", level).put("eventType", "test")
                .put("message", title + message).put("packageName", "com.lucky.mixflipouter")
                .put("processName", "com.lucky.mixflipouter");
    }

    private Field field(String name) throws Exception {
        Field field = LogClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}

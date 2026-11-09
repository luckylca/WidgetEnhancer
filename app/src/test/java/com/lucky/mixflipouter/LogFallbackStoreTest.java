package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public final class LogFallbackStoreTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void fallbackSurvivesReadsAndAcknowledgesWithoutDuplicateReplay() throws Exception {
        File directory = temporaryFolder.newFolder("fallback");
        List<String> events = Arrays.asList(
                event("event-1", "ERROR", "important"),
                event("event-2", "INFO", "ordinary"));

        JSONObject first = LogFallbackStore.append(directory, events, "provider_unavailable");
        assertTrue(first.getBoolean("accepted"));
        JSONObject duplicate = LogFallbackStore.append(directory, events, "retry");
        assertTrue(duplicate.getBoolean("accepted"));
        assertEquals(2, duplicate.getInt("pendingCount"));

        JSONObject pending = LogFallbackStore.peek(directory, (int) LogFallbackStore.MAX_BYTES);
        JSONArray recovered = pending.getJSONArray("events");
        assertEquals(2, recovered.length());
        assertEquals("event-1", new JSONObject(recovered.getString(0)).getString("eventId"));
        assertEquals("event-2", new JSONObject(recovered.getString(1)).getString("eventId"));

        LogFallbackStore.acknowledge(directory, new HashSet<>(Collections.singletonList("event-1")));
        JSONObject afterAck = LogFallbackStore.peek(directory, 512 * 1024);
        assertEquals(1, afterAck.getJSONArray("events").length());
        assertEquals("event-2", new JSONObject(afterAck.getJSONArray("events").getString(0))
                .getString("eventId"));
        assertTrue(new String(Files.readAllBytes(new File(directory, "pending.jsonl").toPath()),
                StandardCharsets.UTF_8).contains("event-2"));
    }

    @Test
    public void fallbackIsBoundedAndEvictsLowPriorityBeforeRejectingImportantEvents()
            throws Exception {
        File directory = temporaryFolder.newFolder("bounded");
        String payload = repeat('x', 100_000);
        java.util.ArrayList<String> ordinary = new java.util.ArrayList<>();
        for (int i = 0; i < 44; i++) ordinary.add(new JSONObject()
                .put("eventId", "ordinary-" + i).put("level", "INFO")
                .put("eventType", "message").put("message", payload).toString());
        LogFallbackStore.append(directory, ordinary, "test_capacity");
        JSONObject urgent = LogFallbackStore.append(directory,
                Collections.singletonList(new JSONObject().put("eventId", "critical-hook")
                        .put("level", "ERROR").put("eventType", "hook_failure")
                        .put("message", repeat('e', 100_000)).toString()), "test_priority");

        JSONObject stats = LogFallbackStore.stats(directory);
        assertTrue(stats.getLong("pendingBytes") <= LogFallbackStore.MAX_BYTES);
        assertTrue(stats.getInt("pendingCount") <= LogFallbackStore.MAX_EVENTS);
        assertTrue(stats.getLong("lossCount") > 0);
        assertEquals("fallback_capacity_eviction", stats.getString("lastLossReason"));
        assertTrue(urgent.getBoolean("accepted"));
        JSONObject pending = LogFallbackStore.peek(directory, (int) LogFallbackStore.MAX_BYTES);
        boolean foundCritical = false;
        for (int i = 0; i < pending.getJSONArray("events").length(); i++) {
            if ("critical-hook".equals(new JSONObject(pending.getJSONArray("events")
                    .getString(i)).optString("eventId"))) foundCritical = true;
        }
        assertTrue(foundCritical);
    }

    @Test
    public void unavailableRecordsAndExplicitLossReasonsAreCounted() throws Exception {
        File directory = temporaryFolder.newFolder("losses");
        JSONObject result = LogFallbackStore.append(directory,
                Collections.singletonList("{}"), "ipc_unavailable");
        assertTrue(result.getBoolean("accepted"));
        LogFallbackStore.recordLoss(directory, "permanent_disk_failure", 3);
        JSONObject stats = LogFallbackStore.stats(directory);
        assertEquals(3, stats.getLong("lossCount"));
        assertEquals(3, stats.getJSONObject("lossesByReason")
                .getLong("permanent_disk_failure"));
    }

    private static String event(String id, String level, String message) throws Exception {
        return new JSONObject().put("eventId", id).put("level", level)
                .put("eventType", "message").put("message", message).toString();
    }

    private static String repeat(char value, int length) {
        char[] chars = new char[length];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}

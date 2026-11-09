package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public final class LogBatchCodecTest {
    @Test
    public void compressedBatchPreservesLargeExceptionRecordsWithinBothByteLimits() throws Exception {
        List<String> rows = new ArrayList<>();
        String stack = repeat("at com.example.Widget.method(Widget.java:123)\n", 220);
        for (int i = 0; i < 24; i++) {
            rows.add(new JSONObject().put("eventId", "event-" + i)
                    .put("level", i == 23 ? "ERROR" : "INFO")
                    .put("stackTrace", stack).put("message", "diagnostic row " + i).toString());
        }

        byte[] encoded = LogBatchCodec.encode(rows);
        String json = new String(gunzip(encoded), StandardCharsets.UTF_8);
        JSONArray decoded = new JSONArray(json);

        assertTrue(encoded.length <= LogBatchCodec.MAX_COMPRESSED_BYTES);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= LogBatchCodec.MAX_JSON_BYTES);
        assertEquals(rows.size(), decoded.length());
        assertEquals(stack, decoded.getJSONObject(23).getString("stackTrace"));
    }

    @Test
    public void oversizedStackTraceIsBoundedBeforeItCanPoisonTheWholeQueue() throws Exception {
        JSONObject event = new JSONObject().put("level", "ERROR")
                .put("message", "large exception")
                .put("stackTrace", repeat("caused by frame with details\n", 150_000));
        LogClient.boundEvent(event);

        assertTrue(event.getBoolean("eventTruncated"));
        assertTrue(event.getBoolean("stackTraceTruncated"));
        assertTrue(event.toString().getBytes(StandardCharsets.UTF_8).length
                <= LogBatchCodec.MAX_EVENT_BYTES);
        assertTrue(LogBatchCodec.encode(java.util.Collections.singletonList(event.toString())).length
                <= LogBatchCodec.MAX_COMPRESSED_BYTES);
    }

    @Test
    public void rejectsUncompressedBatchAboveByteLimit() throws Exception {
        String value = repeat("x", LogBatchCodec.MAX_JSON_BYTES);
        String row = new JSONObject().put("message", value).toString();
        try {
            LogBatchCodec.encode(java.util.Collections.singletonList(row));
            fail("expected uncompressed byte limit to reject the batch");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("Uncompressed"));
        }
    }

    private static byte[] gunzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String repeat(String value, int count) {
        StringBuilder output = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) output.append(value);
        return output.toString();
    }
}

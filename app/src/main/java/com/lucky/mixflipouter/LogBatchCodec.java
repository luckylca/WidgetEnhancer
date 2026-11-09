package com.lucky.mixflipouter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Encodes size-bounded JSONL records for one compressed provider transaction. */
final class LogBatchCodec {
    static final int MAX_EVENT_BYTES = 512 * 1024;
    static final int MAX_JSON_BYTES = 640 * 1024;
    static final int MAX_COMPRESSED_BYTES = 900 * 1024;

    static byte[] encode(List<String> lines) throws IOException {
        JSONArray array = new JSONArray();
        try {
            for (String line : lines) array.put(new JSONObject(line));
        } catch (JSONException error) {
            throw new IOException("Invalid JSON log row", error);
        }
        byte[] json = array.toString().getBytes(StandardCharsets.UTF_8);
        if (json.length > MAX_JSON_BYTES) {
            throw new IOException("Uncompressed log batch exceeds the IPC size limit");
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(json);
        }
        byte[] payload = compressed.toByteArray();
        if (payload.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Compressed log batch exceeds the IPC size limit");
        }
        return payload;
    }

    private LogBatchCodec() { }
}

package com.lucky.mixflipouter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Bounds and canonicalizes the data crossing the SystemUI/provider process boundary. */
final class QSTilePayload {
    static final int MAX_SNAPSHOT_BYTES = 256 * 1024;
    static final int MAX_TILE_COUNT = 256;
    static final int MAX_SPEC_LENGTH = 1024;
    static final int MAX_LABEL_LENGTH = 256;
    static final int MAX_IMPLEMENTATION_LENGTH = 512;

    static String sanitizeSnapshot(String source) {
        String value = source == null ? "[]" : source;
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException("QS tile snapshot is too large");
        }
        try {
            JSONArray input = new JSONArray(value);
            if (input.length() > MAX_TILE_COUNT) {
                throw new IllegalArgumentException("Too many QS tiles");
            }
            JSONArray output = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < input.length(); i++) {
                JSONObject item = input.optJSONObject(i);
                if (item == null) throw new IllegalArgumentException("Invalid QS tile entry");
                String spec = normalizeSpec(item.optString("spec", ""));
                if (!seen.add(spec)) throw new IllegalArgumentException("Duplicate QS tile spec");
                int state = item.optInt("state", 0);
                if (state < 0 || state > 2) state = 0;
                String label = bounded(item.optString("label", spec), MAX_LABEL_LENGTH);
                String implementation = bounded(
                        item.optString("implementation", ""), MAX_IMPLEMENTATION_LENGTH);
                output.put(new JSONObject()
                        .put("spec", spec)
                        .put("label", label.isEmpty() ? spec : label)
                        .put("state", state)
                        .put("available", state != 0 && item.optBoolean("available", false))
                        .put("custom", spec.startsWith("custom("))
                        .put("implementation", implementation));
            }
            return output.toString();
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid QS tile snapshot", error);
        }
    }

    static String normalizeSpec(String source) {
        String spec = source == null ? "" : source.trim();
        if (spec.isEmpty() || spec.length() > MAX_SPEC_LENGTH
                || spec.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid QS tile spec");
        }
        return spec;
    }

    private static String bounded(String source, int limit) {
        if (source == null) return "";
        String value = source.trim();
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private QSTilePayload() {}
}

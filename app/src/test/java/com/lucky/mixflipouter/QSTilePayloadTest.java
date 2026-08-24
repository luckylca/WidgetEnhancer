package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class QSTilePayloadTest {
    @Test
    public void snapshotIsCanonicalAndUnavailableStateCannotClaimAvailability() throws Exception {
        JSONArray source = new JSONArray()
                .put(new JSONObject()
                        .put("spec", "  flashlight  ")
                        .put("label", " 手电筒 ")
                        .put("state", 0)
                        .put("available", true)
                        .put("custom", true)
                        .put("unknown", "discard me"));

        JSONObject tile = new JSONArray(
                QSTilePayload.sanitizeSnapshot(source.toString())).getJSONObject(0);

        assertEquals("flashlight", tile.getString("spec"));
        assertEquals("手电筒", tile.getString("label"));
        assertFalse(tile.getBoolean("available"));
        assertFalse(tile.getBoolean("custom"));
        assertFalse(tile.has("unknown"));
    }

    @Test
    public void activeCustomTileKeepsSupportedFields() throws Exception {
        String spec = "custom(com.example/.ExampleTileService)";
        JSONArray source = new JSONArray().put(new JSONObject()
                .put("spec", spec)
                .put("label", "Example")
                .put("state", 2)
                .put("available", true)
                .put("implementation", "com.android.systemui.qs.external.CustomTile"));

        JSONObject tile = new JSONArray(
                QSTilePayload.sanitizeSnapshot(source.toString())).getJSONObject(0);

        assertTrue(tile.getBoolean("available"));
        assertTrue(tile.getBoolean("custom"));
        assertEquals(2, tile.getInt("state"));
        assertEquals(spec, tile.getString("spec"));
    }

    @Test
    public void duplicateAndOversizedPayloadsAreRejected() throws Exception {
        JSONArray duplicates = new JSONArray()
                .put(new JSONObject().put("spec", "wifi"))
                .put(new JSONObject().put("spec", "wifi"));
        assertThrows(IllegalArgumentException.class,
                () -> QSTilePayload.sanitizeSnapshot(duplicates.toString()));

        StringBuilder oversized = new StringBuilder(QSTilePayload.MAX_SNAPSHOT_BYTES + 1);
        for (int i = 0; i <= QSTilePayload.MAX_SNAPSHOT_BYTES; i++) oversized.append('x');
        assertThrows(IllegalArgumentException.class,
                () -> QSTilePayload.sanitizeSnapshot(oversized.toString()));
    }

    @Test
    public void invalidSpecsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> QSTilePayload.normalizeSpec("   "));
        assertThrows(IllegalArgumentException.class,
                () -> QSTilePayload.normalizeSpec("wifi\u0000evil"));
        assertEquals("wifi", QSTilePayload.normalizeSpec(" wifi "));
    }
}

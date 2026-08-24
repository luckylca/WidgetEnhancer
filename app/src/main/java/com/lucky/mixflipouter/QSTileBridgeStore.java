package com.lucky.mixflipouter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/** Small provider-side mailbox between FlipHome and the optional SystemUI hook. */
final class QSTileBridgeStore {
    private static final String PREFS = "qs_tile_bridge";
    private static final long BRIDGE_STALE_MS = 90_000;
    private static final long REQUEST_EXPIRES_MS = 15_000;
    private final Context context;
    private final SharedPreferences prefs;

    QSTileBridgeStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, 0);
    }

    synchronized Bundle publish(Bundle source) {
        String json = QSTilePayload.sanitizeSnapshot(
                source == null ? "[]" : source.getString("tiles_json", "[]"));
        long now = System.currentTimeMillis();
        prefs.edit()
                .putString("tiles_json", json)
                .putLong("published_at", now)
                .putString("systemui_version", source == null
                        ? "" : source.getString("systemui_version", ""))
                .apply();
        return snapshot();
    }

    synchronized Bundle snapshot() {
        long publishedAt = prefs.getLong("published_at", 0);
        Bundle out = new Bundle();
        out.putString("tiles_json", prefs.getString("tiles_json", "[]"));
        out.putLong("published_at", publishedAt);
        out.putBoolean("bridge_ready",
                publishedAt > 0 && System.currentTimeMillis() - publishedAt <= BRIDGE_STALE_MS);
        out.putString("systemui_version", prefs.getString("systemui_version", ""));
        out.putString("last_request_id", prefs.getString("last_request_id", ""));
        out.putBoolean("last_result_ok", prefs.getBoolean("last_result_ok", false));
        out.putString("last_result_message", prefs.getString("last_result_message", ""));
        out.putLong("last_result_at", prefs.getLong("last_result_at", 0));
        return out;
    }

    synchronized Bundle request(String spec) {
        Bundle out = new Bundle();
        String normalized;
        try {
            normalized = QSTilePayload.normalizeSpec(spec);
        } catch (IllegalArgumentException error) {
            out.putBoolean("ok", false);
            out.putString("message", "尚未选择快捷设置磁贴");
            return out;
        }
        long publishedAt = prefs.getLong("published_at", 0);
        if (publishedAt == 0 || System.currentTimeMillis() - publishedAt > BRIDGE_STALE_MS) {
            out.putBoolean("ok", false);
            out.putString("message", "QS 桥接未连接，请在 LSPosed 勾选系统界面并重启");
            return out;
        }
        JSONObject selected = find(normalized);
        if (selected == null) {
            out.putBoolean("ok", false);
            out.putString("message", "该磁贴尚未加入系统控制中心");
            return out;
        }
        if (!selected.optBoolean("available", false)) {
            out.putBoolean("ok", false);
            out.putString("message", "该磁贴当前不可用");
            return out;
        }
        String id = UUID.randomUUID().toString();
        prefs.edit()
                .putString("request_id", id)
                .putString("request_spec", normalized)
                .putLong("request_at", System.currentTimeMillis())
                .remove("request_taken_id")
                .apply();
        context.getContentResolver().notifyChange(Contract.QS_URI, null);
        out.putBoolean("ok", true);
        out.putString("request_id", id);
        out.putString("message", "已提交给 SystemUI");
        return out;
    }

    synchronized Bundle take() {
        String id = prefs.getString("request_id", "");
        long at = prefs.getLong("request_at", 0);
        Bundle out = new Bundle();
        if (id.isEmpty() || id.equals(prefs.getString("request_taken_id", ""))) {
            out.putBoolean("has_request", false);
            return out;
        }
        if (System.currentTimeMillis() - at > REQUEST_EXPIRES_MS) {
            clearPending();
            out.putBoolean("has_request", false);
            return out;
        }
        prefs.edit().putString("request_taken_id", id).apply();
        out.putBoolean("has_request", true);
        out.putString("request_id", id);
        out.putString("spec", prefs.getString("request_spec", ""));
        return out;
    }

    synchronized Bundle complete(Bundle result) {
        String id = result == null ? "" : result.getString("request_id", "");
        long requestedAt = prefs.getLong("request_at", 0);
        if (id.isEmpty() || !id.equals(prefs.getString("request_id", ""))
                || !id.equals(prefs.getString("request_taken_id", ""))
                || System.currentTimeMillis() - requestedAt > REQUEST_EXPIRES_MS) {
            throw new IllegalArgumentException("Unknown QS request");
        }
        boolean ok = result.getBoolean("ok", false);
        String message = result.getString("message", ok ? "已执行" : "执行失败");
        prefs.edit()
                .putString("last_request_id", id)
                .putBoolean("last_result_ok", ok)
                .putString("last_result_message", message)
                .putLong("last_result_at", System.currentTimeMillis())
                .remove("request_id")
                .remove("request_spec")
                .remove("request_at")
                .remove("request_taken_id")
                .apply();
        Bundle out = new Bundle();
        out.putBoolean("ok", true);
        return out;
    }

    private JSONObject find(String spec) {
        try {
            JSONArray tiles = new JSONArray(prefs.getString("tiles_json", "[]"));
            for (int i = 0; i < tiles.length(); i++) {
                JSONObject tile = tiles.optJSONObject(i);
                if (tile != null && spec.equals(tile.optString("spec"))) return tile;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void clearPending() {
        prefs.edit()
                .remove("request_id")
                .remove("request_spec")
                .remove("request_at")
                .remove("request_taken_id")
                .apply();
    }
}

package com.lucky.mixflipouter;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class WidgetConfig {
    long repositoryRevision;
    String id = Contract.DEFAULT_WIDGET_ID;
    String name = "我的外屏";
    boolean enabled = true;
    String mediaType = "none";
    String mimeType = "application/octet-stream";
    boolean loop = true;
    boolean mute = true;
    final String[] labels = new String[Contract.BUTTON_COUNT];
    final String[] actionTypes = new String[Contract.BUTTON_COUNT];
    final String[] actionValues = new String[Contract.BUTTON_COUNT];

    WidgetConfig() {
        for (int i = 0; i < Contract.BUTTON_COUNT; i++) {
            labels[i] = "";
            actionTypes[i] = "package";
            actionValues[i] = "";
        }
    }

    static WidgetConfig load(Context context) {
        return load(context, Contract.DEFAULT_WIDGET_ID);
    }

    static WidgetConfig load(Context context, String widgetId) {
        try {
            Bundle b = context.getContentResolver().call(
                    Contract.PROVIDER_URI, "get_widget", widgetId, null);
            return b == null ? null : fromBundle(b);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static List<WidgetConfig> list(Context context) {
        try {
            ContentResolver resolver = context.getContentResolver();
            Bundle result = resolver.call(Contract.PROVIDER_URI, "list_widgets", null, null);
            if (result == null) return Collections.emptyList();
            @SuppressWarnings("deprecation")
            ArrayList<Bundle> bundles = result.getParcelableArrayList("widgets");
            if (bundles == null) return Collections.emptyList();
            ArrayList<WidgetConfig> out = new ArrayList<>(bundles.size());
            for (Bundle bundle : bundles) out.add(fromBundle(bundle));
            return out;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    Bundle toBundle(long revision) {
        Bundle out = new Bundle();
        out.putInt("schema_version", WidgetRepository.SCHEMA_VERSION);
        out.putLong("revision", revision);
        out.putString("id", id);
        out.putString("name", name);
        out.putBoolean("enabled", enabled);
        out.putString("media_type", mediaType);
        out.putString("mime_type", mimeType);
        out.putBoolean("loop", loop);
        out.putBoolean("mute", mute);
        for (int i = 0; i < Contract.BUTTON_COUNT; i++) {
            out.putString("button_" + i + "_label", labels[i]);
            out.putString("button_" + i + "_type", actionTypes[i]);
            out.putString("button_" + i + "_value", actionValues[i]);
        }
        return out;
    }

    static WidgetConfig fromBundle(Bundle b) {
        WidgetConfig c = new WidgetConfig();
        c.repositoryRevision = b.getLong("revision", 0);
        c.id = safe(b.getString("id"), Contract.DEFAULT_WIDGET_ID);
        c.name = safe(b.getString("name"), "我的外屏");
        c.enabled = b.getBoolean("enabled", true);
        c.mediaType = safe(b.getString("media_type"), "none");
        c.mimeType = safe(b.getString("mime_type"), "application/octet-stream");
        c.loop = b.getBoolean("loop", true);
        c.mute = b.getBoolean("mute", true);
        for (int i = 0; i < Contract.BUTTON_COUNT; i++) {
            c.labels[i] = safe(b.getString("button_" + i + "_label"), "");
            c.actionTypes[i] = safe(b.getString("button_" + i + "_type"), "package");
            c.actionValues[i] = safe(b.getString("button_" + i + "_value"), "");
        }
        return c;
    }

    JSONObject toJson() throws Exception {
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("name", name);
        out.put("enabled", enabled);
        out.put("mediaType", mediaType);
        out.put("mimeType", mimeType);
        JSONObject runtime = new JSONObject();
        runtime.put("loop", loop);
        runtime.put("mute", mute);
        out.put("runtime", runtime);
        JSONArray actions = new JSONArray();
        for (int i = 0; i < Contract.BUTTON_COUNT; i++) {
            JSONObject action = new JSONObject();
            action.put("label", labels[i]);
            action.put("type", actionTypes[i]);
            action.put("value", actionValues[i]);
            actions.put(action);
        }
        out.put("actions", actions);
        return out;
    }

    static WidgetConfig fromJson(JSONObject in) {
        WidgetConfig c = new WidgetConfig();
        c.id = safe(in.optString("id", null), Contract.DEFAULT_WIDGET_ID);
        c.name = safe(in.optString("name", null), "我的外屏");
        c.enabled = in.optBoolean("enabled", true);
        c.mediaType = safe(in.optString("mediaType", null), "none");
        c.mimeType = safe(in.optString("mimeType", null), "application/octet-stream");
        JSONObject runtime = in.optJSONObject("runtime");
        if (runtime != null) {
            c.loop = runtime.optBoolean("loop", true);
            c.mute = runtime.optBoolean("mute", true);
        }
        JSONArray actions = in.optJSONArray("actions");
        if (actions != null) {
            for (int i = 0; i < Math.min(actions.length(), Contract.BUTTON_COUNT); i++) {
                JSONObject action = actions.optJSONObject(i);
                if (action == null) continue;
                c.labels[i] = action.optString("label", "");
                c.actionTypes[i] = action.optString("type", "package");
                c.actionValues[i] = action.optString("value", "");
            }
        }
        return c;
    }

    WidgetConfig duplicate(String newId, String newName) {
        WidgetConfig copy = fromJsonQuietly(toJsonQuietly());
        copy.id = newId;
        copy.name = newName;
        return copy;
    }

    private JSONObject toJsonQuietly() {
        try {
            return toJson();
        } catch (Exception impossible) {
            return new JSONObject();
        }
    }

    private static WidgetConfig fromJsonQuietly(JSONObject value) {
        return fromJson(value);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}

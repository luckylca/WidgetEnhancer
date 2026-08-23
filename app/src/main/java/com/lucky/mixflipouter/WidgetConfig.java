package com.lucky.mixflipouter;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;

final class WidgetConfig {
    String id = Contract.DEFAULT_WIDGET_ID;
    String name = "我的外屏";
    boolean enabled;
    String mediaType = "none";
    String mimeType = "application/octet-stream";
    boolean loop = true;
    boolean mute = true;
    final String[] labels = new String[Contract.BUTTON_COUNT];
    final String[] actionTypes = new String[Contract.BUTTON_COUNT];
    final String[] actionValues = new String[Contract.BUTTON_COUNT];

    static WidgetConfig load(Context context) {
        return load(context, Contract.DEFAULT_WIDGET_ID);
    }

    static WidgetConfig load(Context context, String widgetId) {
        try {
            ContentResolver resolver = context.getContentResolver();
            Bundle b = resolver.call(Contract.PROVIDER_URI, "get_widget", widgetId, null);
            if (b == null) return null;
            WidgetConfig c = new WidgetConfig();
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
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safe(String value, String fallback) {
        return value == null ? fallback : value;
    }
}

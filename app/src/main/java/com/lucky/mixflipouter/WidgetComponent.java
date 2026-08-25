package com.lucky.mixflipouter;

import org.json.JSONObject;

import java.util.UUID;

/** Platform-neutral component record persisted inside WidgetConfig. */
final class WidgetComponent {
    static final String TYPE_IMAGE = "image";
    static final String TYPE_VIDEO = "video";
    static final String TYPE_TEXT = "text";
    static final String TYPE_TIME = "time";
    static final String TYPE_BUTTON = "button";
    static final String TYPE_SONG_TITLE = "song_title";
    static final String TYPE_ARTIST = "artist";
    static final String TYPE_LYRIC_PREVIOUS = "lyric_previous";
    static final String TYPE_LYRIC_CURRENT = "lyric_current";
    static final String TYPE_LYRIC_NEXT = "lyric_next";
    static final String TYPE_PLAYBACK_PROGRESS = "playback_progress";
    static final String TYPE_ALBUM_ART = "album_art";

    String id = UUID.randomUUID().toString();
    String type = TYPE_TEXT;
    float x;
    float y;
    float width = 200;
    float height = 80;
    int zIndex;
    boolean visible = true;
    boolean locked;
    float opacity = 1f;
    float cornerRadius;
    String fillMode = "cover";
    String content = "";
    String color = "#FFFFFFFF";
    float textSize = 28f;
    String textAlign = "center";
    String actionType = "";
    String actionValue = "";

    static WidgetComponent media(String type) {
        WidgetComponent component = new WidgetComponent();
        component.type = type;
        component.width = WidgetConfig.CANVAS_WIDTH;
        component.height = WidgetConfig.CANVAS_HEIGHT;
        component.zIndex = 0;
        return component;
    }

    static WidgetComponent button(String label, String actionType, String actionValue,
                                  float x, float y, float width, float height, int zIndex) {
        WidgetComponent component = new WidgetComponent();
        component.type = TYPE_BUTTON;
        component.content = label;
        component.actionType = actionType;
        component.actionValue = actionValue;
        component.x = x;
        component.y = y;
        component.width = width;
        component.height = height;
        component.zIndex = zIndex;
        component.cornerRadius = 22;
        return component;
    }

    JSONObject toJson() throws Exception {
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("type", type);
        JSONObject frame = new JSONObject();
        frame.put("x", x);
        frame.put("y", y);
        frame.put("width", width);
        frame.put("height", height);
        out.put("frame", frame);
        out.put("zIndex", zIndex);
        out.put("visible", visible);
        out.put("locked", locked);
        JSONObject style = new JSONObject();
        style.put("opacity", opacity);
        style.put("cornerRadius", cornerRadius);
        style.put("fillMode", fillMode);
        style.put("color", color);
        style.put("textSize", textSize);
        style.put("textAlign", textAlign);
        out.put("style", style);
        out.put("content", content);
        if (!actionType.isEmpty() || !actionValue.isEmpty()) {
            JSONObject action = new JSONObject();
            action.put("type", actionType);
            action.put("value", actionValue);
            out.put("action", action);
        }
        return out;
    }

    static WidgetComponent fromJson(JSONObject in) {
        WidgetComponent component = new WidgetComponent();
        component.id = safe(in.optString("id", null), component.id);
        component.type = safe(in.optString("type", null), TYPE_TEXT);
        JSONObject frame = in.optJSONObject("frame");
        if (frame != null) {
            component.x = (float) frame.optDouble("x", 0);
            component.y = (float) frame.optDouble("y", 0);
            component.width = positive((float) frame.optDouble("width", 200), 200);
            component.height = positive((float) frame.optDouble("height", 80), 80);
        }
        component.zIndex = in.optInt("zIndex", 0);
        component.visible = in.optBoolean("visible", true);
        component.locked = in.optBoolean("locked", false);
        JSONObject style = in.optJSONObject("style");
        if (style != null) {
            component.opacity = clamp((float) style.optDouble("opacity", 1), 0, 1);
            component.cornerRadius = Math.max(0, (float) style.optDouble("cornerRadius", 0));
            component.fillMode = safe(style.optString("fillMode", null), "cover");
            component.color = safe(style.optString("color", null), "#FFFFFFFF");
            component.textSize = positive((float) style.optDouble("textSize", 28), 28);
            component.textAlign = safe(style.optString("textAlign", null), "center");
        }
        component.content = in.optString("content", "");
        JSONObject action = in.optJSONObject("action");
        if (action != null) {
            component.actionType = action.optString("type", "");
            component.actionValue = action.optString("value", "");
        }
        return component;
    }

    WidgetComponent copy() {
        try {
            WidgetComponent copy = fromJson(toJson());
            copy.id = UUID.randomUUID().toString();
            return copy;
        } catch (Exception impossible) {
            return new WidgetComponent();
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float positive(float value, float fallback) {
        return value > 0 ? value : fallback;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}

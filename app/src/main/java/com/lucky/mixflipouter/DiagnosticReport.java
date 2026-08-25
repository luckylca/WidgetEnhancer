package com.lucky.mixflipouter;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds a bounded diagnostics snapshot without user content, identifiers or file paths. */
final class DiagnosticReport {
    static final int SCHEMA_VERSION = 1;
    private static final String[] HOOK_STAGES = {
            "compatibility", "catalogue", "runtime", "live_refresh", "lyrics", "qs"
    };

    static JSONObject collect(Context source, WidgetRepository repository) throws Exception {
        Context context = source.getApplicationContext();
        long now = System.currentTimeMillis();
        Bundle health = call(context, "get_health");
        Bundle playback = call(context, "get_playback_state");
        Bundle lyrics = call(context, "get_lyrics_state");
        Bundle qs = call(context, "get_qs_tiles");

        JSONObject report = new JSONObject();
        report.put("format", "mixflip-diagnostics");
        report.put("schemaVersion", SCHEMA_VERSION);
        report.put("generatedAt", Instant.ofEpochMilli(now).toString());
        report.put("generatedAtEpochMs", now);
        report.put("privacy", new JSONObject()
                .put("userContentIncluded", false)
                .put("widgetIdentifiersIncluded", false)
                .put("filePathsIncluded", false)
                .put("tileInventoryIncluded", false));
        report.put("app", app(context));
        report.put("device", device(context));
        report.put("permissions", permissions(context));
        report.put("packages", packages(context));
        JSONObject hooks = summarizeHooks(health);
        report.put("environment", environment(context, hooks));
        report.put("hooks", hooks);
        report.put("widgets", summarizeWidgets(repository.list(), repository.revision(),
                repository.isSafeMode(), id -> repository.mediaFile(id).length()));
        report.put("playback", summarizePlayback(playback));
        report.put("lyrics", summarizeLyrics(lyrics));
        report.put("quickSettings", summarizeQuickSettings(qs));
        return report;
    }

    static JSONObject summarizeWidgets(List<WidgetConfig> widgets, long revision,
                                       boolean safeMode, MediaSizeLookup mediaSizeLookup)
            throws Exception {
        int enabled = 0;
        int componentCount = 0;
        long mediaBytes = 0;
        Map<String, Integer> mediaTypes = new TreeMap<>();
        Map<String, Integer> componentTypes = new TreeMap<>();
        Map<String, Integer> actionTypes = new TreeMap<>();
        for (WidgetConfig widget : widgets) {
            if (widget.enabled) enabled++;
            increment(mediaTypes, mediaTypeKey(widget.mediaType));
            mediaBytes += Math.max(0, mediaSizeLookup.size(widget.id));
            for (WidgetComponent component : widget.components) {
                componentCount++;
                increment(componentTypes, componentTypeKey(component.type));
                if (!component.actionType.isEmpty()) {
                    increment(actionTypes, actionTypeKey(component.actionType));
                }
            }
        }
        return widgetSummary(widgets.size(), enabled, componentCount, revision, safeMode,
                mediaBytes, mediaTypes, componentTypes, actionTypes);
    }

    static JSONObject widgetSummary(int count, int enabled, int componentCount, long revision,
                                    boolean safeMode, long mediaBytes,
                                    Map<String, Integer> mediaTypes,
                                    Map<String, Integer> componentTypes,
                                    Map<String, Integer> actionTypes) throws Exception {
        return new JSONObject()
                .put("schemaVersion", WidgetRepository.SCHEMA_VERSION)
                .put("revision", revision)
                .put("safeMode", safeMode)
                .put("count", count)
                .put("enabledCount", enabled)
                .put("componentCount", componentCount)
                .put("mediaBytes", mediaBytes)
                .put("mediaTypes", new JSONObject(mediaTypes))
                .put("componentTypes", new JSONObject(componentTypes))
                .put("actionTypes", new JSONObject(actionTypes));
    }

    private static JSONObject app(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return new JSONObject()
                .put("package", context.getPackageName())
                .put("versionName", info.versionName == null ? "" : info.versionName)
                .put("versionCode", info.getLongVersionCode());
    }

    private static JSONObject device(Context context) throws Exception {
        UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
        return new JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("androidRelease", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("buildDisplay", Build.DISPLAY)
                .put("userUnlocked", users == null || users.isUserUnlocked());
    }

    private static JSONObject permissions(Context context) throws Exception {
        NotificationManager notifications =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String listeners = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        String component = new ComponentName(context, PlaybackNotificationListener.class)
                .flattenToString();
        return new JSONObject()
                .put("camera", context.checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED)
                .put("notificationListener", listeners != null && listeners.contains(component))
                .put("notificationListenerConnected", PlaybackNotificationListener.isConnected())
                .put("notificationPolicy", notifications != null
                        && notifications.isNotificationPolicyAccessGranted())
                .put("writeSettings", Settings.System.canWrite(context));
    }

    private static JSONObject packages(Context context) throws Exception {
        return new JSONObject()
                .put("flipHome", packageInfo(context, Contract.TARGET_PACKAGE))
                .put("systemUi", packageInfo(context, Contract.SYSTEM_UI_PACKAGE))
                .put("netease", packageInfo(context, Contract.NETEASE_PACKAGE))
                .put("lsposedManager", packageInfo(context, "org.lsposed.manager"))
                .put("lsposedManagerLegacy", packageInfo(context, "io.github.lsposed.manager"));
    }

    private static JSONObject environment(Context context, JSONObject hooks) throws Exception {
        boolean rootVisible = false;
        for (String path : new String[]{
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su"
        }) {
            if (new File(path).exists()) {
                rootVisible = true;
                break;
            }
        }
        boolean managerVisible = installed(context, "org.lsposed.manager")
                || installed(context, "io.github.lsposed.manager");
        boolean hookEvidence = false;
        for (String stage : HOOK_STAGES) {
            JSONObject item = hooks.optJSONObject(stage);
            if (item != null && item.optLong("reportedAtEpochMs", 0) > 0) {
                hookEvidence = true;
                break;
            }
        }
        return new JSONObject()
                .put("rootIndicator", rootVisible ? "su_binary_visible" : "not_detectable_by_app")
                .put("lsposedManagerVisible", managerVisible)
                .put("lsposedHookEvidence", hookEvidence);
    }

    private static JSONObject packageInfo(Context context, String packageName) throws Exception {
        JSONObject out = new JSONObject().put("package", packageName);
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            out.put("installed", true);
            out.put("versionName", info.versionName == null ? "" : info.versionName);
            out.put("versionCode", info.getLongVersionCode());
        } catch (PackageManager.NameNotFoundException ignored) {
            out.put("installed", false);
        }
        return out;
    }

    private static boolean installed(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private static JSONObject summarizeHooks(Bundle health) throws Exception {
        JSONObject out = new JSONObject();
        out.put("providerAvailable", health != null);
        for (String stage : HOOK_STAGES) {
            JSONObject item = new JSONObject();
            item.put("ok", health != null && health.getBoolean(stage + "_ok"));
            item.put("reportedAtEpochMs", health == null ? 0 : health.getLong(stage + "_time", 0));
            out.put(stage, item);
        }
        return out;
    }

    private static JSONObject summarizePlayback(Bundle playback) throws Exception {
        JSONObject out = new JSONObject();
        boolean available = playback != null && playback.getBoolean("available");
        out.put("providerAvailable", playback != null);
        out.put("sessionAvailable", available);
        out.put("sourcePackage", available ? playback.getString("package", "") : "");
        out.put("playing", available && playback.getBoolean("playing"));
        out.put("durationKnown", available && playback.getLong("duration", 0) > 0);
        out.put("artworkAvailable", playback != null
                && playback.getBoolean("artwork_available"));
        return out;
    }

    private static JSONObject summarizeLyrics(Bundle lyrics) throws Exception {
        boolean available = lyrics != null && lyrics.getBoolean("available");
        return new JSONObject()
                .put("providerAvailable", lyrics != null)
                .put("available", available)
                .put("source", available ? lyrics.getString("source", "") : "")
                .put("lineCount", available ? lyrics.getInt("line_count", 0) : 0)
                .put("publishedAtEpochMs", available
                        ? lyrics.getLong("published_at", 0) : 0);
    }

    private static JSONObject summarizeQuickSettings(Bundle qs) throws Exception {
        int tileCount = 0;
        int availableCount = 0;
        if (qs != null) {
            try {
                JSONArray tiles = new JSONArray(qs.getString("tiles_json", "[]"));
                tileCount = tiles.length();
                for (int i = 0; i < tiles.length(); i++) {
                    JSONObject tile = tiles.optJSONObject(i);
                    if (tile != null && tile.optBoolean("available")) availableCount++;
                }
            } catch (Throwable ignored) {
            }
        }
        return new JSONObject()
                .put("providerAvailable", qs != null)
                .put("bridgeReady", qs != null && qs.getBoolean("bridge_ready"))
                .put("publishedAtEpochMs", qs == null ? 0 : qs.getLong("published_at", 0))
                .put("systemUiVersion", qs == null ? "" : qs.getString("systemui_version", ""))
                .put("tileCount", tileCount)
                .put("availableTileCount", availableCount)
                .put("lastResultOk", qs != null && qs.getBoolean("last_result_ok"))
                .put("lastResultAtEpochMs", qs == null ? 0 : qs.getLong("last_result_at", 0));
    }

    private static Bundle call(Context context, String method) {
        try {
            return context.getContentResolver().call(Contract.PROVIDER_URI, method, null, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private static String mediaTypeKey(String value) {
        if (WidgetComponent.TYPE_IMAGE.equals(value) || WidgetComponent.TYPE_VIDEO.equals(value)
                || "none".equals(value)) return value;
        return "unknown";
    }

    private static String componentTypeKey(String value) {
        if (WidgetComponent.TYPE_IMAGE.equals(value) || WidgetComponent.TYPE_VIDEO.equals(value)
                || WidgetComponent.TYPE_TEXT.equals(value) || WidgetComponent.TYPE_TIME.equals(value)
                || WidgetComponent.TYPE_BUTTON.equals(value)
                || WidgetComponent.TYPE_SONG_TITLE.equals(value)
                || WidgetComponent.TYPE_ARTIST.equals(value)
                || WidgetComponent.TYPE_LYRIC_PREVIOUS.equals(value)
                || WidgetComponent.TYPE_LYRIC_CURRENT.equals(value)
                || WidgetComponent.TYPE_LYRIC_NEXT.equals(value)
                || WidgetComponent.TYPE_PLAYBACK_PROGRESS.equals(value)
                || WidgetComponent.TYPE_ALBUM_ART.equals(value)) return value;
        return "unknown";
    }

    private static String actionTypeKey(String value) {
        if (ActionSpec.LAUNCH_APP.equals(value) || ActionSpec.OPEN_URI.equals(value)
                || ActionSpec.SEND_BROADCAST.equals(value) || ActionSpec.VOLUME_UP.equals(value)
                || ActionSpec.VOLUME_DOWN.equals(value) || ActionSpec.MUTE_TOGGLE.equals(value)
                || ActionSpec.FLASHLIGHT_ON.equals(value) || ActionSpec.FLASHLIGHT_OFF.equals(value)
                || ActionSpec.FLASHLIGHT_TOGGLE.equals(value)
                || ActionSpec.DO_NOT_DISTURB_TOGGLE.equals(value)
                || ActionSpec.AUTO_ROTATE_TOGGLE.equals(value)
                || ActionSpec.LOCK_SCREEN.equals(value) || ActionSpec.MEDIA_PREVIOUS.equals(value)
                || ActionSpec.MEDIA_PLAY_PAUSE.equals(value) || ActionSpec.MEDIA_NEXT.equals(value)
                || ActionSpec.QS_TILE.equals(value)) return value;
        return "unknown";
    }

    interface MediaSizeLookup {
        long size(String widgetId);
    }

    private DiagnosticReport() {}
}

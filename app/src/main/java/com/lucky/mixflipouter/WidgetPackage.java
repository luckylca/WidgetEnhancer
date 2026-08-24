package com.lucky.mixflipouter;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Versioned .mixflipwidget ZIP reader/writer with bounded extraction and media integrity checks. */
final class WidgetPackage {
    static final int FORMAT_VERSION = 1;
    private static final long MAX_JSON_BYTES = 2L * 1024 * 1024;
    private static final long MAX_MEDIA_BYTES = 512L * 1024 * 1024;
    private static final String MANIFEST = "manifest.json";
    private static final String WIDGET = "widget.json";
    private static final String MEDIA = "assets/media";

    static void exportWidget(Context context, WidgetRepository repository,
                             String widgetId, Uri destination) throws Exception {
        WidgetConfig config = repository.get(widgetId);
        if (config == null) throw new IllegalArgumentException("找不到要导出的 Widget");
        File media = repository.mediaFile(widgetId);
        boolean hasMedia = media.isFile();
        JSONObject manifest = new JSONObject()
                .put("format", "mixflipwidget")
                .put("formatVersion", FORMAT_VERSION)
                .put("schemaVersion", WidgetRepository.SCHEMA_VERSION)
                .put("appVersion", appVersion(context))
                .put("exportedAt", System.currentTimeMillis());
        JSONObject asset = new JSONObject()
                .put("included", hasMedia)
                .put("path", hasMedia ? MEDIA : JSONObject.NULL)
                .put("size", hasMedia ? media.length() : 0)
                .put("sha256", hasMedia ? sha256(media) : "")
                .put("mimeType", config.mimeType);
        manifest.put("media", asset);

        try (OutputStream raw = context.getContentResolver().openOutputStream(destination, "w")) {
            if (raw == null) throw new IllegalStateException("无法创建导出文件");
            try (ZipOutputStream zip = new ZipOutputStream(raw)) {
                writeText(zip, MANIFEST, manifest.toString(2));
                writeText(zip, WIDGET, config.toJson().toString(2));
                if (hasMedia) writeFile(zip, MEDIA, media);
            }
        }
    }

    static WidgetConfig importWidget(Context context, WidgetRepository repository,
                                     Uri source) throws Exception {
        File temporaryMedia = File.createTempFile("mixflip-import-", ".media", context.getCacheDir());
        try {
        boolean mediaSeen = false;
        byte[] manifestBytes = null;
        byte[] widgetBytes = null;
        Set<String> entries = new HashSet<>();
        try (InputStream raw = context.getContentResolver().openInputStream(source)) {
            if (raw == null) throw new IllegalStateException("无法读取导入文件");
            try (ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory()) continue;
                    if (!entries.add(name)) throw new IllegalArgumentException("压缩包包含重复条目：" + name);
                    if (MANIFEST.equals(name)) manifestBytes = readBounded(zip, MAX_JSON_BYTES);
                    else if (WIDGET.equals(name)) widgetBytes = readBounded(zip, MAX_JSON_BYTES);
                    else if (MEDIA.equals(name)) {
                        copyBounded(zip, temporaryMedia, MAX_MEDIA_BYTES);
                        mediaSeen = true;
                    } else {
                        throw new IllegalArgumentException("压缩包包含不支持的条目：" + name);
                    }
                    zip.closeEntry();
                }
            }
        }
        if (manifestBytes == null || widgetBytes == null) {
            throw new IllegalArgumentException("缺少 manifest.json 或 widget.json");
        }
        JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        if (!"mixflipwidget".equals(manifest.optString("format"))) {
            throw new IllegalArgumentException("不是 MIX Flip Widget 文件");
        }
        if (manifest.optInt("formatVersion", -1) != FORMAT_VERSION) {
            throw new IllegalArgumentException("暂不支持此文件格式版本");
        }
        int schemaVersion = manifest.optInt("schemaVersion", -1);
        if (schemaVersion < 1 || schemaVersion > WidgetRepository.SCHEMA_VERSION) {
            throw new IllegalArgumentException("暂不支持此 Widget 配置版本");
        }
        JSONObject media = manifest.optJSONObject("media");
        boolean expectedMedia = media != null && media.optBoolean("included", false);
        if (expectedMedia != mediaSeen) throw new IllegalArgumentException("媒体清单与压缩包不一致");
        if (mediaSeen) {
            if (!MEDIA.equals(media.optString("path", ""))) {
                throw new IllegalArgumentException("媒体路径与清单不一致");
            }
            long expectedSize = media.optLong("size", -1);
            String expectedHash = media.optString("sha256", "").toLowerCase(Locale.ROOT);
            if (expectedSize != temporaryMedia.length()) throw new IllegalArgumentException("媒体大小校验失败");
            if (!expectedHash.equals(sha256(temporaryMedia))) throw new IllegalArgumentException("媒体 SHA-256 校验失败");
        }
        WidgetConfig imported = WidgetConfig.fromJson(
                new JSONObject(new String(widgetBytes, StandardCharsets.UTF_8)));
        sanitize(imported, mediaSeen);
        imported.syncLegacyActionsFromComponents();
        return repository.importPackage(imported, mediaSeen ? temporaryMedia : null);
        } finally {
            temporaryMedia.delete();
        }
    }

    static void sanitize(WidgetConfig config, boolean hasMedia) {
        config.name = bounded(config.name, 80);
        config.enabled = config.enabled && (!isMedia(config.mediaType) || hasMedia);
        if (!hasMedia && isMedia(config.mediaType)) {
            config.mediaType = "none";
            config.mimeType = "application/octet-stream";
            config.components.removeIf(component -> WidgetComponent.TYPE_IMAGE.equals(component.type)
                    || WidgetComponent.TYPE_VIDEO.equals(component.type));
        }
        if (config.components.size() > 64) {
            config.components.subList(64, config.components.size()).clear();
        }
        config.components.removeIf(component -> !knownType(component.type));
        for (WidgetComponent component : config.components) {
            component.x = clamp(component.x, 0, WidgetConfig.CANVAS_WIDTH - 1);
            component.y = clamp(component.y, 0, WidgetConfig.CANVAS_HEIGHT - 1);
            component.width = clamp(component.width, 1, WidgetConfig.CANVAS_WIDTH - component.x);
            component.height = clamp(component.height, 1, WidgetConfig.CANVAS_HEIGHT - component.y);
            component.opacity = clamp(component.opacity, 0, 1);
            component.cornerRadius = clamp(component.cornerRadius, 0,
                    Math.min(component.width, component.height) / 2f);
            component.textSize = clamp(component.textSize, 1, 200);
            component.zIndex = Math.max(-10_000, Math.min(10_000, component.zIndex));
            component.fillMode = knownFillMode(component.fillMode) ? component.fillMode : "cover";
            component.textAlign = knownTextAlign(component.textAlign) ? component.textAlign : "center";
            component.color = validColor(component.color) ? component.color : "#FFFFFFFF";
            component.content = bounded(component.content, 500);
            component.actionType = bounded(component.actionType, 80);
            component.actionValue = bounded(component.actionValue, 2048);
            if (WidgetComponent.TYPE_BUTTON.equals(component.type)
                    && !knownAction(component.actionType)) {
                component.actionType = "";
                component.actionValue = "";
            }
        }
    }

    private static boolean knownType(String type) {
        return WidgetComponent.TYPE_IMAGE.equals(type) || WidgetComponent.TYPE_VIDEO.equals(type)
                || WidgetComponent.TYPE_TEXT.equals(type) || WidgetComponent.TYPE_TIME.equals(type)
                || WidgetComponent.TYPE_BUTTON.equals(type) || WidgetComponent.TYPE_SONG_TITLE.equals(type)
                || WidgetComponent.TYPE_ARTIST.equals(type) || WidgetComponent.TYPE_LYRIC_CURRENT.equals(type)
                || WidgetComponent.TYPE_LYRIC_NEXT.equals(type)
                || WidgetComponent.TYPE_PLAYBACK_PROGRESS.equals(type)
                || WidgetComponent.TYPE_ALBUM_ART.equals(type);
    }

    private static boolean isMedia(String type) {
        return WidgetComponent.TYPE_IMAGE.equals(type) || WidgetComponent.TYPE_VIDEO.equals(type);
    }

    private static boolean knownAction(String type) {
        return type.isEmpty() || ActionSpec.LAUNCH_APP.equals(type) || ActionSpec.OPEN_URI.equals(type)
                || ActionSpec.SEND_BROADCAST.equals(type) || ActionSpec.VOLUME_UP.equals(type)
                || ActionSpec.VOLUME_DOWN.equals(type) || ActionSpec.MUTE_TOGGLE.equals(type)
                || ActionSpec.FLASHLIGHT_ON.equals(type) || ActionSpec.FLASHLIGHT_OFF.equals(type)
                || ActionSpec.FLASHLIGHT_TOGGLE.equals(type) || ActionSpec.LOCK_SCREEN.equals(type)
                || ActionSpec.MEDIA_PREVIOUS.equals(type)
                || ActionSpec.MEDIA_PLAY_PAUSE.equals(type) || ActionSpec.MEDIA_NEXT.equals(type);
    }

    private static boolean knownFillMode(String value) {
        return "cover".equals(value) || "contain".equals(value) || "stretch".equals(value);
    }

    private static boolean knownTextAlign(String value) {
        return "left".equals(value) || "center".equals(value) || "right".equals(value);
    }

    private static boolean validColor(String value) {
        if (value == null) return false;
        return value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?");
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }

    private static byte[] readBounded(InputStream input, long limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output, limit);
        return output.toByteArray();
    }

    private static void copyBounded(InputStream input, File destination, long limit) throws Exception {
        try (FileOutputStream output = new FileOutputStream(destination, false)) {
            copy(input, output, limit);
            output.getFD().sync();
        }
    }

    private static void copy(InputStream input, OutputStream output, long limit) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > limit) throw new IllegalArgumentException("文件内容超过允许大小");
            output.write(buffer, 0, count);
        }
    }

    private static void writeText(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void writeFile(ZipOutputStream zip, String name, File file) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) zip.write(buffer, 0, count);
        }
        zip.closeEntry();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private WidgetPackage() {}
}

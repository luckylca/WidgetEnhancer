package com.lucky.mixflipouter;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class WidgetRepository {
    static final int SCHEMA_VERSION = 2;
    private static final Object LOCK = new Object();
    private static final String STORE_FILE = "widgets-v1.json";
    private static final String ASSET_ROOT = "widgets";

    private final Context context;
    private final AtomicFile store;

    WidgetRepository(Context context) {
        this.context = context.getApplicationContext();
        this.store = new AtomicFile(new File(this.context.getFilesDir(), STORE_FILE));
        ensureMigrated();
    }

    List<WidgetConfig> list() {
        synchronized (LOCK) {
            return new ArrayList<>(readState().widgets);
        }
    }

    WidgetConfig get(String id) {
        synchronized (LOCK) {
            for (WidgetConfig config : readState().widgets) {
                if (config.id.equals(id)) return config;
            }
            return null;
        }
    }

    long revision() {
        synchronized (LOCK) {
            return readState().revision;
        }
    }

    boolean isSafeMode() {
        synchronized (LOCK) {
            return readState().safeMode;
        }
    }

    void setSafeMode(boolean enabled) {
        synchronized (LOCK) {
            State state = readState();
            if (state.safeMode == enabled) return;
            state.safeMode = enabled;
            writeNext(state);
        }
    }

    WidgetConfig create(String name) {
        synchronized (LOCK) {
            State state = readState();
            WidgetConfig config = new WidgetConfig();
            config.id = UUID.randomUUID().toString();
            config.name = normalizeName(name, "新建 Widget " + (state.widgets.size() + 1));
            state.widgets.add(config);
            writeNext(state);
            return config;
        }
    }

    WidgetConfig duplicate(String id) {
        synchronized (LOCK) {
            State state = readState();
            for (WidgetConfig source : state.widgets) {
                if (!source.id.equals(id)) continue;
                WidgetConfig copy = source.duplicate(UUID.randomUUID().toString(), source.name + " 副本");
                state.widgets.add(copy);
                copyAsset(source.id, copy.id);
                writeNext(state);
                return copy;
            }
            return null;
        }
    }

    boolean save(WidgetConfig config) {
        synchronized (LOCK) {
            State state = readState();
            for (int i = 0; i < state.widgets.size(); i++) {
                if (state.widgets.get(i).id.equals(config.id)) {
                    config.name = normalizeName(config.name, "未命名 Widget");
                    state.widgets.set(i, config);
                    writeNext(state);
                    return true;
                }
            }
            return false;
        }
    }

    boolean delete(String id) {
        synchronized (LOCK) {
            State state = readState();
            if (state.widgets.size() <= 1) return false;
            boolean removed = state.widgets.removeIf(item -> item.id.equals(id));
            if (!removed) return false;
            deleteTree(widgetDir(id));
            writeNext(state);
            return true;
        }
    }

    File mediaFile(String id) {
        return new File(widgetDir(id), "media");
    }

    void importMedia(String id, Uri source) throws Exception {
        File target = mediaFile(id);
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        try (java.io.InputStream in = context.getContentResolver().openInputStream(source);
             FileOutputStream out = new FileOutputStream(target, false)) {
            if (in == null) throw new IllegalStateException("无法读取媒体");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
        }
    }

    void clearMedia(String id) {
        File file = mediaFile(id);
        if (file.exists()) file.delete();
    }

    private void ensureMigrated() {
        synchronized (LOCK) {
            if (store.getBaseFile().isFile()) {
                State current = readState();
                if (current.schemaVersion < SCHEMA_VERSION) writeNext(current);
                return;
            }
            SharedPreferences legacy = context.getSharedPreferences(Contract.PREFS, 0);
            WidgetConfig config = new WidgetConfig();
            config.id = Contract.DEFAULT_WIDGET_ID;
            config.name = legacy.getString("name", "我的外屏");
            config.enabled = legacy.getBoolean("enabled", true);
            config.mediaType = legacy.getString("media_type", "none");
            config.mimeType = legacy.getString("mime_type", "application/octet-stream");
            config.loop = legacy.getBoolean("loop", true);
            config.mute = legacy.getBoolean("mute", true);
            for (int i = 0; i < Contract.BUTTON_COUNT; i++) {
                config.labels[i] = legacy.getString("button_" + i + "_label", "");
                config.actionTypes[i] = legacy.getString("button_" + i + "_type", "package");
                config.actionValues[i] = legacy.getString("button_" + i + "_value", "");
            }
            File legacyMedia = new File(context.getFilesDir(), "selected_media");
            if (legacyMedia.isFile()) copyFile(legacyMedia, mediaFile(config.id));
            State state = new State();
            state.schemaVersion = SCHEMA_VERSION;
            state.revision = 1;
            state.safeMode = false;
            state.widgets.add(config);
            writeState(state);
        }
    }

    private State readState() {
        try (FileInputStream input = store.openRead();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            JSONObject root = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            State state = new State();
            state.schemaVersion = root.optInt("schemaVersion", 1);
            state.revision = root.optLong("revision", 1);
            state.safeMode = root.optBoolean("safeMode", false);
            JSONArray widgets = root.optJSONArray("widgets");
            if (widgets != null) {
                for (int i = 0; i < widgets.length(); i++) {
                    JSONObject item = widgets.optJSONObject(i);
                    if (item != null) state.widgets.add(WidgetConfig.fromJson(item));
                }
            }
            if (state.widgets.isEmpty()) throw new IllegalStateException("Widget store is empty");
            return state;
        } catch (Throwable error) {
            State fallback = new State();
            fallback.revision = System.currentTimeMillis();
            fallback.widgets.add(new WidgetConfig());
            return fallback;
        }
    }

    private void writeNext(State state) {
        state.revision = Math.max(state.revision + 1, System.currentTimeMillis());
        writeState(state);
        context.getContentResolver().notifyChange(Contract.PROVIDER_URI, null);
    }

    private void writeState(State state) {
        FileOutputStream output = null;
        try {
            JSONObject root = new JSONObject();
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("revision", state.revision);
            root.put("safeMode", state.safeMode);
            JSONArray widgets = new JSONArray();
            for (WidgetConfig config : state.widgets) widgets.put(config.toJson());
            root.put("widgets", widgets);
            output = store.startWrite();
            output.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            store.finishWrite(output);
        } catch (Throwable error) {
            if (output != null) store.failWrite(output);
            throw new IllegalStateException("无法保存 Widget 数据", error);
        }
    }

    private File widgetDir(String id) {
        return new File(new File(context.getFilesDir(), ASSET_ROOT), id);
    }

    private void copyAsset(String from, String to) {
        File source = mediaFile(from);
        if (source.isFile()) copyFile(source, mediaFile(to));
    }

    private static void copyFile(File source, File target) {
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
        } catch (Throwable error) {
            target.delete();
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static String normalizeName(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

    private static final class State {
        int schemaVersion = SCHEMA_VERSION;
        long revision;
        boolean safeMode;
        final List<WidgetConfig> widgets = new ArrayList<>();
    }
}

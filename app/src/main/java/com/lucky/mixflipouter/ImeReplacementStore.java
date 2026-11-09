package com.lucky.mixflipouter;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durable device-protected source of truth for the selected cover-screen IME. */
final class ImeReplacementStore {
    private static final String FILE_NAME = "ime_replacement.json";
    private static final Pattern LEGACY_ENABLED = Pattern.compile(
            "<boolean\\s+name=\"replace_with_wechat\"\\s+value=\"(true|false)\"\\s*/>");
    private static final Pattern LEGACY_TARGET = Pattern.compile(
            "<string\\s+name=\"replacement_package\">(.*?)</string>");

    static final class State {
        final boolean configured;
        final String target;
        final String source;

        State(boolean configured, String target, String source) {
            this.configured = configured;
            this.target = target == null ? "" : target;
            this.source = source == null ? "" : source;
        }

        boolean enabled() { return !target.isEmpty(); }
    }

    static State read(Context context) throws Exception {
        Context device = context.createDeviceProtectedStorageContext();
        File directory = device.getFilesDir();
        File legacyFile = new File(device.getDataDir(),
                "shared_prefs/ime_scope_status.xml");
        String legacyXml = legacyFile.isFile()
                ? new String(Files.readAllBytes(legacyFile.toPath()), StandardCharsets.UTF_8)
                : "";
        State state = read(directory, legacyXml);
        if ("legacy".equals(state.source)) {
            write(directory, state.target);
            return read(directory, "");
        }
        return state;
    }

    static State read(File directory, String legacyXml) throws Exception {
        File source = new File(directory, FILE_NAME);
        if (source.isFile()) {
            JSONObject value = new JSONObject(new String(Files.readAllBytes(source.toPath()),
                    StandardCharsets.UTF_8));
            if (!value.optBoolean("configured", false)) {
                throw new IOException("Replacement preference file is not configured");
            }
            String target = validateTarget(value.optString("target", ""));
            return new State(true, target, "file");
        }

        Matcher enabled = LEGACY_ENABLED.matcher(legacyXml == null ? "" : legacyXml);
        if (enabled.find()) {
            String target = "true".equals(enabled.group(1))
                    ? ImeReplacementSettings.WECHAT_PACKAGE : "";
            Matcher legacyTarget = LEGACY_TARGET.matcher(legacyXml);
            if (legacyTarget.find()) {
                String candidate = legacyTarget.group(1);
                if (candidate.isEmpty() || ImeReplacementSettings.WECHAT_PACKAGE.equals(candidate)
                        || ImeReplacementSettings.DOUBAO_PACKAGE.equals(candidate)) {
                    target = candidate;
                }
            }
            return new State(true, validateTarget(target), "legacy");
        }
        return new State(false, "", "default");
    }

    static boolean write(Context context, String target) throws Exception {
        Context device = context.createDeviceProtectedStorageContext();
        return write(device.getFilesDir(), target);
    }

    static boolean write(File directory, String target) throws Exception {
        target = validateTarget(target);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create device-protected IME settings directory");
        }
        JSONObject state = new JSONObject().put("schemaVersion", 1)
                .put("configured", true).put("enabled", !target.isEmpty())
                .put("target", target).put("updatedAtEpochMs", System.currentTimeMillis());
        File destination = new File(directory, FILE_NAME);
        File temporary = new File(directory, FILE_NAME + ".tmp");
        byte[] bytes = state.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable atomicMoveUnavailable) {
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        State verified = read(directory, "");
        if (!verified.configured || !target.equals(verified.target)) {
            throw new IOException("IME replacement file read-back did not match the request");
        }
        return true;
    }

    private static String validateTarget(String target) throws IOException {
        if (target == null || (!target.isEmpty()
                && !ImeReplacementSettings.WECHAT_PACKAGE.equals(target)
                && !ImeReplacementSettings.DOUBAO_PACKAGE.equals(target))) {
            throw new IOException("Unsupported input-method replacement target");
        }
        return target;
    }

    private ImeReplacementStore() { }
}

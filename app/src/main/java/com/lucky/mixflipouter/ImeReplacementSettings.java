package com.lucky.mixflipouter;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import de.robv.android.xposed.XSharedPreferences;

/** Shared setting read by system_server and edited by the module app. */
final class ImeReplacementSettings {
    static final String PREFS_NAME = "outer_ime";
    static final String KEY_ENABLED = "replace_with_wechat";
    static final String WECHAT_PACKAGE = "com.tencent.wetype";
    static final String DOUBAO_PACKAGE = "com.bytedance.android.doubaoime";

    private static volatile XSharedPreferences systemPrefs;
    private static volatile ContentResolver systemResolver;

    /** Keeps a failed Provider read distinct from a known, explicitly disabled preference. */
    static final class ReadResult {
        final boolean known;
        final String target;
        final String source;
        final String error;

        private ReadResult(boolean known, String target, String source, String error) {
            this.known = known;
            this.target = target == null ? "" : target;
            this.source = source == null ? "" : source;
            this.error = error == null ? "" : error;
        }

        static ReadResult known(String target, String source) {
            return new ReadResult(true, target, source, "");
        }

        static ReadResult unknown(Throwable error) {
            return new ReadResult(false, "", "unavailable", error == null
                    ? "Provider returned no data" : error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage()));
        }
    }

    static boolean write(Context context, boolean enabled) {
        return writeTarget(context, enabled ? WECHAT_PACKAGE : "");
    }

    static boolean writeTarget(Context context, String target) {
        if (target == null || (!target.isEmpty() && !WECHAT_PACKAGE.equals(target)
                && !DOUBAO_PACKAGE.equals(target))) {
            ModuleLogger.warn("Unsupported input-method replacement target");
            return false;
        }
        try {
            Bundle extras = new Bundle();
            extras.putBoolean("enabled", !target.isEmpty());
            extras.putString("target", target);
            Bundle result = context.getContentResolver().call(ImeScopeStatusProvider.URI,
                    "set_replacement_status", null, extras);
            if (result == null || !result.getBoolean("saved", false)) {
                ModuleLogger.warn("Input-method replacement preference was not saved");
                return false;
            }
            Bundle verified = context.getContentResolver().call(ImeScopeStatusProvider.URI,
                    "get_replacement_status", null, null);
            ReadResult readBack = fromProvider(verified);
            if (!readBack.known || !target.equals(readBack.target)) {
                ModuleLogger.warn("Input-method preference read-back mismatch; requested=" + target
                        + ", observed=" + (readBack.known ? readBack.target : "UNKNOWN"));
                return false;
            }
            ModuleLogger.logFeatureState("Input-method replacement preference", "SAVED",
                    "Device-protected file write and fresh raw read-back verified; target="
                            + (target.isEmpty() ? "OFF" : target));
            return true;
        } catch (Throwable error) {
            ModuleLogger.logException("Unable to save/verify input-method preference", error);
            return false;
        }
    }

    static ReadResult readResult(Context context) {
        try {
            Bundle result = context.getContentResolver().call(ImeScopeStatusProvider.URI,
                    "get_replacement_status", null, null);
            if (result == null) return ReadResult.unknown(null);
            ReadResult provider = fromProvider(result);
            if (!provider.known) return provider;
            if (!result.getBoolean("configured", false)) {
                SharedPreferences legacy = context.getSharedPreferences(PREFS_NAME,
                        Context.MODE_PRIVATE);
                if (legacy.contains(KEY_ENABLED)) {
                    String oldTarget = legacy.getBoolean(KEY_ENABLED, false)
                            ? WECHAT_PACKAGE : "";
                    if (!writeTarget(context, oldTarget)) {
                        ModuleLogger.warnThrottled("ime_legacy_migration",
                                "Legacy input-method preference remains the active fallback",
                                60_000L);
                        return ReadResult.known(oldTarget, "legacy_unmigrated");
                    }
                    return ReadResult.known(oldTarget, "legacy_migrated");
                }
            }
            return provider;
        } catch (Throwable error) {
            ModuleLogger.logExceptionThrottled("Unable to read input-method preference", error);
            return ReadResult.unknown(error);
        }
    }

    static boolean read(Context context) {
        ReadResult result = readResult(context);
        return result.known && WECHAT_PACKAGE.equals(result.target);
    }

    static String readTarget(Context context) {
        ReadResult result = readResult(context);
        return result.known ? result.target : "";
    }

    static boolean isEnabledInSystemServer() {
        ReadResult result = targetStatusInSystemServer();
        return result.known && !result.target.isEmpty();
    }

    static String targetInSystemServer() {
        ReadResult result = targetStatusInSystemServer();
        return result.known ? result.target : "";
    }

    static ReadResult targetStatusInSystemServer() {
        ContentResolver resolver = systemResolver;
        if (resolver == null) {
            ReadResult legacy = readExplicitLegacySystemPreference();
            return legacy == null ? ReadResult.unknown(new IllegalStateException(
                    "system_server has no IME preference ContentResolver yet")) : legacy;
        }
        try {
            Bundle result = resolver.call(ImeScopeStatusProvider.URI,
                    "get_replacement_status", null, null);
            if (result != null) {
                ReadResult provider = fromProvider(result);
                if (provider.known) {
                    if (!result.getBoolean("configured", false)) {
                        ReadResult legacy = readExplicitLegacySystemPreference();
                        if (legacy != null) return legacy;
                    }
                    return provider;
                }
                return provider;
            }
            throw new IllegalStateException("IME preference Provider returned no result");
        } catch (Throwable error) {
            ModuleLogger.logExceptionThrottled(
                    "system_server cannot read input-method preference Provider", error);
            return ReadResult.unknown(error);
        }
    }

    private static ReadResult readExplicitLegacySystemPreference() {
        try {
            XSharedPreferences prefs = systemPrefs;
            if (prefs == null) {
                synchronized (ImeReplacementSettings.class) {
                    prefs = systemPrefs;
                    if (prefs == null) {
                        prefs = new XSharedPreferences(Contract.MODULE_PACKAGE, PREFS_NAME);
                        systemPrefs = prefs;
                    }
                }
            }
            prefs.reload();
            if (!prefs.contains(KEY_ENABLED)) return null;
            return ReadResult.known(prefs.getBoolean(KEY_ENABLED, false)
                    ? WECHAT_PACKAGE : "", "legacy_xsharedpreferences");
        } catch (Throwable error) {
            ModuleLogger.logExceptionThrottled(
                    "Unable to read legacy input-method preference", error);
            return null;
        }
    }

    static ReadResult fromProvider(Bundle result) {
        if (result == null) return ReadResult.unknown(null);
        return fromProvider(!result.containsKey("available")
                        || result.getBoolean("available", false),
                result.getBoolean("configured", false),
                result.getBoolean("enabled", false),
                result.getString("target", result.getBoolean("enabled", false)
                        ? WECHAT_PACKAGE : ""),
                result.getString("error", "IME preference store unavailable"));
    }

    static ReadResult fromProvider(boolean available, boolean configured, boolean enabled,
                                   String target, String error) {
        if (!available) {
            return ReadResult.unknown(new IllegalStateException(
                    error == null ? "IME preference store unavailable" : error));
        }
        return fromProvider(configured, enabled,
                target == null ? enabled ? WECHAT_PACKAGE : "" : target);
    }

    static ReadResult fromProvider(boolean configured, boolean enabled, String target) {
        if (!configured) return ReadResult.known("", "provider_default");
        if (target == null) target = enabled ? WECHAT_PACKAGE : "";
        if (target == null || (!target.isEmpty() && !WECHAT_PACKAGE.equals(target)
                && !DOUBAO_PACKAGE.equals(target))) {
            return ReadResult.unknown(new IllegalStateException(
                    "Provider returned an unsupported input-method target"));
        }
        return ReadResult.known(target, "provider");
    }

    static void setSystemResolver(ContentResolver resolver) {
        systemResolver = resolver;
    }

    private ImeReplacementSettings() {}
}

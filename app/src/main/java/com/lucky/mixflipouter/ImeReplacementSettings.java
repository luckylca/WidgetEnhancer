package com.lucky.mixflipouter;

import android.content.Context;
import android.content.ContentResolver;
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

    static boolean write(Context context, boolean enabled) {
        return writeTarget(context, enabled ? WECHAT_PACKAGE : "");
    }

    static boolean writeTarget(Context context, String target) {
        try {
            Bundle extras = new Bundle();
            extras.putBoolean("enabled", WECHAT_PACKAGE.equals(target));
            extras.putString("target", target);
            Bundle result = context.getContentResolver().call(ImeScopeStatusProvider.URI,
                    "set_replacement_status", null, extras);
            return result != null && result.getBoolean("saved", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String readTarget(Context context) {
        try {
            Bundle result = context.getContentResolver().call(ImeScopeStatusProvider.URI,
                    "get_replacement_status", null, null);
            if (result != null && result.getBoolean("configured", false)) {
                return result.getString("target", "");
            }
        } catch (Throwable ignored) {
        }
        return read(context) ? WECHAT_PACKAGE : "";
    }

    static boolean read(Context context) {
        try {
            Bundle result = context.getContentResolver().call(ImeScopeStatusProvider.URI,
                    "get_replacement_status", null, null);
            if (result != null && result.getBoolean("configured", false)) {
                return result.getBoolean("enabled", false);
            }
        } catch (Throwable ignored) {
        }
        boolean legacy = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
        write(context, legacy);
        return legacy;
    }

    static boolean isEnabledInSystemServer() {
        return WECHAT_PACKAGE.equals(targetInSystemServer());
    }

    static String targetInSystemServer() {
        ContentResolver resolver = systemResolver;
        if (resolver != null) {
            try {
                Bundle result = resolver.call(ImeScopeStatusProvider.URI,
                        "get_replacement_status", null, null);
                if (result != null && result.getBoolean("configured", false)) {
                    return result.getString("target", result.getBoolean("enabled", false)
                            ? WECHAT_PACKAGE : "");
                }
            } catch (Throwable ignored) {
            }
        }
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
            return prefs.getBoolean(KEY_ENABLED, false) ? WECHAT_PACKAGE : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void setSystemResolver(ContentResolver resolver) {
        systemResolver = resolver;
    }

    private ImeReplacementSettings() {}
}

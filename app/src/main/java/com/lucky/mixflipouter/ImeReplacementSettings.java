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
    private static volatile XSharedPreferences systemPrefs;
    private static volatile ContentResolver systemResolver;

    static boolean write(Context context, boolean enabled) {
        try {
            Bundle extras = new Bundle();
            extras.putBoolean("enabled", enabled);
            Bundle result = context.getContentResolver().call(ImeScopeStatusProvider.URI,
                    "set_replacement_status", null, extras);
            return result != null && result.getBoolean("saved", false);
        } catch (Throwable ignored) {
            return false;
        }
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
        ContentResolver resolver = systemResolver;
        if (resolver != null) {
            try {
                Bundle result = resolver.call(ImeScopeStatusProvider.URI,
                        "get_replacement_status", null, null);
                if (result != null && result.getBoolean("configured", false)) {
                    return result.getBoolean("enabled", false);
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
            return prefs.getBoolean(KEY_ENABLED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void setSystemResolver(ContentResolver resolver) {
        systemResolver = resolver;
    }

    private ImeReplacementSettings() {}
}

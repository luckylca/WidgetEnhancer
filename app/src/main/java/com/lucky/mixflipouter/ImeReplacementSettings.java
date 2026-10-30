package com.lucky.mixflipouter;

import android.content.Context;
import android.content.SharedPreferences;

import de.robv.android.xposed.XSharedPreferences;

/** Shared setting read by system_server and edited by the module app. */
final class ImeReplacementSettings {
    static final String PREFS_NAME = "outer_ime";
    static final String KEY_ENABLED = "replace_with_wechat";
    private static volatile XSharedPreferences systemPrefs;

    @SuppressWarnings("deprecation")
    static boolean write(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_WORLD_READABLE);
        return prefs.edit().putBoolean(KEY_ENABLED, enabled).commit();
    }

    static boolean read(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    static boolean isEnabledInSystemServer() {
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

    private ImeReplacementSettings() {}
}

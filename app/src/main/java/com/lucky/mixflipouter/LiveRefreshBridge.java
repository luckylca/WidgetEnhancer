package com.lucky.mixflipouter;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Rebuilds selected custom hosts and an open settings screen when app data changes. */
final class LiveRefreshBridge {
    private static final String CONFIG_CLASS =
            "com.miui.fliphome.widget.model.FlipWatchDefaultConfig";
    private static final String MODEL_CLASS =
            "com.miui.fliphome.widget.model.FlipWidgetModel";

    private static final Object LOCK = new Object();
    private static WeakReference<Object> settingsViewModel = new WeakReference<>(null);
    private static boolean installed;
    private static Context context;
    private static ClassLoader classLoader;

    static void install(Context candidate, ClassLoader loader) {
        if (candidate == null) return;
        synchronized (LOCK) {
            context = candidate.getApplicationContext();
            classLoader = loader;
            if (installed) return;
            Handler main = new Handler(Looper.getMainLooper());
            context.getContentResolver().registerContentObserver(
                    Contract.PROVIDER_URI, true, new ContentObserver(main) {
                        @Override
                        public void onChange(boolean selfChange) {
                            main.post(LiveRefreshBridge::refreshNow);
                        }
                    });
            installed = true;
            report("live_refresh", true, "配置观察器已注册");
            XposedBridge.log("MixFlipCustom: live config observer installed");
        }
    }

    static void trackSettingsViewModel(Object viewModel) {
        settingsViewModel = new WeakReference<>(viewModel);
    }

    private static void refreshNow() {
        Context activeContext = context;
        ClassLoader loader = classLoader;
        if (activeContext == null || loader == null) return;
        try {
            reconcileSelectedWidgets(loader);
            refreshOpenSettings();
            report("live_refresh", true, "配置已即时刷新");
            XposedBridge.log("MixFlipCustom: live configuration refresh completed");
        } catch (Throwable error) {
            report("live_refresh", false, "即时刷新失败：" + error.getClass().getSimpleName());
            XposedBridge.log("MixFlipCustom: live refresh failed: " + error);
        }
    }

    private static void reconcileSelectedWidgets(ClassLoader loader) {
        Class<?> configClass = XposedHelpers.findClass(CONFIG_CLASS, loader);
        @SuppressWarnings("unchecked")
        List<Object> catalogue = (List<Object>) XposedHelpers.callStaticMethod(
                configClass, "loadAllWidget");
        Map<String, Object> currentCustom = new HashMap<>();
        for (Object info : catalogue) {
            String fileName = fileName(info);
            if (isCustom(fileName)) currentCustom.put(fileName, info);
        }

        Class<?> modelClass = XposedHelpers.findClass(MODEL_CLASS, loader);
        Object model = XposedHelpers.callStaticMethod(modelClass, "getInstance");
        @SuppressWarnings("unchecked")
        List<Object> selected = (List<Object>) XposedHelpers.callMethod(
                model, "getAddedListBlocked");
        if (selected == null) return;

        if (isSafeMode()) {
            XposedHelpers.callMethod(model, "updateShowList", new ArrayList<>(selected));
            return;
        }

        boolean hadCustom = false;
        ArrayList<Object> updated = new ArrayList<>(selected.size());
        for (Object info : selected) {
            String fileName = fileName(info);
            if (!isCustom(fileName)) {
                updated.add(info);
                continue;
            }
            hadCustom = true;
            Object replacement = currentCustom.get(fileName);
            if (replacement != null) updated.add(replacement);
        }
        if (hadCustom) XposedHelpers.callMethod(model, "updateShowList", updated);
    }

    private static void refreshOpenSettings() {
        Object viewModel = settingsViewModel.get();
        if (viewModel == null) return;
        XposedHelpers.setObjectField(viewModel, "mAddedIdList", null);
        XposedHelpers.setObjectField(viewModel, "mAllWidgets", null);
        XposedHelpers.callMethod(viewModel, "refreshShowListAsync");
    }

    private static String fileName(Object info) {
        try {
            Object value = XposedHelpers.getObjectField(info, "mFileName");
            return value == null ? "" : value.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isCustom(String fileName) {
        return fileName.startsWith(Contract.WIDGET_FILE_PREFIX);
    }

    private static boolean isSafeMode() {
        try {
            Bundle state = context.getContentResolver().call(
                    Contract.PROVIDER_URI, "get_system_state", null, null);
            return state != null && state.getBoolean("safe_mode");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void report(String stage, boolean ok, String message) {
        try {
            Bundle extras = new Bundle();
            extras.putString("stage", stage);
            extras.putBoolean("ok", ok);
            extras.putString("message", message);
            context.getContentResolver().call(
                    Contract.PROVIDER_URI, "report_hook", null, extras);
        } catch (Throwable ignored) {
        }
    }

    private LiveRefreshBridge() {
    }
}

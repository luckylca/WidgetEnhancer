package com.lucky.mixflipouter;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

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
    private static final List<RuntimeHost> runtimeHosts = new ArrayList<>();
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
                    Contract.CONFIG_URI, false, new ContentObserver(main) {
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

    static void trackRuntimeHost(ViewGroup host, String widgetId) {
        if (host == null || widgetId == null || widgetId.isEmpty()) return;
        synchronized (LOCK) {
            for (int index = runtimeHosts.size() - 1; index >= 0; index--) {
                ViewGroup current = runtimeHosts.get(index).host.get();
                if (current == null || current == host) runtimeHosts.remove(index);
            }
            runtimeHosts.add(new RuntimeHost(host, widgetId));
        }
    }

    private static void refreshNow() {
        Context activeContext = context;
        ClassLoader loader = classLoader;
        if (activeContext == null || loader == null) return;
        try {
            reconcileSelectedWidgets(loader);
            int refreshedHosts = refreshRuntimeHosts(activeContext);
            refreshOpenSettings();
            report("live_refresh", true, "配置已即时刷新，重建 " + refreshedHosts + " 个运行时页面");
            XposedBridge.log("MixFlipCustom: live configuration refresh completed, runtime hosts="
                    + refreshedHosts);
        } catch (Throwable error) {
            report("live_refresh", false, "即时刷新失败：" + error.getClass().getSimpleName());
            XposedBridge.log("MixFlipCustom: live refresh failed: " + error);
        }
    }

    private static int refreshRuntimeHosts(Context activeContext) {
        ArrayList<RuntimeHost> snapshot;
        synchronized (LOCK) {
            for (int index = runtimeHosts.size() - 1; index >= 0; index--) {
                if (runtimeHosts.get(index).host.get() == null) runtimeHosts.remove(index);
            }
            snapshot = new ArrayList<>(runtimeHosts);
        }
        int refreshed = 0;
        for (RuntimeHost tracked : snapshot) {
            ViewGroup host = tracked.host.get();
            if (host == null) continue;
            WidgetConfig config = WidgetConfig.load(activeContext, tracked.widgetId);
            prepareRuntimeHost(host);
            if (config == null || !config.enabled) continue;
            MediaWidgetView overlay = new MediaWidgetView(host.getContext(), config);
            overlay.setTag(Contract.RUNTIME_VIEW_TAG);
            host.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            refreshed++;
        }
        return refreshed;
    }

    private static void prepareRuntimeHost(ViewGroup host) {
        for (int index = host.getChildCount() - 1; index >= 0; index--) {
            View child = host.getChildAt(index);
            if (Contract.RUNTIME_VIEW_TAG.equals(child.getTag())) host.removeViewAt(index);
        }
        host.setBackgroundColor(Color.TRANSPARENT);
        host.setClickable(false);
        host.setFocusable(false);
        try {
            XposedHelpers.callMethod(host, "setTouchable", false);
        } catch (Throwable ignored) {
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

    private static final class RuntimeHost {
        final WeakReference<ViewGroup> host;
        final String widgetId;

        RuntimeHost(ViewGroup host, String widgetId) {
            this.host = new WeakReference<>(host);
            this.widgetId = widgetId;
        }
    }

    private LiveRefreshBridge() {
    }
}

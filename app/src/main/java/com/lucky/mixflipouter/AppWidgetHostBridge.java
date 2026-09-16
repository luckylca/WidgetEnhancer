package com.lucky.mixflipouter;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AppWidget host living in the FlipHome process. FlipHome lacks BIND_APPWIDGET,
 * so binding goes through the system's user-consent flow; this bridge owns the
 * shared AppWidgetHost, id allocation and orphan cleanup.
 */
final class AppWidgetHostBridge {
    private static final String TAG = "MixFlipCustom";
    private static final int HOST_ID = 0x4d495446; // "MIFT"
    private static final String PREFS = "mixflip_appwidget_host";
    private static final String KEY_ALLOCATED = "allocated_ids";
    private static final long RECONCILE_INTERVAL_MS = 60_000L;
    private static final Object LOCK = new Object();
    private static AppWidgetHost host;
    private static AppWidgetManager manager;
    private static Context appContext;
    private static long lastReconcileElapsed;
    private static java.lang.ref.WeakReference<android.app.Activity> topActivity =
            new java.lang.ref.WeakReference<>(null);

    static void initialize(Context context) {
        synchronized (LOCK) {
            if (host != null) return;
            appContext = context.getApplicationContext();
            manager = AppWidgetManager.getInstance(appContext);
            host = new AppWidgetHost(appContext, HOST_ID);
            try {
                host.startListening();
                Log.i(TAG, "AppWidget host started in FlipHome");
            } catch (Throwable error) {
                Log.w(TAG, "AppWidget host startListening failed", error);
            }
            trackActivities(appContext);
        }
    }

    /** MIUI's bind-consent page requires a live calling activity; keep track of one. */
    private static void trackActivities(Context context) {
        if (!(context instanceof android.app.Application)) return;
        try {
            ((android.app.Application) context).registerActivityLifecycleCallbacks(
                    new android.app.Application.ActivityLifecycleCallbacks() {
                        @Override
                        public void onActivityResumed(android.app.Activity activity) {
                            topActivity = new java.lang.ref.WeakReference<>(activity);
                        }

                        @Override public void onActivityCreated(android.app.Activity a, Bundle b) {}
                        @Override public void onActivityStarted(android.app.Activity a) {}
                        @Override public void onActivityPaused(android.app.Activity a) {}
                        @Override public void onActivityStopped(android.app.Activity a) {}
                        @Override public void onActivitySaveInstanceState(
                                android.app.Activity a, Bundle b) {}
                        @Override public void onActivityDestroyed(android.app.Activity a) {}
                    });
        } catch (Throwable error) {
            Log.w(TAG, "activity tracking failed", error);
        }
    }

    static android.app.Activity topActivity() {
        android.app.Activity activity = topActivity.get();
        return activity == null || activity.isFinishing() || activity.isDestroyed()
                ? null : activity;
    }

    static boolean ready() {
        synchronized (LOCK) {
            return host != null && manager != null;
        }
    }

    static int allocateId() {
        synchronized (LOCK) {
            if (host == null) return -1;
            try {
                int id = host.allocateAppWidgetId();
                remember(id);
                return id;
            } catch (Throwable error) {
                Log.w(TAG, "allocateAppWidgetId failed", error);
                return -1;
            }
        }
    }

    static boolean bindIfAllowed(int appWidgetId, ComponentName provider) {
        synchronized (LOCK) {
            if (manager == null || appWidgetId < 0 || provider == null) return false;
            try {
                return manager.bindAppWidgetIdIfAllowed(appWidgetId, provider);
            } catch (Throwable error) {
                Log.w(TAG, "bindAppWidgetIdIfAllowed failed", error);
                return false;
            }
        }
    }

    static AppWidgetProviderInfo infoFor(int appWidgetId) {
        synchronized (LOCK) {
            if (manager == null || appWidgetId < 0) return null;
            try {
                return manager.getAppWidgetInfo(appWidgetId);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    static AppWidgetHostView createView(int appWidgetId, AppWidgetProviderInfo info) {
        synchronized (LOCK) {
            if (host == null || info == null) return null;
            try {
                return host.createView(appContext, appWidgetId, info);
            } catch (Throwable error) {
                Log.w(TAG, "createView failed for id " + appWidgetId, error);
                return null;
            }
        }
    }

    static void updateOptions(int appWidgetId, int widthDp, int heightDp) {
        synchronized (LOCK) {
            if (manager == null || appWidgetId < 0) return;
            Bundle options = new Bundle();
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp);
            try {
                manager.updateAppWidgetOptions(appWidgetId, options);
            } catch (Throwable ignored) {
            }
        }
    }

    static void deleteId(int appWidgetId) {
        synchronized (LOCK) {
            if (host == null || appWidgetId < 0) return;
            try {
                host.deleteAppWidgetId(appWidgetId);
            } catch (Throwable ignored) {
            }
            forget(appWidgetId);
        }
    }

    /** Drops ids this host allocated that no longer appear in any widget config. */
    static void reconcile(List<WidgetConfig> configs) {
        synchronized (LOCK) {
            if (host == null || appContext == null) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastReconcileElapsed < RECONCILE_INTERVAL_MS) return;
            lastReconcileElapsed = now;
            Set<Integer> live = new HashSet<>();
            if (configs != null) {
                for (WidgetConfig config : configs) {
                    if (config == null) continue;
                    for (WidgetComponent component : config.components) {
                        if (WidgetComponent.TYPE_APPWIDGET.equals(component.type)
                                && component.appWidgetId >= 0) {
                            live.add(component.appWidgetId);
                        }
                    }
                }
            }
            Set<Integer> allocated = new HashSet<>(allocatedLocked());
            for (int id : allocated) {
                if (live.contains(id)) continue;
                try {
                    host.deleteAppWidgetId(id);
                    Log.i(TAG, "Released orphan AppWidget id " + id);
                } catch (Throwable ignored) {
                }
                forget(id);
            }
        }
    }

    private static void remember(int appWidgetId) {
        Set<Integer> allocated = allocatedLocked();
        if (!allocated.add(appWidgetId)) return;
        prefs().edit().putStringSet(KEY_ALLOCATED, toStrings(allocated)).apply();
    }

    private static void forget(int appWidgetId) {
        Set<Integer> allocated = allocatedLocked();
        if (!allocated.remove(appWidgetId)) return;
        prefs().edit().putStringSet(KEY_ALLOCATED, toStrings(allocated)).apply();
    }

    private static Set<Integer> allocatedLocked() {
        Set<String> stored = prefs().getStringSet(KEY_ALLOCATED, null);
        Set<Integer> out = new HashSet<>();
        if (stored != null) {
            for (String value : stored) {
                try {
                    out.add(Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    private static Set<String> toStrings(Set<Integer> values) {
        Set<String> out = new HashSet<>();
        for (int value : values) out.add(Integer.toString(value));
        return out;
    }

    private static SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private AppWidgetHostBridge() {}
}

package com.lucky.mixflipouter;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Collection;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Optional SystemUI-scoped adapter that delegates clicks to real active QSTile instances. */
final class SystemUiTileHook {
    private static final String HOST =
            "com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter";
    private static final String TILE_IMPL = "com.android.systemui.qs.tileimpl.QSTileImpl";
    private static final long HEARTBEAT_MS = 30_000;
    private static final long PUBLISH_THROTTLE_MS = 500;
    private static volatile Object host;
    private static volatile Context context;
    private static Handler main;
    private static ContentObserver observer;
    private static long lastPublish;
    private static boolean heartbeatStarted;

    static void install(ClassLoader loader) {
        try {
            Class<?> hostClass = XposedHelpers.findClass(HOST, loader);
            XposedBridge.hookAllConstructors(hostClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam hook) {
                    attach(hook.thisObject);
                }
            });
            XposedBridge.hookAllMethods(hostClass, "getTiles", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam hook) {
                    host = hook.thisObject;
                    schedulePublish(100);
                }
            });
            Class<?> tileClass = XposedHelpers.findClass(TILE_IMPL, loader);
            XposedBridge.hookAllMethods(tileClass, "refreshState", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam hook) {
                    schedulePublish(250);
                }
            });
            XposedBridge.log("MixFlipCustom: SystemUI QS adapter hooks installed");
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: SystemUI QS adapter failed: " + error);
        }
    }

    private static synchronized void attach(Object newHost) {
        host = newHost;
        Context newContext = (Context) XposedHelpers.getObjectField(newHost, "context");
        if (newContext == null) return;
        context = newContext.getApplicationContext();
        if (main == null) main = new Handler(Looper.getMainLooper());
        if (observer == null) {
            observer = new ContentObserver(main) {
                @Override
                public void onChange(boolean selfChange) {
                    processRequest();
                }
            };
            context.getContentResolver().registerContentObserver(
                    Contract.QS_URI, false, observer);
        }
        publish(true);
        if (!heartbeatStarted) {
            heartbeatStarted = true;
            main.postDelayed(SystemUiTileHook::heartbeat, HEARTBEAT_MS);
        }
    }

    private static void heartbeat() {
        publish(true);
        Handler handler = main;
        if (handler != null) handler.postDelayed(SystemUiTileHook::heartbeat, HEARTBEAT_MS);
    }

    private static void schedulePublish(long delay) {
        Handler handler = main;
        if (handler != null) handler.postDelayed(() -> publish(false), delay);
    }

    private static void publish(boolean force) {
        Object currentHost = host;
        Context currentContext = context;
        if (currentHost == null || currentContext == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastPublish < PUBLISH_THROTTLE_MS) return;
        try {
            Object value = XposedHelpers.callMethod(currentHost, "getTiles");
            if (!(value instanceof Collection)) return;
            JSONArray tiles = new JSONArray();
            for (Object tile : (Collection<?>) value) {
                JSONObject item = describe(tile);
                if (item != null) tiles.put(item);
            }
            Bundle extras = new Bundle();
            extras.putString("tiles_json", tiles.toString());
            extras.putString("systemui_version", appVersion(currentContext));
            currentContext.getContentResolver().call(
                    Contract.PROVIDER_URI, "publish_qs_tiles", null, extras);
            lastPublish = now;
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: QS snapshot publish failed: " + error);
        }
    }

    private static JSONObject describe(Object tile) {
        try {
            String spec = String.valueOf(XposedHelpers.callMethod(tile, "getTileSpec"));
            if (spec.isEmpty() || "null".equals(spec)) return null;
            Object stateObject = XposedHelpers.callMethod(tile, "getState");
            int state = stateObject == null ? 0 : XposedHelpers.getIntField(stateObject, "state");
            Object labelValue = XposedHelpers.callMethod(tile, "getTileLabel");
            boolean available = Boolean.TRUE.equals(XposedHelpers.callMethod(tile, "isAvailable"))
                    && state != 0;
            return new JSONObject()
                    .put("spec", spec)
                    .put("label", labelValue == null ? spec : labelValue.toString())
                    .put("state", state)
                    .put("available", available)
                    .put("custom", spec.startsWith("custom("))
                    .put("implementation", tile.getClass().getName());
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: cannot describe QS tile: " + error);
            return null;
        }
    }

    private static void processRequest() {
        Context currentContext = context;
        if (currentContext == null) return;
        Bundle request;
        try {
            request = currentContext.getContentResolver().call(
                    Contract.PROVIDER_URI, "take_qs_request", null, null);
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: cannot take QS request: " + error);
            return;
        }
        if (request == null || !request.getBoolean("has_request")) return;
        String id = request.getString("request_id", "");
        String spec = request.getString("spec", "");
        Bundle result = new Bundle();
        result.putString("request_id", id);
        try {
            Object tile = findTile(spec);
            if (tile == null) throw new IllegalStateException("磁贴不在当前控制中心");
            Object state = XposedHelpers.callMethod(tile, "getState");
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(tile, "isAvailable"))
                    || (state != null && XposedHelpers.getIntField(state, "state") == 0)) {
                throw new IllegalStateException("磁贴当前不可用");
            }
            invokeClick(tile);
            result.putBoolean("ok", true);
            result.putString("message", "已由 SystemUI 执行 " + spec);
            Handler handler = main;
            if (handler != null) handler.postDelayed(() -> publish(true), 700);
        } catch (Throwable error) {
            result.putBoolean("ok", false);
            result.putString("message", error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
        }
        try {
            currentContext.getContentResolver().call(
                    Contract.PROVIDER_URI, "complete_qs_request", null, result);
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: cannot complete QS request: " + error);
        }
    }

    private static Object findTile(String requestedSpec) {
        Object currentHost = host;
        if (currentHost == null) return null;
        Object value = XposedHelpers.callMethod(currentHost, "getTiles");
        if (!(value instanceof Collection)) return null;
        for (Object tile : (Collection<?>) value) {
            Object spec = XposedHelpers.callMethod(tile, "getTileSpec");
            if (requestedSpec.equals(spec)) return tile;
        }
        return null;
    }

    private static void invokeClick(Object tile) throws Exception {
        for (Method method : tile.getClass().getMethods()) {
            if ("click".equals(method.getName()) && method.getParameterTypes().length == 1) {
                method.invoke(tile, new Object[]{null});
                return;
            }
        }
        throw new NoSuchMethodException("QSTile.click(Expandable)");
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(
                    Contract.SYSTEM_UI_PACKAGE, 0).versionName;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private SystemUiTileHook() {}
}

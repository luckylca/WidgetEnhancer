package com.lucky.mixflipouter;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;


/** Process-local, best-effort entry point for module diagnostics. */
public final class ModuleLogger {
    private static final String TAG = "WidgetEnhancer";
    private static final String MODULE_PACKAGE = "com.lucky.mixflipouter";
    private static final String SESSION = UUID.randomUUID().toString();
    private static final AtomicBoolean CONTEXT_READY = new AtomicBoolean();
    private static final AtomicBoolean INITIALIZATION_SCHEDULED = new AtomicBoolean();
    private static final ConcurrentHashMap<String, ErrorThrottle> ERROR_THROTTLE =
            new ConcurrentHashMap<>();
    private static volatile String packageName = MODULE_PACKAGE;
    private static volatile String processName = MODULE_PACKAGE;
    private static volatile String moduleVersion = BuildConfig.VERSION_NAME + " ("
            + BuildConfig.VERSION_CODE + ")";
    private static volatile String targetVersion = "unknown";
    private static volatile String hyperOsBuild;
    private static volatile Context appContext;
    private static volatile boolean startLogged;

    private ModuleLogger() {}

    /** Called from the Xposed load entry before application attach. */
    public static void beginProcess(String pkg, String process) {
        packageName = safe(pkg, "unknown");
        processName = safe(process, packageName);
    }

    /** Called after Application.attach, or from the module's Application.onCreate. */
    public static void initialize(Context context, String pkg, String process) {
        if (context == null) return;
        appContext = context.getApplicationContext() == null ? context
                : context.getApplicationContext();
        packageName = safe(pkg, context.getPackageName());
        processName = safe(process, packageName);
        if (!INITIALIZATION_SCHEDULED.compareAndSet(false, true)) return;
        final Context deviceContext;
        try { deviceContext = context.createDeviceProtectedStorageContext(); }
        catch (Throwable error) {
            INITIALIZATION_SCHEDULED.set(false);
            fallback("Logger storage context initialization failed", error);
            return;
        }
        Thread initializer = new Thread(() -> {
            try {
                moduleVersion = version(deviceContext, MODULE_PACKAGE);
                targetVersion = version(deviceContext, packageName);
                LogClient.initialize(deviceContext);
                CONTEXT_READY.set(true);
                if (!startLogged) {
                    startLogged = true;
                    emit("INFO", "process_start", "Target process loaded the module", null,
                            null, null, null, null);
                }
                emit("INFO", "process_context_ready", "Logger transport initialized", null,
                        null, null, null, null);
            } catch (Throwable error) {
                INITIALIZATION_SCHEDULED.set(false);
                fallback("Logger initialization failed", error);
            }
        }, "widgetenhancer-log-init");
        initializer.setDaemon(true);
        initializer.start();
    }

    public static void debug(String message) { emit("DEBUG", "message", message, null, null, null, null, null); }
    public static void info(String message) { emit("INFO", "message", message, null, null, null, null, null); }
    public static void warn(String message) { emit("WARN", "message", message, null, null, null, null, null); }
    public static void error(String message) { emit("ERROR", "message", message, null, null, null, null, null); }

    public static void logException(String message, Throwable error) {
        emit("ERROR", "exception", message, error, null, null, null, null);
    }

    /** Use on layout callbacks where a persistent failure can repeat at UI frequency. */
    public static void logExceptionThrottled(String message, Throwable error) {
        String key = safe(message, "exception") + ":"
                + (error == null ? "unknown" : error.getClass().getName());
        long now = System.currentTimeMillis();
        ErrorThrottle state = ERROR_THROTTLE.computeIfAbsent(key, ignored -> new ErrorThrottle());
        synchronized (state) {
            if (state.lastWrittenAt > 0 && now - state.lastWrittenAt < 5000L) {
                state.suppressed++;
                return;
            }
            String detail = state.suppressed == 0 ? message
                    : message + " (" + state.suppressed + " similar exceptions suppressed)";
            state.suppressed = 0;
            state.lastWrittenAt = now;
            emit("ERROR", "exception", detail, error, null, null, null, null);
        }
    }

    public static void warnThrottled(String key, String message, long intervalMs) {
        String throttleKey = "warning:" + safe(key, "warning");
        long now = System.currentTimeMillis();
        ErrorThrottle state = ERROR_THROTTLE.computeIfAbsent(throttleKey,
                ignored -> new ErrorThrottle());
        synchronized (state) {
            if (state.lastWrittenAt > 0 && now - state.lastWrittenAt < intervalMs) {
                state.suppressed++;
                return;
            }
            String detail = state.suppressed == 0 ? message
                    : message + " (" + state.suppressed + " similar warnings suppressed)";
            state.suppressed = 0;
            state.lastWrittenAt = now;
            emit("WARN", "warning", detail, null, null, null, null, null);
        }
    }

    /** Waits briefly for this process's asynchronous queue to reach the provider. */
    public static boolean flush(long timeoutMs) {
        try { return LogClient.flush(timeoutMs); }
        catch (Throwable error) {
            fallback("Logger flush failed", error);
            return false;
        }
    }

    public static void logHookStart(String feature, String targetClass, String method,
                                    String signature) {
        emit("INFO", "hook_start", "Installing " + safe(feature, "feature"), null,
                feature, targetClass, method, signature);
    }

    public static void logHookSuccess(String feature, String targetClass, String method,
                                      String signature) {
        emit("INFO", "hook_success", "Hook registered", null,
                feature, targetClass, method, signature);
    }

    public static void logHookFailure(String feature, String targetClass, String method,
                                      String signature, Throwable error) {
        emit("ERROR", "hook_failure", "Hook registration failed", error,
                feature, targetClass, method, signature);
    }

    public static void logHookSkipped(String feature, String targetClass, String method,
                                      String reason) {
        emit("INFO", "hook_skipped", reason, null,
                feature, targetClass, method, null);
    }

    public static void logFeatureState(String feature, String state, String reason) {
        emit("INFO", "feature_state", safe(feature, "feature") + ": " + safe(state, "unknown")
                        + (reason == null || reason.isEmpty() ? "" : " — " + reason),
                null, feature, null, null, null);
    }

    private static void emit(String level, String event, String message, Throwable error,
                             String feature, String targetClass, String method,
                             String signature) {
        try {
            JSONObject row = new JSONObject();
            long now = System.currentTimeMillis();
            row.put("timestamp", now);
            row.put("time", java.time.Instant.ofEpochMilli(now).toString());
            row.put("level", level);
            row.put("eventType", event);
            row.put("eventId", UUID.randomUUID().toString());
            row.put("message", safe(message, ""));
            row.put("packageName", packageName);
            row.put("processName", processName);
            row.put("pid", Process.myPid());
            row.put("threadId", Thread.currentThread().getId());
            row.put("threadName", Thread.currentThread().getName());
            row.put("moduleVersion", moduleVersion);
            row.put("targetAppVersion", targetVersion);
            row.put("sessionId", SESSION);
            row.put("androidRelease", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
            row.put("sdk", Build.VERSION.SDK_INT);
            row.put("hyperOsBuild", hyperOsBuild());
            if (feature != null) row.put("feature", feature);
            if (targetClass != null) row.put("hookClass", targetClass);
            if (method != null) row.put("hookMethod", method);
            if (signature != null) row.put("signature", signature);
            if (error != null) row.put("stackTrace", stackTrace(error));
            LogClient.enqueue(row);
            if (error != null && ("ERROR".equals(level) || "WARN".equals(level))) {
                // Keep XposedBridge as a last-resort path if the local transport is down.
                xposedFallback("WidgetEnhancer " + event + ": " + message + "\n"
                        + row.optString("stackTrace", error.toString()));
            }
        } catch (Throwable loggerFailure) {
            fallback("Logger could not record " + event, loggerFailure);
        }
    }

    private static String version(Context context, String pkg) {
        if (MODULE_PACKAGE.equals(pkg)) {
            return BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            return (info.versionName == null ? "" : info.versionName) + " ("
                    + info.getLongVersionCode() + ")";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    static Context context() { return appContext; }

    static String hyperOsBuild() {
        String value = hyperOsBuild;
        if (value != null) return value;
        synchronized (ModuleLogger.class) {
            value = hyperOsBuild;
            if (value != null) return value;
            try {
                Class<?> properties = Class.forName("android.os.SystemProperties");
                java.lang.reflect.Method get = properties.getDeclaredMethod(
                        "get", String.class, String.class);
                get.setAccessible(true);
                value = (String) get.invoke(null, "ro.mi.os.version.name", "");
            } catch (Throwable ignored) {
                value = "";
            }
            if (value == null || value.isEmpty()) value = Build.DISPLAY;
            hyperOsBuild = value == null ? "unknown" : value;
            return hyperOsBuild;
        }
    }

    private static String stackTrace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private static final class ErrorThrottle {
        long lastWrittenAt;
        int suppressed;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static void fallback(String message, Throwable error) {
        try {
            Log.e(TAG, message, error);
            if (!MODULE_PACKAGE.equals(packageName)) {
                xposedFallback("WidgetEnhancer: " + message + ": " + error);
            }
        } catch (Throwable ignored) {
            // Logging must never affect hook or app behavior.
        }
    }

    static void xposedFallback(String message) {
        try {
            Class<?> bridge = Class.forName("de.robv.android.xposed.XposedBridge");
            bridge.getMethod("log", String.class).invoke(null, message);
        } catch (Throwable ignored) { }
    }
}

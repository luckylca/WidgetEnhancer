package com.lucky.mixflipouter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;

/** Receives scope-active beacons from system_server and the supported IME processes. */
public final class ImeScopeStatusProvider extends ContentProvider {
    static final Uri URI = Uri.parse("content://com.lucky.mixflipouter.ime-scope");
    private static final String PREFS = "ime_scope_status";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("report_scope".equals(method)) {
            Bundle result = new Bundle();
            result.putBoolean("accepted", reportScope(arg));
            return result;
        }
        if ("get_scope_status".equals(method)) {
            enforceOwnCaller();
            int bootCount = currentBootCount();
            android.content.SharedPreferences prefs = statusPreferences();
            Bundle result = new Bundle();
            result.putBoolean("bootCountKnown", bootCount >= 0);
            result.putBoolean("system", bootCount >= 0
                    && prefs.getInt("system_boot", -1) == bootCount);
            result.putBoolean("wechat", bootCount >= 0
                    && prefs.getInt("wechat_boot", -1) == bootCount);
            result.putBoolean("doubao", bootCount >= 0
                    && prefs.getInt("doubao_boot", -1) == bootCount);
            return result;
        }
        if ("set_replacement_status".equals(method)) {
            enforceOwnCaller();
            String target = extras != null && extras.containsKey("target")
                    ? extras.getString("target", "")
                    : extras != null && extras.getBoolean("enabled", false)
                    ? "com.tencent.wetype" : "";
            boolean saved;
            String error = "";
            try {
                saved = ImeReplacementStore.write(getContext(), target);
            } catch (Throwable failure) {
                saved = false;
                error = failure.getClass().getSimpleName() + ": "
                        + (failure.getMessage() == null ? "unable to persist setting"
                        : failure.getMessage());
                ModuleLogger.logException("Unable to persist IME replacement preference", failure);
            }
            if (saved) getContext().getContentResolver().notifyChange(URI, null);
            Bundle result = new Bundle();
            result.putBoolean("saved", saved);
            if (!saved) result.putString("error", error);
            return result;
        }
        if ("get_replacement_status".equals(method)) {
            enforceSystemOrOwnCaller();
            Bundle result = new Bundle();
            try {
                ImeReplacementStore.State state = ImeReplacementStore.read(getContext());
                result.putBoolean("available", true);
                result.putBoolean("configured", state.configured);
                result.putBoolean("enabled", state.enabled());
                result.putString("target", state.target);
                result.putString("source", state.source);
            } catch (Throwable failure) {
                result.putBoolean("available", false);
                result.putBoolean("configured", false);
                result.putBoolean("enabled", false);
                result.putString("target", "");
                result.putString("error", failure.getClass().getSimpleName() + ": "
                        + (failure.getMessage() == null ? "unable to read setting"
                        : failure.getMessage()));
                ModuleLogger.logExceptionThrottled(
                        "Unable to read durable IME replacement preference", failure);
            }
            return result;
        }
        return super.call(method, arg, extras);
    }

    private boolean reportScope(String scope) {
        int callerUid = Binder.getCallingUid();
        if ("system".equals(scope)) {
            if (callerUid != Process.SYSTEM_UID) throw new SecurityException("system only");
        } else if ("wechat".equals(scope) || "doubao".equals(scope)) {
            String[] packages = getContext().getPackageManager().getPackagesForUid(callerUid);
            String expected = "wechat".equals(scope)
                    ? "com.tencent.wetype" : "com.bytedance.android.doubaoime";
            boolean fromExpectedIme = false;
            if (packages != null) {
                for (String packageName : packages) {
                    if (expected.equals(packageName)) {
                        fromExpectedIme = true;
                        break;
                    }
                }
            }
            if (!fromExpectedIme) throw new SecurityException("IME package mismatch");
        } else {
            throw new SecurityException("unknown scope");
        }

        int bootCount = currentBootCount();
        if (bootCount < 0) return false;
        String key = "system".equals(scope) ? "system_boot" : scope + "_boot";
        statusPreferences().edit()
                .putInt(key, bootCount).commit();
        return true;
    }

    static void reportLoaded(android.content.Context context, String scope) {
        if (context == null) return;
        android.content.Context appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        Thread reporter = new Thread(() -> {
            Throwable lastFailure = null;
            try {
                long[] retryDelaysMs = {0L, 500L, 1500L, 4000L};
                for (long delay : retryDelaysMs) {
                    if (delay > 0) Thread.sleep(delay);
                    try {
                        Bundle result = appContext.getContentResolver().call(
                                URI, "report_scope", scope, null);
                        if (result != null && result.getBoolean("accepted", false)) {
                            ModuleLogger.logFeatureState(scope + " IME scope load beacon",
                                    "ACCEPTED", "本次启动进程加载回报已接收");
                            return;
                        }
                        lastFailure = new IllegalStateException(
                                "Load beacon was not accepted for " + scope);
                    } catch (Throwable error) {
                        lastFailure = error;
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                lastFailure = error;
            }
            ModuleLogger.logException("IME load beacon failed for " + scope, lastFailure);
        }, "widgetenhancer-ime-load-beacon");
        reporter.setDaemon(true);
        reporter.start();
    }

    private android.content.SharedPreferences statusPreferences() {
        return getContext().createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, 0);
    }

    private int currentBootCount() {
        return Settings.Global.getInt(getContext().getContentResolver(),
                Settings.Global.BOOT_COUNT, -1);
    }

    private void enforceOwnCaller() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("module app only");
        }
    }

    private void enforceSystemOrOwnCaller() {
        int callerUid = Binder.getCallingUid();
        if (callerUid != Process.SYSTEM_UID && callerUid != Process.myUid()) {
            throw new SecurityException("system or module app only");
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}

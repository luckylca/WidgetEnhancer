package com.lucky.mixflipouter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;

/** Receives scope-active beacons from the system server and WeChat IME process. */
public final class ImeScopeStatusProvider extends ContentProvider {
    static final Uri URI = Uri.parse("content://com.lucky.mixflipouter.ime-scope");
    private static final String PREFS = "ime_scope_status";
    private static final String REPLACEMENT_ENABLED = "replace_with_wechat";

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
            result.putBoolean("system", bootCount >= 0
                    && prefs.getInt("system_boot", -1) == bootCount);
            result.putBoolean("wechat", bootCount >= 0
                    && prefs.getInt("wechat_boot", -1) == bootCount);
            return result;
        }
        if ("set_replacement_status".equals(method)) {
            enforceOwnCaller();
            boolean enabled = extras != null && extras.getBoolean("enabled", false);
            boolean saved = statusPreferences().edit()
                    .putBoolean(REPLACEMENT_ENABLED, enabled).commit();
            if (saved) getContext().getContentResolver().notifyChange(URI, null);
            Bundle result = new Bundle();
            result.putBoolean("saved", saved);
            return result;
        }
        if ("get_replacement_status".equals(method)) {
            enforceSystemOrOwnCaller();
            android.content.SharedPreferences prefs = statusPreferences();
            Bundle result = new Bundle();
            result.putBoolean("configured", prefs.contains(REPLACEMENT_ENABLED));
            result.putBoolean("enabled", prefs.getBoolean(REPLACEMENT_ENABLED, false));
            return result;
        }
        return super.call(method, arg, extras);
    }

    private boolean reportScope(String scope) {
        int callerUid = Binder.getCallingUid();
        if ("system".equals(scope)) {
            if (callerUid != Process.SYSTEM_UID) throw new SecurityException("system only");
        } else if ("wechat".equals(scope)) {
            String[] packages = getContext().getPackageManager().getPackagesForUid(callerUid);
            boolean fromWeChat = false;
            if (packages != null) {
                for (String packageName : packages) {
                    if ("com.tencent.wetype".equals(packageName)) {
                        fromWeChat = true;
                        break;
                    }
                }
            }
            if (!fromWeChat) throw new SecurityException("WeChat IME only");
        } else {
            throw new SecurityException("unknown scope");
        }

        int bootCount = currentBootCount();
        if (bootCount < 0) return false;
        String key = "system".equals(scope) ? "system_boot" : "wechat_boot";
        statusPreferences().edit()
                .putInt(key, bootCount).commit();
        return true;
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

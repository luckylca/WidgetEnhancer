package com.lucky.mixflipouter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;

/** Receives the system_server scope-active beacon. */
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
            reportScope(arg);
            return Bundle.EMPTY;
        }
        if ("get_scope_status".equals(method)) {
            enforceOwnCaller();
            int bootCount = currentBootCount();
            android.content.SharedPreferences prefs = statusPreferences();
            Bundle result = new Bundle();
            result.putBoolean("system", bootCount >= 0
                    && prefs.getInt("system_boot", -1) == bootCount);
            return result;
        }
        return super.call(method, arg, extras);
    }

    private void reportScope(String scope) {
        int callerUid = Binder.getCallingUid();
        if ("system".equals(scope)) {
            if (callerUid != Process.SYSTEM_UID) throw new SecurityException("system only");
        } else {
            throw new SecurityException("unknown scope");
        }

        int bootCount = currentBootCount();
        if (bootCount < 0) return;
        statusPreferences().edit()
                .putInt("system_boot", bootCount).apply();
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

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}

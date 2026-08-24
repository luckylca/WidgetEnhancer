package com.lucky.mixflipouter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

public final class ConfigProvider extends ContentProvider {
    private WidgetRepository repository;

    @Override
    public boolean onCreate() {
        repository = new WidgetRepository(getContext());
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("get_health".equals(method)) return getHealth();
        enforceAllowedCaller();
        if ("get_config".equals(method) || "get_widget".equals(method)) {
            return getWidget(arg == null ? Contract.DEFAULT_WIDGET_ID : arg);
        }
        if ("list_widgets".equals(method)) return listWidgets();
        if ("report_hook".equals(method)) {
            saveHookReport(extras);
            return Bundle.EMPTY;
        }
        return super.call(method, arg, extras);
    }

    private Bundle getWidget(String widgetId) {
        WidgetConfig config = repository.get(widgetId);
        return config == null ? null : config.toBundle(repository.revision());
    }

    private Bundle listWidgets() {
        long revision = repository.revision();
        ArrayList<Bundle> widgets = new ArrayList<>();
        for (WidgetConfig config : repository.list()) widgets.add(config.toBundle(revision));
        Bundle out = new Bundle();
        out.putInt("schema_version", WidgetRepository.SCHEMA_VERSION);
        out.putLong("revision", revision);
        out.putParcelableArrayList("widgets", widgets);
        return out;
    }

    private void saveHookReport(Bundle extras) {
        if (extras == null) return;
        String stage = extras.getString("stage", "unknown");
        prefs().edit()
                .putBoolean("hook_" + stage + "_ok", extras.getBoolean("ok"))
                .putString("hook_" + stage + "_message", extras.getString("message", ""))
                .putLong("hook_" + stage + "_time", System.currentTimeMillis())
                .apply();
    }

    private Bundle getHealth() {
        SharedPreferences p = prefs();
        Bundle out = new Bundle();
        for (String stage : new String[]{"catalogue", "runtime"}) {
            out.putBoolean(stage + "_ok", p.getBoolean("hook_" + stage + "_ok", false));
            out.putString(stage + "_message", p.getString("hook_" + stage + "_message", "尚未收到上报"));
            out.putLong(stage + "_time", p.getLong("hook_" + stage + "_time", 0));
        }
        return out;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceAllowedCaller();
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        String kind = lastSegment(uri);
        String widgetId = widgetId(uri);
        WidgetConfig config = repository.get(widgetId);
        File media = repository.mediaFile(widgetId);
        if ("media".equals(kind) && media.isFile()) return readOnly(media);
        if ("preview".equals(kind)) {
            File preview = PreviewRenderer.ensure(
                    getContext(), config, media, repository.revision());
            if (preview.isFile()) return readOnly(preview);
        }
        throw new FileNotFoundException("No file for " + uri);
    }

    @Override
    public String getType(Uri uri) {
        enforceAllowedCaller();
        WidgetConfig config = repository.get(widgetId(uri));
        if (config == null) return "application/octet-stream";
        if ("preview".equals(lastSegment(uri))) return "image/png";
        return config.mimeType;
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(Contract.PREFS, 0);
    }

    private static String lastSegment(Uri uri) {
        return uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
    }

    private static String widgetId(Uri uri) {
        List<String> parts = uri.getPathSegments();
        return parts.size() >= 2 && "widgets".equals(parts.get(0))
                ? parts.get(1) : Contract.DEFAULT_WIDGET_ID;
    }

    private static ParcelFileDescriptor readOnly(File file) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private void enforceAllowedCaller() {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            for (String name : packages) {
                if (Contract.TARGET_PACKAGE.equals(name)) return;
            }
        }
        throw new SecurityException("Caller is not FlipHome");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}

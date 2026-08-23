package com.lucky.mixflipouter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

public final class ConfigProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("get_health".equals(method)) return getHealth();
        enforceAllowedCaller();
        if ("get_config".equals(method) || "get_widget".equals(method)) {
            return getWidget(arg == null ? Contract.DEFAULT_WIDGET_ID : arg);
        }
        if ("report_hook".equals(method)) {
            saveHookReport(extras);
            return Bundle.EMPTY;
        }
        return super.call(method, arg, extras);
    }

    private Bundle getWidget(String widgetId) {
        SharedPreferences p = prefs();
        Bundle out = new Bundle();
        out.putInt("schema_version", 1);
        out.putString("id", Contract.DEFAULT_WIDGET_ID);
        out.putString("name", p.getString("name", "我的外屏"));
        out.putBoolean("enabled", p.getBoolean("enabled", true));
        out.putString("media_type", p.getString("media_type", "none"));
        out.putString("mime_type", p.getString("mime_type", "application/octet-stream"));
        out.putBoolean("loop", p.getBoolean("loop", true));
        out.putBoolean("mute", p.getBoolean("mute", true));
        for (int i = 0; i < Contract.BUTTON_COUNT; i++) {
            out.putString("button_" + i + "_label", p.getString("button_" + i + "_label", ""));
            out.putString("button_" + i + "_type", p.getString("button_" + i + "_type", "package"));
            out.putString("button_" + i + "_value", p.getString("button_" + i + "_value", ""));
        }
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
        File media = new File(getContext().getFilesDir(), "selected_media");
        if ("media".equals(kind) && media.isFile()) return readOnly(media);
        if ("preview".equals(kind)) {
            if (media.isFile()) return readOnly(media);
            File preview = ensurePlaceholderPreview();
            if (preview.isFile()) return readOnly(preview);
        }
        throw new FileNotFoundException("No file for " + uri);
    }

    @Override
    public String getType(Uri uri) {
        enforceAllowedCaller();
        if ("preview".equals(lastSegment(uri))) {
            String mediaType = prefs().getString("media_type", "none");
            if ("none".equals(mediaType)) return "image/png";
        }
        return prefs().getString("mime_type", "application/octet-stream");
    }

    private File ensurePlaceholderPreview() {
        File file = new File(getContext().getCacheDir(), "widget_preview.png");
        if (file.isFile()) return file;
        Bitmap bitmap = Bitmap.createBitmap(604, 696, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(18, 18, 22));
        Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
        accent.setColor(Color.rgb(255, 105, 0));
        canvas.drawCircle(302, 265, 96, accent);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(48);
        text.setFakeBoldText(true);
        canvas.drawText("自定义外屏", 302, 450, text);
        text.setTextSize(30);
        text.setFakeBoldText(false);
        text.setColor(Color.LTGRAY);
        canvas.drawText("图片 · 视频 · 快捷按键", 302, 505, text);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, out);
        } catch (Throwable ignored) {
            file.delete();
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(Contract.PREFS, 0);
    }

    private static String lastSegment(Uri uri) {
        return uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
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

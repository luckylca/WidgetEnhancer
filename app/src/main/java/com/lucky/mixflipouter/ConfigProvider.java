package com.lucky.mixflipouter;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
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
    private CameraManager cameraManager;
    private String torchCameraId;
    private volatile boolean torchOn;
    private volatile boolean torchKnown;

    @Override
    public boolean onCreate() {
        repository = new WidgetRepository(getContext());
        initializeTorch();
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
        if ("get_system_state".equals(method)) return getSystemState();
        if ("get_playback_state".equals(method)) return PlaybackStateStore.provider().snapshot();
        if ("grant_media".equals(method)) {
            String packageName = extras == null ? null : extras.getString("package");
            if (!Contract.GALLERY_PACKAGE.equals(packageName)) {
                throw new SecurityException("Unsupported media viewer");
            }
            String widgetId = arg == null ? Contract.DEFAULT_WIDGET_ID : arg;
            if (repository.get(widgetId) == null) return Bundle.EMPTY;
            getContext().grantUriPermission(packageName, Contract.mediaUri(widgetId),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return Bundle.EMPTY;
        }
        if ("execute_action".equals(method)) return executeAction(arg);
        if ("set_safe_mode".equals(method)) {
            enforceModuleCaller();
            repository.setSafeMode(extras != null && extras.getBoolean("enabled"));
            return getSystemState();
        }
        if ("report_hook".equals(method)) {
            saveHookReport(extras);
            return Bundle.EMPTY;
        }
        return super.call(method, arg, extras);
    }

    private Bundle getWidget(String widgetId) {
        if (repository.isSafeMode()) return null;
        WidgetConfig config = repository.get(widgetId);
        return config == null ? null : config.toBundle(repository.revision());
    }

    private Bundle listWidgets() {
        long revision = repository.revision();
        ArrayList<Bundle> widgets = new ArrayList<>();
        boolean safeMode = repository.isSafeMode();
        if (!safeMode) {
            for (WidgetConfig config : repository.list()) widgets.add(config.toBundle(revision));
        }
        Bundle out = new Bundle();
        out.putInt("schema_version", WidgetRepository.SCHEMA_VERSION);
        out.putLong("revision", revision);
        out.putBoolean("safe_mode", safeMode);
        out.putParcelableArrayList("widgets", widgets);
        return out;
    }

    private Bundle getSystemState() {
        Bundle out = new Bundle();
        out.putBoolean("safe_mode", repository.isSafeMode());
        out.putLong("revision", repository.revision());
        return out;
    }

    private void initializeTorch() {
        try {
            cameraManager = (CameraManager) getContext().getSystemService(android.content.Context.CAMERA_SERVICE);
            if (cameraManager == null) return;
            torchCameraId = findBackFlashCamera();
            cameraManager.registerTorchCallback(getContext().getMainExecutor(),
                    new CameraManager.TorchCallback() {
                        @Override
                        public void onTorchModeChanged(String cameraId, boolean enabled) {
                            if (cameraId.equals(torchCameraId)) {
                                torchOn = enabled;
                                torchKnown = true;
                            }
                        }
                    });
        } catch (Throwable ignored) {
            cameraManager = null;
            torchCameraId = null;
        }
    }

    private String findBackFlashCamera() throws Exception {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
            Boolean flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (Boolean.TRUE.equals(flash)
                    && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return null;
    }

    private Bundle executeAction(String action) {
        if (ActionSpec.isMediaControl(action)) return PlaybackStateStore.provider().execute(action);
        Bundle result = new Bundle();
        if (!ActionSpec.isFlashlight(action)) {
            result.putBoolean("ok", false);
            result.putString("message", "Provider 不支持该动作");
            return result;
        }
        if (getContext().checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            result.putBoolean("ok", false);
            result.putString("message", "请先在配置 App 中授予手电筒权限");
            return result;
        }
        if (cameraManager == null || torchCameraId == null) {
            initializeTorch();
        }
        if (cameraManager == null || torchCameraId == null) {
            result.putBoolean("ok", false);
            result.putString("message", "未找到可用的后置闪光灯");
            return result;
        }
        boolean enabled = ActionSpec.FLASHLIGHT_ON.equals(action)
                || (ActionSpec.FLASHLIGHT_TOGGLE.equals(action) && (!torchKnown || !torchOn));
        if (ActionSpec.FLASHLIGHT_OFF.equals(action)) enabled = false;
        try {
            boolean previousEnabled = torchKnown && torchOn;
            cameraManager.setTorchMode(torchCameraId, enabled);
            torchOn = enabled;
            torchKnown = true;
            result.putBoolean("ok", true);
            result.putBoolean("enabled", enabled);
            result.putBoolean("previous_enabled", previousEnabled);
        } catch (Throwable error) {
            result.putBoolean("ok", false);
            result.putString("message", error.getMessage() == null
                    ? "手电筒当前不可用" : error.getMessage());
        }
        return result;
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
        for (String stage : new String[]{"compatibility", "catalogue", "runtime", "live_refresh"}) {
            out.putBoolean(stage + "_ok", p.getBoolean("hook_" + stage + "_ok", false));
            out.putString(stage + "_message", p.getString("hook_" + stage + "_message", "尚未收到上报"));
            out.putLong(stage + "_time", p.getLong("hook_" + stage + "_time", 0));
        }
        return out;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceMediaReader(uri);
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
        enforceMediaReader(uri);
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

    private void enforceMediaReader(Uri uri) {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return;
        if (getContext().checkUriPermission(uri, Binder.getCallingPid(), uid,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        enforceAllowedCaller();
    }

    private void enforceModuleCaller() {
        if (Binder.getCallingUid() != android.os.Process.myUid()) {
            throw new SecurityException("Only the module app can change system state");
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}

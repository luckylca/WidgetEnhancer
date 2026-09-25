package com.lucky.mixflipouter;

import android.app.Activity;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Bridges the MIUI Personal Assistant picker selection back to the editor. */
final class SystemWidgetPickerHook {
    private static final String PICKER_ACTIVITY =
            "com.miui.personalassistant.picker.business.home.pages.PickerHomeActivity";
    private static final String ADD_ACTION_CONTROLLER =
            "com.miui.personalassistant.picker.business.detail.utils.PickerDetailActionController";
    private static final String ITEM_INFO =
            "com.miui.personalassistant.widget.entity.ItemInfo";
    private static final String APP_WIDGET_ITEM_INFO =
            "com.miui.personalassistant.widget.iteminfo.AppWidgetItemInfo";
    private static final String MAML_ITEM_INFO =
            "com.miui.personalassistant.widget.iteminfo.MaMlItemInfo";
    private static volatile String pendingRequest;
    private static volatile boolean selectionInProgress;

    static void installAssistantPicker(ClassLoader loader) {
        hookPickerLifecycle(loader);
        hookSelectionAction(loader);
        android.util.Log.i("MixFlipCustom", "MIUI Assistant widget picker hooks installed");
    }

    private static void hookPickerLifecycle(ClassLoader loader) {
        try {
            Class<?> picker = XposedHelpers.findClass(PICKER_ACTIVITY, loader);
            XposedHelpers.findAndHookMethod(picker, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            captureRequest((Activity) hook.thisObject,
                                    ((Activity) hook.thisObject).getIntent());
                        }
                    });
            XposedHelpers.findAndHookMethod(picker, "onNewIntent", Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            Activity activity = (Activity) hook.thisObject;
                            Intent intent = (Intent) hook.args[0];
                            if (intent != null) activity.setIntent(intent);
                            captureRequest(activity, intent);
                        }
                    });
            XposedHelpers.findAndHookMethod(picker, "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            if (!selectionInProgress) pendingRequest = null;
                        }
                    });
            android.util.Log.i("MixFlipCustom", "Assistant picker lifecycle hook installed");
        } catch (Throwable error) {
            android.util.Log.e("MixFlipCustom", "Assistant picker lifecycle hook failed", error);
            XposedBridge.log("MixFlipCustom: Assistant picker lifecycle hook failed: " + error);
        }
    }

    private static void captureRequest(Activity activity, Intent intent) {
        if (activity == null || intent == null
                || !PICKER_ACTIVITY.equals(activity.getClass().getName())) return;
        String request = intent.getStringExtra(Contract.EXTRA_SYSTEM_WIDGET_PICK_REQUEST);
        if (request == null || request.isEmpty()) return;
        pendingRequest = request;
        intent.removeExtra(Contract.EXTRA_SYSTEM_WIDGET_PICK_REQUEST);
        activity.setIntent(intent);
        android.util.Log.i("MixFlipCustom", "received selection request in MIUI Assistant picker");
    }

    private static void hookSelectionAction(ClassLoader loader) {
        try {
            Class<?> controller = XposedHelpers.findClass(ADD_ACTION_CONTROLLER, loader);
            Class<?> itemInfo = XposedHelpers.findClass(ITEM_INFO, loader);
            XposedHelpers.findAndHookMethod(controller, "addWidgetOrMaMl", View.class,
                    itemInfo, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam hook) {
                            if (selectionInProgress) {
                                hook.setResult(false);
                                return;
                            }
                            String request = pendingRequest;
                            if (request == null) return;

                            View view = (View) hook.args[0];
                            Object item = hook.args[1];
                            android.util.Log.i("MixFlipCustom", "picker add action received: "
                                    + (item == null ? "null" : item.getClass().getName()));
                            if (item != null && MAML_ITEM_INFO.equals(item.getClass().getName())) {
                                beginMamlSelection(view, request, item);
                                hook.setResult(false);
                                return;
                            }
                            AppWidgetProviderInfo providerInfo = appWidgetInfo(item);
                            ComponentName provider = providerInfo == null
                                    ? null : providerInfo.provider;
                            if (provider == null && item != null) {
                                try {
                                    Object value = XposedHelpers.getObjectField(item, "provider");
                                    if (value instanceof ComponentName) {
                                        provider = (ComponentName) value;
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                            if (provider == null) {
                                Toast.makeText(view.getContext(),
                                        "此面板目前只能添加应用小部件", Toast.LENGTH_SHORT).show();
                                hook.setResult(false);
                                return;
                            }

                            int minWidth = providerInfo == null ? 0 : providerInfo.minWidth;
                            int minHeight = providerInfo == null ? 0 : providerInfo.minHeight;
                            boolean delivered = deliverSelection(
                                    view.getContext().getApplicationContext(), request,
                                    provider, minWidth, minHeight);
                            pendingRequest = null;
                            if (!delivered) {
                                Toast.makeText(view.getContext(),
                                        "无法把小部件添加到编辑器，请重新打开面板再试",
                                        Toast.LENGTH_LONG).show();
                                hook.setResult(false);
                                return;
                            }
                            Activity activity = findActivity(view.getContext());
                            if (activity != null) activity.finish();
                            android.util.Log.i("MixFlipCustom", "returned widget selection: "
                                    + provider.flattenToString());
                            hook.setResult(true);
                        }
                    });
            android.util.Log.i("MixFlipCustom", "Assistant picker add action hook installed");
        } catch (Throwable error) {
            android.util.Log.e("MixFlipCustom", "Assistant picker add action hook failed", error);
            XposedBridge.log("MixFlipCustom: Assistant picker selection hook failed: " + error);
        }
    }

    private static AppWidgetProviderInfo appWidgetInfo(Object item) {
        if (item == null) return null;
        try {
            if (!APP_WIDGET_ITEM_INFO.equals(item.getClass().getName())) return null;
            Object info = XposedHelpers.getObjectField(item, "providerInfo");
            return info instanceof AppWidgetProviderInfo ? (AppWidgetProviderInfo) info : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void beginMamlSelection(View view, String request, Object item) {
        Context context = view.getContext().getApplicationContext();
        Activity activity = findActivity(view.getContext());
        String productId = stringField(item, "productId");
        String displayName = stringField(item, "title");
        if (displayName == null || displayName.trim().isEmpty()) displayName = productId;
        String resPath = stringField(item, "resPath");
        int versionCode = intField(item, "versionCode");
        selectionInProgress = true;
        final String selectedProductId = productId;
        final String selectedResPath = resPath;
        final int selectedVersionCode = versionCode;
        final String selectedName = displayName == null || displayName.trim().isEmpty()
                ? "小米小部件" : displayName.trim();
        new Thread(() -> {
            boolean delivered = false;
            String errorMessage = "无法导入小米小部件";
            File archive = null;
            boolean temporaryArchive = false;
            try {
                MamlArchive source = resolveMamlArchive(context, request, selectedProductId,
                        selectedVersionCode, selectedResPath);
                archive = source.file;
                temporaryArchive = source.temporary;
                String cacheId = sha256(archive);
                Bundle extras = new Bundle();
                extras.putString("kind", "maml");
                extras.putString("maml_id", cacheId);
                extras.putString("maml_name", selectedName);
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                        archive, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    extras.putParcelable("maml_archive", descriptor);
                    Bundle result = context.getContentResolver().call(Contract.PROVIDER_URI,
                            "deliver_system_widget_selection", request, extras);
                    delivered = result != null && result.getBoolean("ok");
                    if (!delivered && result != null) {
                        errorMessage = result.getString("message", errorMessage);
                    }
                }
                if (!delivered) {
                    XposedBridge.log("MixFlipCustom: MAML selection rejected: " + errorMessage);
                }
            } catch (Throwable error) {
                errorMessage = error.getMessage() == null ? errorMessage : error.getMessage();
                android.util.Log.e("MixFlipCustom", "MAML selection transfer failed", error);
                XposedBridge.log("MixFlipCustom: MAML selection transfer failed: " + error);
            }
            if (temporaryArchive && archive != null) archive.delete();
            final boolean resultDelivered = delivered;
            final String resultMessage = errorMessage;
            Runnable finish = () -> {
                selectionInProgress = false;
                if (resultDelivered) {
                    pendingRequest = null;
                    android.util.Log.i("MixFlipCustom", "returned MAML widget selection: "
                            + selectedName);
                    if (activity != null && !activity.isFinishing()) activity.finish();
                } else {
                    pendingRequest = request;
                    if (activity != null && !activity.isFinishing()) {
                        Toast.makeText(activity, resultMessage, Toast.LENGTH_LONG).show();
                    }
                }
            };
            if (activity != null) activity.runOnUiThread(finish);
            else finish.run();
        }, "mixflip-maml-picker-result").start();
    }

    private static MamlArchive resolveMamlArchive(Context context, String request,
                                                   String productId, int versionCode,
                                                   String resPath) throws Exception {
        if (productId == null || productId.isEmpty()) {
            throw new IllegalStateException("小米小部件标识为空");
        }
        File external = context.getExternalFilesDir("maml");
        File archive = external == null ? null : new File(external, productId + ".zip");
        if (archive != null && archive.isFile() && archive.length() > 0
                && archive.length() <= MamlImporter.MAX_ZIP_BYTES
                && archive.getCanonicalFile().getParentFile().equals(
                        external.getCanonicalFile())) {
            return new MamlArchive(archive.getCanonicalFile(), false);
        }

        File resourcesRoot = new File(context.getFilesDir(), "maml/res/0").getCanonicalFile();
        File widgetPath = resPath == null || resPath.isEmpty() ? null : new File(resPath);
        if (widgetPath == null || !widgetPath.isDirectory()) {
            if (versionCode <= 0) {
                throw new IllegalStateException(
                        "小米小部件尚未下载完成，请等待系统下载完成后重试");
            }
            widgetPath = new File(new File(new File(resourcesRoot, productId),
                    Integer.toString(versionCode)), productId);
        } else {
            widgetPath = widgetPath.getCanonicalFile().getParentFile();
        }
        if (widgetPath == null || !widgetPath.isDirectory()) {
            throw new IllegalStateException(
                    "小米小部件尚未下载完成，请等待系统下载完成后重试");
        }
        File canonicalRoot = widgetPath.getCanonicalFile();
        String rootPath = resourcesRoot.getPath() + File.separator;
        if (!canonicalRoot.getPath().startsWith(rootPath)
                || !productId.equals(canonicalRoot.getName())) {
            throw new SecurityException("小米小部件资源目录无效");
        }

        String safeRequest = request == null ? "selection"
                : request.replaceAll("[^a-zA-Z0-9_-]", "");
        File generated = new File(context.getCacheDir(), "mixflip-maml-" + safeRequest + ".zip");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(generated, false))) {
            long[] uncompressedBytes = {0};
            zipDirectory(canonicalRoot, canonicalRoot, output, uncompressedBytes);
        } catch (Throwable error) {
            generated.delete();
            throw error;
        }
        if (!generated.isFile() || generated.length() <= 0
                || generated.length() > MamlImporter.MAX_ZIP_BYTES) {
            generated.delete();
            throw new IllegalArgumentException("小米小部件资源包过大");
        }
        android.util.Log.i("MixFlipCustom", "packed extracted MAML resources from "
                + canonicalRoot.getAbsolutePath());
        return new MamlArchive(generated, true);
    }

    private static void zipDirectory(File root, File current, ZipOutputStream output,
                                     long[] uncompressedBytes) throws Exception {
        File[] children = current.listFiles();
        if (children == null) throw new IllegalStateException("无法读取小米小部件资源");
        Arrays.sort(children, (first, second) -> first.getName().compareTo(second.getName()));
        for (File child : children) {
            File canonical = child.getCanonicalFile();
            if (!canonical.getPath().startsWith(root.getPath() + File.separator)) continue;
            String relative = root.toURI().relativize(canonical.toURI()).getPath();
            if (relative.isEmpty() || relative.startsWith("/")
                    || relative.contains("../") || "..".equals(relative)) continue;
            if (canonical.isDirectory()) {
                output.putNextEntry(new ZipEntry(relative.endsWith("/")
                        ? relative : relative + "/"));
                output.closeEntry();
                zipDirectory(root, canonical, output, uncompressedBytes);
                continue;
            }
            long size = canonical.length();
            uncompressedBytes[0] += size;
            if (uncompressedBytes[0] > MamlImporter.MAX_ZIP_BYTES) {
                throw new IllegalArgumentException("小米小部件资源包过大");
            }
            output.putNextEntry(new ZipEntry(relative));
            try (FileInputStream input = new FileInputStream(canonical)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            }
            output.closeEntry();
        }
    }

    private static int intField(Object item, String field) {
        try {
            Object value = XposedHelpers.getObjectField(item, field);
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static final class MamlArchive {
        final File file;
        final boolean temporary;

        MamlArchive(File file, boolean temporary) {
            this.file = file;
            this.temporary = temporary;
        }
    }

    private static String stringField(Object item, String field) {
        try {
            Object value = XposedHelpers.getObjectField(item, field);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) break;
            current = next;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private static boolean deliverSelection(Context context, String request,
                                            ComponentName provider, int minWidth, int minHeight) {
        int[] size = minWidth > 0 && minHeight > 0
                ? AppWidgetLayoutEngine.autoSize(minWidth, minHeight,
                        context.getResources().getDisplayMetrics().density)
                : new int[] {2, 2};
        Bundle extras = new Bundle();
        extras.putString(Contract.EXTRA_SYSTEM_WIDGET_PROVIDER, provider.flattenToString());
        extras.putInt(Contract.EXTRA_SYSTEM_WIDGET_COLS, size[0]);
        extras.putInt(Contract.EXTRA_SYSTEM_WIDGET_ROWS, size[1]);
        try {
            Bundle result = context.getContentResolver().call(Contract.PROVIDER_URI,
                    "deliver_system_widget_selection", request, extras);
            boolean delivered = result != null && result.getBoolean("ok");
            if (!delivered) {
                String message = result == null ? "empty provider response"
                        : result.getString("message", "request rejected");
                android.util.Log.e("MixFlipCustom", "system widget selection rejected: "
                        + message);
                XposedBridge.log("MixFlipCustom: system widget selection rejected: " + message);
            }
            return delivered;
        } catch (Throwable error) {
            android.util.Log.e("MixFlipCustom", "system widget selection delivery failed", error);
            XposedBridge.log("MixFlipCustom: system widget selection delivery failed: "
                    + error);
            return false;
        }
    }

    private SystemWidgetPickerHook() {}
}

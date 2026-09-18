package com.lucky.mixflipouter;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Routes each explicitly scoped package to its versioned integration adapter. */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String INFO_CLASS = "com.miui.fliphome.widget.FlipWidgetInfo";
    private static final String CONFIG_CLASS = "com.miui.fliphome.widget.model.FlipWatchDefaultConfig";
    private static final String MAML_COMPAT_CLASS = "com.miui.fliphome.widget.ui.maml.FlipMaMlWidgetCompat";
    private static final String VIEW_MODEL_CLASS = "com.miui.fliphome.settings.widget.WidgetViewModel";
    private static volatile String fallbackMamlPath;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (Contract.SYSTEM_UI_PACKAGE.equals(param.packageName)) {
            if (param.processName == null || Contract.SYSTEM_UI_PACKAGE.equals(param.processName)) {
                SystemUiTileHook.install(param.classLoader);
            }
            return;
        }
        if (Contract.NETEASE_PACKAGE.equals(param.packageName)) {
            if (param.processName == null || Contract.NETEASE_PACKAGE.equals(param.processName)) {
                NeteaseLyricHook.install(param.classLoader);
            }
            return;
        }
        if (!Contract.TARGET_PACKAGE.equals(param.packageName)) return;
        try {
            Class<?> infoClass = XposedHelpers.findClass(INFO_CLASS, param.classLoader);
            hookCatalogue(param.classLoader, infoClass);
            hookGroupTitle(param.classLoader);
            hookRuntimeHost(param.classLoader, infoClass);
            hookWallpaperColor(param.classLoader);
            WidgetLimitHook.install(param.classLoader);
            XposedBridge.log("MixFlipCustom: P0 hooks installed");
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: unsupported FlipHome build: " + error);
        }
    }

    private static void hookCatalogue(ClassLoader loader, Class<?> infoClass) {
        Class<?> configClass = XposedHelpers.findClass(CONFIG_CLASS, loader);
        XposedHelpers.findAndHookMethod(configClass, "loadAllWidget", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam hook) {
                try {
                    if (!(hook.getResult() instanceof List)) return;
                    @SuppressWarnings("unchecked")
                    List<Object> widgets = (List<Object>) hook.getResult();
                    rememberFallbackPath(widgets);
                    widgets.removeIf(HookEntry::isOurInfo);

                    Context context = currentFlipHomeContext();
                    List<WidgetConfig> configs = context == null
                            ? java.util.Collections.emptyList() : WidgetConfig.list(context);
                    int added = 0;
                    for (WidgetConfig config : configs) {
                        if (!config.enabled) continue;
                        if (WidgetTypeRegistry.MAML.equals(
                                WidgetTypeRegistry.resolve(config))) {
                            Object info = createMamlWidgetInfo(loader, context, config,
                                    widgets.size());
                            if (info != null) {
                                widgets.add(info);
                                added++;
                            }
                            continue;
                        }
                        Object info = createWidgetInfo(infoClass, config, widgets.size());
                        widgets.add(info);
                        added++;
                    }
                    cleanupStaleMamlImports(context, configs);
                    if (added == 0) {
                        report(context, "catalogue", false, "没有已启用的自定义 Widget");
                        return;
                    }
                    report(context, "compatibility", true,
                            "FlipHome catalogue / settings / runtime signatures matched");
                    report(context, "catalogue", true, "已注入 " + added + " 个 Widget");
                } catch (Throwable error) {
                    report(currentFlipHomeContext(), "catalogue", false, error.toString());
                    XposedBridge.log("MixFlipCustom: catalogue injection failed: " + error);
                }
            }
        });
    }

    /** Copies an imported MAML package into FlipHome's own res dir and lets the
     * native loader build the catalogue entry for it. */
    private static Object createMamlWidgetInfo(ClassLoader loader, Context context,
                                               WidgetConfig config, int priority) {
        if (context == null) return null;
        try {
            String fileName = Contract.mamlFileName(config.id);
            if (!ensureMamlInstalled(context, config, fileName)) {
                report(context, "catalogue", false, "MAML 包尚未就绪: " + config.name);
                return null;
            }
            Class<?> compatClass = XposedHelpers.findClass(MAML_COMPAT_CLASS, loader);
            Context deviceContext = context.createDeviceProtectedStorageContext();
            Object info = XposedHelpers.callStaticMethod(compatClass, "createWidgetInfo", fileName);
            if (info == null) {
                report(context, "catalogue", false, "MAML 包里没有 2x3 小部件: " + config.name);
                return null;
            }
            XposedHelpers.setIntField(info, "mShowInSetPage", priority);
            rememberMamlInstall(deviceContext, fileName);
            return info;
        } catch (Throwable error) {
            report(context, "catalogue", false, "MAML 导入失败: " + error.getClass().getSimpleName());
            XposedBridge.log("MixFlipCustom: maml catalogue entry failed: " + error);
            return null;
        }
    }

    private static boolean ensureMamlInstalled(Context context, WidgetConfig config,
                                               String fileName) {
        try {
            Context deviceContext = context.createDeviceProtectedStorageContext();
            File resDir = new File(deviceContext.getFilesDir(), "maml/res");
            File mtz = new File(resDir, fileName + ".mtz");
            File extracted = new File(resDir, fileName);
            if (mtz.isFile() || extracted.isDirectory()) return true;
            resDir.mkdirs();
            File temporary = new File(resDir, fileName + ".tmp");
            try (java.io.InputStream in = context.getContentResolver().openInputStream(
                    Contract.mamlUri(config.id));
                 java.io.FileOutputStream out = new java.io.FileOutputStream(temporary, false)) {
                if (in == null) return false;
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
                out.getFD().sync();
            } catch (Throwable error) {
                temporary.delete();
                return false;
            }
            if (!temporary.renameTo(mtz)) {
                temporary.delete();
                return false;
            }
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    private static void rememberMamlInstall(Context deviceContext, String fileName) {
        deviceContext.getSharedPreferences("mixflip_maml_imports", 0)
                .edit().putBoolean(fileName, true).apply();
    }

    private static void cleanupStaleMamlImports(Context context, List<WidgetConfig> configs) {
        if (context == null) return;
        try {
            Context deviceContext = context.createDeviceProtectedStorageContext();
            android.content.SharedPreferences prefs =
                    deviceContext.getSharedPreferences("mixflip_maml_imports", 0);
            java.util.Set<String> live = new java.util.HashSet<>();
            for (WidgetConfig config : configs) {
                String type = WidgetTypeRegistry.resolve(config);
                if (WidgetTypeRegistry.MAML.equals(type)) {
                    live.add(Contract.mamlFileName(config.id));
                } else if (WidgetTypeRegistry.APPWIDGET.equals(type)) {
                    for (WidgetComponent component : config.components) {
                        if (ActionSpec.HOST_MAML.equals(component.actionType)) {
                            live.add("mixflip_mamls_" + component.id);
                        }
                    }
                }
            }
            boolean changed = false;
            for (String installed : prefs.getAll().keySet()) {
                if (live.contains(installed)) continue;
                File resDir = new File(deviceContext.getFilesDir(), "maml/res");
                deleteTree(new File(resDir, installed + ".mtz"));
                deleteTree(new File(resDir, installed));
                prefs.edit().remove(installed).apply();
                changed = true;
            }
            if (changed) XposedBridge.log("MixFlipCustom: stale MAML imports cleaned");
        } catch (Throwable ignored) {
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }

    private static void hookGroupTitle(ClassLoader loader) {
        Class<?> viewModel = XposedHelpers.findClass(VIEW_MODEL_CLASS, loader);
        XposedBridge.hookAllConstructors(viewModel, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam hook) {
                try {
                    Context context = currentFlipHomeContext();
                    LiveRefreshBridge.install(context, loader);
                    LiveRefreshBridge.trackSettingsViewModel(hook.thisObject);
                    @SuppressWarnings("unchecked")
                    Map<String, String> typeMap = (Map<String, String>)
                            XposedHelpers.getObjectField(hook.thisObject, "mWidgetTypeMap");
                    typeMap.put(Contract.CUSTOM_TYPE, "自定义");
                    XposedBridge.log("MixFlipCustom: custom type map installed, size=" + typeMap.size());
                } catch (Throwable error) {
                    XposedBridge.log("MixFlipCustom: type map injection failed: " + error);
                }
            }
        });
        XposedHelpers.findAndHookMethod(viewModel, "getNameOfType", String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam hook) {
                if (Contract.CUSTOM_TYPE.equals(hook.args[0])) hook.setResult("自定义");
            }
        });
    }

    /**
     * Follows FlipHome's own backdrop color verdict (same sources native widgets use):
     * - WallpaperUtils.setCurrentWallpaperColorMode: wallpaper dark/light on the desk
     * - WidgetBgHelper.onDeskChanged / onAppColorChanged: whether the desk is visible and
     *   the foreground app's navigation bar color — this is what makes official widgets
     *   flip when a light-background app (e.g. 时钟) opens on the outer screen.
     */
    private static void hookWallpaperColor(ClassLoader loader) {
        try {
            Class<?> utils = XposedHelpers.findClass(
                    "com.miui.fliphome.wallpaper.WallpaperUtils", loader);
            XposedHelpers.findAndHookMethod(utils, "setCurrentWallpaperColorMode", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            int mode = (Integer) hook.args[0];
                            WallpaperColorState.setDarkWallpaper(mode == 0);
                            reportBackdrop("wallpaper colorMode=" + mode);
                        }
                    });
            boolean dark = (Boolean) XposedHelpers.callStaticMethod(utils, "hasAppliedDarkWallpaper");
            WallpaperColorState.setDarkWallpaper(dark);
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: wallpaper color hook unavailable: " + error);
        }
        try {
            Class<?> bgHelper = XposedHelpers.findClass(
                    "com.miui.fliphome.widget.WidgetBgHelper", loader);
            Class<?> bgListener = XposedHelpers.findClass(
                    "com.miui.fliphome.widget.WidgetBgHelper$IBackgroundListener", loader);
            // The constructor samples the current foreground app color itself; mirror it
            // so a FlipHome restart while an app is open still starts with the right state.
            XposedHelpers.findAndHookConstructor(bgHelper, bgListener, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam hook) {
                    WallpaperColorState.setOnDesk(XposedHelpers.getBooleanField(
                            hook.thisObject, "isDesk"));
                    WallpaperColorState.setAppColor(XposedHelpers.getIntField(
                            hook.thisObject, "appColor"));
                    reportBackdrop("bgHelper init");
                }
            });
            XposedHelpers.findAndHookMethod(bgHelper, "onDeskChanged", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            WallpaperColorState.setOnDesk((Boolean) hook.args[0]);
                            reportBackdrop("onDeskChanged=" + hook.args[0]);
                        }
                    });
            XposedHelpers.findAndHookMethod(bgHelper, "onAppColorChanged", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            int color = (Integer) hook.args[0];
                            WallpaperColorState.setAppColor(color);
                            reportBackdrop(String.format("appColor=#%08X", color));
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: widget bg hook unavailable: " + error);
        }
    }

    private static void reportBackdrop(String event) {
        report(currentFlipHomeContext(), "wallpaper", true, event
                + " 生效=" + (WallpaperColorState.isDarkWallpaper() ? "深色" : "浅色"));
    }

    private static void hookRuntimeHost(ClassLoader loader, Class<?> infoClass) {
        Class<?> compatClass = XposedHelpers.findClass(MAML_COMPAT_CLASS, loader);
        XposedHelpers.findAndHookMethod(compatClass, "createMamlHostView", Context.class, infoClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam hook) {
                        Object info = hook.args[1];
                        if (!isOurInfo(info)) return;
                        try {
                            Object path = XposedHelpers.getObjectField(info, "mResPath");
                            if (path == null && fallbackMamlPath != null) {
                                XposedHelpers.setObjectField(info, "mResPath", fallbackMamlPath);
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam hook) {
                        Object info = hook.args[1];
                        if (!isOurInfo(info)) return;
                        Context context = (Context) hook.args[0];
                        try {
                            LiveRefreshBridge.install(context, loader);
                            if (!(hook.getResult() instanceof ViewGroup)) {
                                report(context, "runtime", false, "MAML 兼容宿主创建失败");
                                return;
                            }
                            ViewGroup host = (ViewGroup) hook.getResult();
                            String fileName = String.valueOf(
                                    XposedHelpers.getObjectField(info, "mFileName"));
                            String widgetId = Contract.widgetIdFromFileName(fileName);
                            WidgetConfig config = WidgetConfig.load(context, widgetId);
                            if (config == null) {
                                report(context, "runtime", false, "找不到 Widget 配置: " + widgetId);
                                hook.setResult(null);
                                return;
                            }
                            prepareRuntimeHost(host);
                            MediaWidgetView overlay = new MediaWidgetView(context, config);
                            overlay.setTag(Contract.RUNTIME_VIEW_TAG);
                            host.addView(overlay, new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT));
                            LiveRefreshBridge.trackRuntimeHost(host, widgetId);
                            overlay.post(() -> report(context, "runtime", true,
                                    "运行时视图已创建: " + widgetId
                                            + " · " + overlay.getWidth() + "×" + overlay.getHeight()
                                            + " · host " + host.getWidth() + "×" + host.getHeight()));
                        } catch (Throwable error) {
                            report(context, "runtime", false, error.toString());
                            XposedBridge.log("MixFlipCustom: runtime overlay failed: " + error);
                        }
                    }
                });
    }

    private static void rememberFallbackPath(List<Object> widgets) {
        if (fallbackMamlPath != null) return;
        for (Object info : widgets) {
            try {
                Object path = XposedHelpers.getObjectField(info, "mResPath");
                if (path instanceof String && !((String) path).isEmpty()) {
                    fallbackMamlPath = (String) path;
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static Object createWidgetInfo(Class<?> infoClass, WidgetConfig config, int priority) {
        Object info = XposedHelpers.newInstance(infoClass, Contract.widgetFileName(config.id));
        XposedHelpers.setObjectField(info, "mTypeTag", Contract.CUSTOM_TYPE);
        XposedHelpers.setObjectField(info, "mTitle", config.name);
        XposedHelpers.setObjectField(info, "mCategory", Contract.CUSTOM_TYPE);
        XposedHelpers.setObjectField(info, "mAppPackageName", Contract.MODULE_PACKAGE);
        XposedHelpers.setObjectField(info, "mResPath", fallbackMamlPath);
        XposedHelpers.setIntField(info, "mShowInSetPage", priority);
        setAllPreviewFields(info, Contract.previewUri(config.id, config.repositoryRevision).toString());
        return info;
    }

    private static boolean isOurInfo(Object info) {
        if (info == null) return false;
        try {
            Object value = XposedHelpers.getObjectField(info, "mFileName");
            if (!(value instanceof String)) return false;
            String fileName = (String) value;
            return fileName.startsWith(Contract.WIDGET_FILE_PREFIX);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setAllPreviewFields(Object info, String path) {
        String[] fields = {"mLightPreviewPath", "mDarkPreviewPath", "mZHCNLightPreviewPath",
                "mZHCNDarkPreviewPath", "mENUSLightPreviewPath", "mENUSDarkPreviewPath"};
        for (String field : fields) XposedHelpers.setObjectField(info, field, path);
    }

    private static void prepareRuntimeHost(ViewGroup host) {
        // The concrete MAML host must remain as the method result, but its borrowed
        // renderer and touch target must not show or launch the original Xiaomi widget.
        host.removeAllViews();
        host.setBackgroundColor(Color.TRANSPARENT);
        host.setClickable(false);
        host.setFocusable(false);
        try { XposedHelpers.callMethod(host, "setTouchable", false); } catch (Throwable ignored) {}
    }

    private static Context currentFlipHomeContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            return (Context) XposedHelpers.callStaticMethod(activityThread, "currentApplication");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void report(Context context, String stage, boolean ok, String message) {
        if (context == null) return;
        try {
            Bundle extras = new Bundle();
            extras.putString("stage", stage);
            extras.putBoolean("ok", ok);
            extras.putString("message", message);
            context.getContentResolver().call(Contract.PROVIDER_URI, "report_hook", null, extras);
        } catch (Throwable ignored) {
        }
    }
}

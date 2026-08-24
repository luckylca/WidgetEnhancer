package com.lucky.mixflipouter;

import android.content.Context;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Hooks only FlipHome's official widget catalogue and MAML host factory. */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String INFO_CLASS = "com.miui.fliphome.widget.FlipWidgetInfo";
    private static final String CONFIG_CLASS = "com.miui.fliphome.widget.model.FlipWatchDefaultConfig";
    private static final String MAML_COMPAT_CLASS = "com.miui.fliphome.widget.ui.maml.FlipMaMlWidgetCompat";
    private static final String VIEW_MODEL_CLASS = "com.miui.fliphome.settings.widget.WidgetViewModel";
    private static volatile String fallbackMamlPath;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!Contract.TARGET_PACKAGE.equals(param.packageName)) return;
        try {
            Class<?> infoClass = XposedHelpers.findClass(INFO_CLASS, param.classLoader);
            hookCatalogue(param.classLoader, infoClass);
            hookGroupTitle(param.classLoader);
            hookRuntimeHost(param.classLoader, infoClass);
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
                        Object info = createWidgetInfo(infoClass, config, widgets.size());
                        widgets.add(info);
                        added++;
                    }
                    if (added == 0) {
                        report(context, "catalogue", false, "没有已启用的自定义 Widget");
                        return;
                    }
                    report(context, "catalogue", true, "已注入 " + added + " 个 Widget");
                } catch (Throwable error) {
                    report(currentFlipHomeContext(), "catalogue", false, error.toString());
                    XposedBridge.log("MixFlipCustom: catalogue injection failed: " + error);
                }
            }
        });
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
                            String widgetId = Contract.widgetIdFromFileName(
                                    String.valueOf(XposedHelpers.getObjectField(info, "mFileName")));
                            WidgetConfig config = WidgetConfig.load(context, widgetId);
                            if (config == null) {
                                report(context, "runtime", false, "找不到 Widget 配置: " + widgetId);
                                hook.setResult(null);
                                return;
                            }
                            removeExistingOverlay(host);
                            MediaWidgetView overlay = new MediaWidgetView(context, config);
                            overlay.setTag(Contract.RUNTIME_VIEW_TAG);
                            host.addView(overlay, new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT));
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
            return value instanceof String && ((String) value).startsWith(Contract.WIDGET_FILE_PREFIX);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setAllPreviewFields(Object info, String path) {
        String[] fields = {"mLightPreviewPath", "mDarkPreviewPath", "mZHCNLightPreviewPath",
                "mZHCNDarkPreviewPath", "mENUSLightPreviewPath", "mENUSDarkPreviewPath"};
        for (String field : fields) XposedHelpers.setObjectField(info, field, path);
    }

    private static void removeExistingOverlay(ViewGroup host) {
        for (int i = host.getChildCount() - 1; i >= 0; i--) {
            if (Contract.RUNTIME_VIEW_TAG.equals(host.getChildAt(i).getTag())) host.removeViewAt(i);
        }
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

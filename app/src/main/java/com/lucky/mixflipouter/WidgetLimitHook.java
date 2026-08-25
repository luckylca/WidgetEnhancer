package com.lucky.mixflipouter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Removes FlipHome's UI and persistence limits on the number of widget pages. */
final class WidgetLimitHook {
    private static final String VIEW_MODEL_CLASS =
            "com.miui.fliphome.settings.widget.WidgetViewModel";
    private static final String ADAPTER_EDITOR_CLASS =
            "com.miui.fliphome.settings.widget.WidgetAdapter$AdapterWidgetEditor";
    private static final String CHILD_ADAPTER_CLASS =
            "com.miui.fliphome.settings.widget.WidgetChildAdapter";
    private static final String MODEL_CLASS =
            "com.miui.fliphome.widget.model.FlipWidgetModel";
    private static boolean installed;

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        Class<?> viewModel = XposedHelpers.findClass(VIEW_MODEL_CLASS, loader);
        Class<?> widgetItem = XposedHelpers.findClass(VIEW_MODEL_CLASS + "$WidgetItem", loader);
        hookViewModel(loader, viewModel, widgetItem);
        hookSettingsAdapters(loader, widgetItem);
        hookPersistence(loader);
        installed = true;
        XposedBridge.log("MixFlipCustom: unlimited widget pages hook installed");
    }

    private static void hookViewModel(ClassLoader loader, Class<?> viewModel, Class<?> widgetItem) {
        XposedHelpers.findAndHookMethod(viewModel, "addWidget", widgetItem, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam hook) {
                Object item = hook.args[0];
                @SuppressWarnings("unchecked")
                List<String> addedIds = (List<String>) XposedHelpers.getObjectField(
                        hook.thisObject, "mAddedIdList");
                if (item == null || addedIds == null) return;
                String id = String.valueOf(XposedHelpers.getObjectField(item, "id"));
                if (addedIds.contains(id)) {
                    hook.setResult(null);
                    return;
                }

                addedIds.add(id);
                Object addedInfo = XposedHelpers.callMethod(hook.thisObject, "getAddedWidgetInfo");
                @SuppressWarnings("unchecked")
                List<Object> addedItems = (List<Object>) XposedHelpers.getObjectField(
                        addedInfo, "items");
                addedItems.add(item);
                XposedHelpers.setObjectField(addedInfo, "typeTitle",
                        normalAddedTitle(loader, hook.thisObject));

                if (XposedHelpers.getBooleanField(hook.thisObject, "mIsO8Model")) {
                    Object availableInfo = XposedHelpers.callMethod(
                            hook.thisObject, "getAvailableWidgetInfo");
                    @SuppressWarnings("unchecked")
                    List<Object> availableItems = (List<Object>) XposedHelpers.getObjectField(
                            availableInfo, "items");
                    availableItems.remove(item);
                }
                XposedHelpers.callMethod(hook.thisObject, "onAddedUpdate");
                @SuppressWarnings("unchecked")
                List<Object> newlyAdded = (List<Object>) XposedHelpers.getObjectField(
                        hook.thisObject, "mNewAddedWidget");
                newlyAdded.add(item);
                hook.setResult(null);
            }
        });

        XposedHelpers.findAndHookMethod(viewModel, "getAddedTitle", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam hook) {
                hook.setResult(normalAddedTitle(loader, hook.thisObject));
            }
        });
    }

    private static void hookSettingsAdapters(ClassLoader loader, Class<?> widgetItem) {
        Class<?> adapterEditor = XposedHelpers.findClass(ADAPTER_EDITOR_CLASS, loader);
        XposedHelpers.findAndHookMethod(adapterEditor, "addWidget", widgetItem,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam hook) {
                        Object editor = XposedHelpers.getObjectField(hook.thisObject, "editor");
                        XposedHelpers.callMethod(editor, "addWidget", hook.args[0]);
                        Object adapter = XposedHelpers.getObjectField(hook.thisObject, "this$0");
                        XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
                        XposedHelpers.callMethod(hook.thisObject, "refreshTitle");
                        XposedHelpers.callMethod(hook.thisObject, "scrollToEndPosition");
                        hook.setResult(null);
                    }
                });

        Class<?> childAdapter = XposedHelpers.findClass(CHILD_ADAPTER_CLASS, loader);
        XposedHelpers.findAndHookMethod(childAdapter, "isItemLimited", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam hook) {
                if (!XposedHelpers.getBooleanField(hook.thisObject, "mIsMineType")) {
                    hook.setResult(false);
                }
            }
        });
    }

    private static void hookPersistence(ClassLoader loader) {
        Class<?> model = XposedHelpers.findClass(MODEL_CLASS, loader);
        XposedHelpers.findAndHookMethod(model, "saveToDatabase",
                List.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam hook) {
                        if (!(hook.args[0] instanceof List)
                                || ((List<?>) hook.args[0]).size() <= 5) return;
                        ArrayList<?> widgets = new ArrayList<>((List<?>) hook.args[0]);
                        XposedHelpers.callMethod(hook.thisObject, "setWidgetDatabaseUsed");
                        Runnable persist = () -> XposedHelpers.callMethod(
                                hook.thisObject, "doUpdateWidgetEntity", widgets);
                        if ((boolean) hook.args[1]) {
                            Class<?> executors = XposedHelpers.findClass(
                                    "com.miui.fliphome.utils.Executors", loader);
                            Object executor = XposedHelpers.getStaticObjectField(
                                    executors, "UI_HELPER_EXECUTOR");
                            if (executor instanceof Executor) ((Executor) executor).execute(persist);
                            else persist.run();
                        } else {
                            persist.run();
                        }
                        hook.setResult(true);
                    }
                });
    }

    private static String normalAddedTitle(ClassLoader loader, Object viewModel) {
        try {
            boolean o8 = XposedHelpers.getBooleanField(viewModel, "mIsO8Model");
            Class<?> strings = XposedHelpers.findClass("com.miui.fliphome.R$string", loader);
            int resource = XposedHelpers.getStaticIntField(strings,
                    o8 ? "widget_settings_added" : "widget_settings_mine");
            Class<?> application = XposedHelpers.findClass(
                    "com.miui.fliphome.FlipApplication", loader);
            Object instance = XposedHelpers.callStaticMethod(application, "getInstance");
            return String.valueOf(XposedHelpers.callMethod(instance, "getString", resource));
        } catch (Throwable ignored) {
            return "我的小部件";
        }
    }

    private WidgetLimitHook() {}
}

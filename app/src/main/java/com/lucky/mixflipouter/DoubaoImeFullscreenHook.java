package com.lucky.mixflipouter;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.Window;
import android.view.WindowManager;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Cover-screen integration for Doubao Input Method. */
final class DoubaoImeFullscreenHook {
    private static DisplayManager.DisplayListener displayListener;
    private static WeakReference<InputMethodService> currentService = new WeakReference<>(null);
    private static final Map<Window, Integer> ORIGINAL_CUTOUT_MODES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, ToolbarBaseline> TOOLBAR_BASELINES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Boolean> OBSERVED_DECORS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final String TOOLBAR_CLASS =
            "com.bytedance.common_biz.tool_bar.view.views.CandidateToolbarHostLayout";

    static void install(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.bytedance.android.doubaoime".equals(param.packageName)
                || !param.packageName.equals(param.processName)) return;
        try {
            XposedHelpers.findAndHookMethod("android.inputmethodservice.InputMethodServiceInjector",
                    param.classLoader, "isImeSupport", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            if (isRotatedCover((Context) hook.args[0])) hook.setResult(true);
                        }
                    });
            XposedHelpers.findAndHookMethod(InputMethodService.class, "showWindow",
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            InputMethodService service = (InputMethodService) hook.thisObject;
                            observeRotation(service);
                            configureWindow(service);
                            observeLayout(service);
                            new Handler(service.getMainLooper()).postDelayed(
                                    () -> fitCandidateToolbar(service), 600L);
                        }
                    });
            Class<?> serviceClass = XposedHelpers.findClass(
                    "com.bytedance.android.doubaoime.ImeService", param.classLoader);
            XposedHelpers.findAndHookMethod(serviceClass, "onConfigurationChanged",
                    Configuration.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            InputMethodService service = (InputMethodService) hook.thisObject;
                            configureWindow(service);
                        }
                    });
            XposedBridge.log("MixFlipCustom: Doubao cover keyboard integration loaded");
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: Doubao keyboard integration unavailable: " + error);
        }
    }

    private static void observeRotation(InputMethodService service) {
        currentService = new WeakReference<>(service);
        if (displayListener != null) return;
        DisplayManager manager = (DisplayManager) service.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) return;
        displayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {}
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {
                InputMethodService active = currentService.get();
                if (active == null || active.getDisplay() == null
                        || active.getDisplay().getDisplayId() != displayId) return;
                configureWindow(active);
                new Handler(active.getMainLooper()).postDelayed(() -> {
                    configureWindow(active);
                    fitCandidateToolbar(active);
                }, 500L);
            }
        };
        manager.registerDisplayListener(displayListener, new Handler(service.getMainLooper()));
    }

    private static void observeLayout(InputMethodService service) {
        Dialog dialog = service.getWindow();
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (OBSERVED_DECORS.putIfAbsent(decor, true) != null) return;
        WeakReference<InputMethodService> reference = new WeakReference<>(service);
        decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            InputMethodService active = reference.get();
            if (active != null) fitCandidateToolbar(active);
        });
    }

    private static void fitCandidateToolbar(InputMethodService service) {
        try {
            Dialog dialog = service.getWindow();
            Window window = dialog == null ? null : dialog.getWindow();
            if (window == null) return;
            View decor = window.getDecorView();
            View toolbar = findView(decor, TOOLBAR_CLASS);
            if (toolbar == null || toolbar.getLayoutParams() == null) return;
            if (!isRotatedCover(service)) {
                ToolbarBaseline baseline = TOOLBAR_BASELINES.remove(toolbar);
                if (baseline == null) return;
                ViewGroup.LayoutParams params = toolbar.getLayoutParams();
                if (params.width != baseline.width) {
                    params.width = baseline.width;
                    toolbar.setLayoutParams(params);
                }
                toolbar.setTranslationX(baseline.translationX);
                return;
            }
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets == null || decor.getWidth() <= 0) return;
            List<Rect> cutouts = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 35) {
                cutouts.addAll(insets.getBoundingRects(WindowInsets.Type.displayCutout()));
            } else if (insets.getDisplayCutout() != null) {
                cutouts.addAll(insets.getDisplayCutout().getBoundingRects());
            }
            int[] location = new int[2];
            toolbar.getLocationOnScreen(location);
            int left = 0;
            for (Rect cutout : cutouts) {
                if (cutout.left <= 0 && cutout.right > 0
                        && cutout.bottom > location[1]
                        && cutout.top < location[1] + toolbar.getHeight()) {
                    left = Math.max(left, cutout.right);
                }
            }
            if (left <= 0 || left >= decor.getWidth()) return;
            ViewGroup.LayoutParams params = toolbar.getLayoutParams();
            TOOLBAR_BASELINES.putIfAbsent(toolbar,
                    new ToolbarBaseline(params.width, toolbar.getTranslationX()));
            int targetWidth = decor.getWidth() - left;
            if (params.width != targetWidth) {
                params.width = targetWidth;
                toolbar.setLayoutParams(params);
            }
            float targetTranslation = left;
            if (Math.abs(toolbar.getTranslationX() - targetTranslation) > 0.5f) {
                toolbar.setTranslationX(targetTranslation);
            }
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: Doubao candidate fitting failed: " + error);
        }
    }

    private static View findView(View view, String className) {
        if (className.equals(view.getClass().getName())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View found = findView(group.getChildAt(index), className);
            if (found != null) return found;
        }
        return null;
    }

    private static final class ToolbarBaseline {
        final int width;
        final float translationX;
        ToolbarBaseline(int width, float translationX) {
            this.width = width;
            this.translationX = translationX;
        }
    }

    private static boolean isRotatedCover(Context context) {
        if (context == null) return false;
        Configuration config = context.getResources().getConfiguration();
        int shortSide = Math.min(config.screenWidthDp, config.screenHeightDp);
        int longSide = Math.max(config.screenWidthDp, config.screenHeightDp);
        return config.orientation == Configuration.ORIENTATION_PORTRAIT
                && shortSide > 0 && shortSide <= 420 && longSide <= 540
                && displayRotation(context) == android.view.Surface.ROTATION_180;
    }

    private static int displayRotation(Context context) {
        try {
            Object windowConfiguration = XposedHelpers.getObjectField(
                    context.getResources().getConfiguration(), "windowConfiguration");
            return ((Number) XposedHelpers.callMethod(windowConfiguration,
                    "getDisplayRotation")).intValue();
        } catch (Throwable ignored) {
            return context.getDisplay() == null ? -1 : context.getDisplay().getRotation();
        }
    }

    private static void configureWindow(InputMethodService service) {
        try {
            Dialog dialog = service.getWindow();
            Window window = dialog == null ? null : dialog.getWindow();
            if (window == null) return;
            WindowManager.LayoutParams attrs = window.getAttributes();
            if (isRotatedCover(service)) {
                ORIGINAL_CUTOUT_MODES.putIfAbsent(window, attrs.layoutInDisplayCutoutMode);
                if (attrs.layoutInDisplayCutoutMode
                        != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
                    attrs.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                    window.setAttributes(attrs);
                }
            } else {
                Integer original = ORIGINAL_CUTOUT_MODES.remove(window);
                if (original != null && attrs.layoutInDisplayCutoutMode != original) {
                    attrs.layoutInDisplayCutoutMode = original;
                    window.setAttributes(attrs);
                }
            }
            window.getDecorView().requestApplyInsets();
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: Doubao window setup failed: " + error);
        }
    }

    private DoubaoImeFullscreenHook() {}
}

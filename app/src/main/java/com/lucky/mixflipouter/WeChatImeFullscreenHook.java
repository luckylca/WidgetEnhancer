package com.lucky.mixflipouter;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Display;
import android.view.Surface;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.graphics.Insets;
import android.widget.LinearLayout;
import android.view.Gravity;
import android.graphics.drawable.Drawable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.WeakHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Adapts WeChat IME layout to the active compact display and its cutout. */
public final class WeChatImeFullscreenHook {
    private static final String INJECTOR_CLASS =
            "android.inputmethodservice.InputMethodServiceInjector";
    private static final String KEYBOARD_VIEW_CLASS =
            "com.tencent.wetype.plugin.hld.keyboard.selfdraw.n";
    private static final String KEYBOARD_BASE_VIEW_CLASS =
            "com.tencent.wetype.plugin.hld.keyboard.AbstractC1942s";
    private static final String CANDIDATE_VIEW_CLASS =
            "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView";
    private static final String SYMBOL_SCROLL_VIEW_CLASS =
            "com.tencent.wetype.plugin.hld.oldsymbol.ImeSboAndSybKeysScrollView";
    private static final int MAX_COMPACT_SHORT_SIDE_DP = 420;
    private static final int MAX_COMPACT_LONG_SIDE_DP = 540;
    private static final Pattern KEYBOARD_BUTTON_BOUNDS = Pattern.compile(
            "\\[(-?\\d+),\\s*(-?\\d+)\\]\\[(-?\\d+),\\s*(-?\\d+)\\]");
    private static final Map<View, float[]> NORMAL_WIDTH_SCALES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile int compactKeyboardWidth;
    private static volatile int compactT9MetricWidth;
    private static volatile boolean scopeReporterInstalled;
    private static volatile boolean scopeReported;
    private static volatile boolean scopeReportPending;

    private static final Map<Window, Integer> ORIGINAL_CUTOUT_MODES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Window, WindowBaseline> WINDOW_BASELINES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, LayoutBaseline> ORIGINAL_LAYOUTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Float> ROOT_TRANSLATIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Float> SYMBOL_TRANSLATIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, int[]> ORIGINAL_PADDING =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, ArcNotchDrawable> ARC_NOTCH_BACKGROUNDS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, KeyboardBaseline> KEYBOARD_BASELINES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, KeyboardBaseline> KEYBOARD_TYPE_BASELINES =
            Collections.synchronizedMap(new HashMap<>());
    private static final Map<View, Integer> AVAILABLE_ROOT_HEIGHTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Boolean> COMPACT_DISPLAY_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, SymbolRowBaseline> SYMBOL_ROW_BASELINES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Integer> SYMBOL_ITEM_LAYOUT_HEIGHTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile DisplayManager rotationDisplayManager;
    private static volatile DisplayManager.DisplayListener rotationDisplayListener;
    private static volatile boolean viewAttachObserverInstalled;
    private static volatile boolean normalWidthRefreshPending;
    private static volatile WeakReference<InputMethodService> rotationService =
            new WeakReference<>(null);
    private static volatile WeakReference<View> rotationDecor = new WeakReference<>(null);
    private static volatile int rotationDisplayId = -1;
    private static final Map<ClassLoader, List<XC_MethodHook.Unhook>> WIDTH_HOOKS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, ViewTreeObserver.OnGlobalLayoutListener> LAYOUT_REFRESHERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Boolean> PENDING_ADAPTATIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Method, Boolean> OBSERVED_DATA_SETTERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> APPLYING_KEYBOARD_DATA = new ThreadLocal<>();
    private static String lastObservedKeyboard;
    private static Boolean savedKeepSymbols;
    private static Boolean lastSymbolRetentionState;
    private static long lastPopupPositionLogAt;
    private static WeakReference<View> lastActiveKeyboard = new WeakReference<>(null);
    private static final Map<View, WeakReference<View>> LONG_PRESS_KEYBOARDS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<InputMethodService, Boolean> WINDOW_VISIBLE_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    static void install(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        if (!"com.tencent.wetype".equals(param.packageName)) return;
        try {
            installScopeReporter();
            installNormalLongPressAnchor(param.classLoader);
            XposedHelpers.findAndHookMethod(INJECTOR_CLASS, param.classLoader,
                    "isImeSupport", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            if (isCompactCover((Context) hook.args[0])) hook.setResult(true);
                        }
                    });

            XposedHelpers.findAndHookMethod(InputMethodService.class, "showWindow",
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            InputMethodService service =
                                    (InputMethodService) hook.thisObject;
                            reportWindowVisibility(service, true);
                            reportWeChatScope(service);
                            Window window;
                            try {
                                Dialog dialog = service.getWindow();
                                window = dialog == null ? null : dialog.getWindow();
                                if (window == null) return;
                            } catch (Throwable error) {
                                ModuleLogger.logException("WeChat window setup failed", error);
                                return;
                            }
                            View decor = window.getDecorView();
                            observeDisplayRotation(service, decor);
                            installLayoutRefresher(service, decor);
                            refreshDisplayState(service, decor);
                        }
                    });
            try {
                XposedHelpers.findAndHookMethod(InputMethodService.class, "hideWindow",
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam hook) {
                                reportWindowVisibility((InputMethodService) hook.thisObject, false);
                            }
                        });
            } catch (Throwable error) {
                ModuleLogger.logHookFailure("WeChat keyboard window hidden state",
                        "InputMethodService", "hideWindow", "()", error);
            }
            XposedHelpers.findAndHookMethod(InputMethodService.class, "setInputView",
                    View.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            InputMethodService service =
                                    (InputMethodService) hook.thisObject;
                            try {
                                Dialog dialog = service.getWindow();
                                Window window = dialog == null ? null : dialog.getWindow();
                                if (window == null) return;
                                View decor = window.getDecorView();
                                observeDisplayRotation(service, decor);
                                installLayoutRefresher(service, decor);
                                refreshDisplayState(service, decor);
                                if (!isCompactCover(service)
                                        && hook.args[0] instanceof View) {
                                    restoreNativeLayoutAfterViewAttach((View) hook.args[0]);
                                }
                            } catch (Throwable error) {
                                ModuleLogger.logException("WeChat input view refresh failed", error);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onCreateInputView",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            InputMethodService service =
                                    (InputMethodService) hook.thisObject;
                            if (!(hook.getResult() instanceof View)) return;
                            try {
                                Dialog dialog = service.getWindow();
                                Window window = dialog == null ? null : dialog.getWindow();
                                if (window == null) return;
                                View decor = window.getDecorView();
                                observeDisplayRotation(service, decor);
                                installLayoutRefresher(service, decor);
                                if (isCompactCover(service)) {
                                    schedule(service, decor, 0);
                                }
                                new Handler(service.getMainLooper()).postDelayed(
                                        () -> refreshDisplayState(service, decor), 200L);
                            } catch (Throwable error) {
                                ModuleLogger.logException("WeChat created input view refresh failed", error);
                            }
                        }
                    });
            installRotatedViewAttachObserver();
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onUpdateSelection",
                    int.class, int.class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            InputMethodService service = (InputMethodService) hook.thisObject;
                            if (!isCompactCover(service)) return;
                            try {
                                Dialog dialog = service.getWindow();
                                Window window = dialog == null ? null : dialog.getWindow();
                                if (window != null) {
                                    schedule(service, window.getDecorView(), 0);
                                }
                            } catch (Throwable error) {
                                ModuleLogger.logExceptionThrottled("WeChat candidate refresh failed", error);
                            }
                        }
                    });
            ModuleLogger.logFeatureState("WeChat IME cover adaptation", "SUCCESS",
                    "窗口与键盘布局 hooks 已注册");
        } catch (Throwable error) {
            ModuleLogger.logFeatureState("WeChat IME cover adaptation", "PARTIAL",
                    "一个或多个输入法内部 API 不匹配");
            throw error;
        }
    }

    private static void reportWindowVisibility(InputMethodService service, boolean visible) {
        Boolean previous = WINDOW_VISIBLE_STATES.put(service, visible);
        if (previous == null || previous.booleanValue() != visible) {
            ModuleLogger.logFeatureState("WeChat keyboard window",
                    visible ? "SHOWN" : "HIDDEN", "外屏输入法窗口状态已变化");
        }
    }

    private static void installNormalLongPressAnchor(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(ViewGroup.class, "addView", View.class,
                    ViewGroup.LayoutParams.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    View child = (View) hook.args[0];
                    if (!child.getClass().getName().matches(
                            "com\\.tencent\\.wetype\\.plugin\\.hld\\.floatview\\.(A|D|p)")) return;
                    View keyboard = lastActiveKeyboard.get();
                    if (keyboard == null || !NORMAL_WIDTH_SCALES.containsKey(keyboard)
                            || !getKeyboardType(keyboard).contains("Qwerty")
                            || !(hook.args[1] instanceof android.widget.FrameLayout.LayoutParams)) return;
                    try {
                        Object button = XposedHelpers.callMethod(keyboard, "getActionButton");
                        Rect rect = (Rect) XposedHelpers.callMethod(button, "t");
                        android.widget.FrameLayout.LayoutParams params =
                                (android.widget.FrameLayout.LayoutParams) hook.args[1];
                        int offset = Math.round(rect.exactCenterX() * (1f - keyboard.getScaleX()));
                        params.leftMargin -= offset;
                        LONG_PRESS_KEYBOARDS.put(child, new WeakReference<>(keyboard));
                        long now = System.currentTimeMillis();
                        if (now - lastPopupPositionLogAt >= 5000L) {
                            lastPopupPositionLogAt = now;
                            ModuleLogger.logFeatureState("WeChat long-press popup",
                                    "POSITIONED", "弹出位置已按键盘缩放比例校正");
                        }
                    } catch (Throwable error) {
                        ModuleLogger.logException("WeChat long-press popup positioning failed", error);
                    }
                }
            });
            for (String name : new String[]{"y", "p"}) {
                Class<?> type = XposedHelpers.findClass(
                        "com.tencent.wetype.plugin.hld.floatview." + name, loader);
                XposedBridge.hookAllMethods(type, "e", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    WeakReference<View> ref = LONG_PRESS_KEYBOARDS.get(hook.thisObject);
                    View keyboard = ref == null ? null : ref.get();
                    if (keyboard == null || !(hook.args[0] instanceof Float)) return;
                    int[] location = new int[2];
                    keyboard.getLocationInWindow(location);
                    float x = (Float) hook.args[0];
                    hook.args[0] = location[0] + (x - location[0]) * keyboard.getScaleX();
                }
            });
            }
        } catch (Throwable error) {
            ModuleLogger.logHookFailure("WeChat long-press popup positioning",
                    "ViewGroup / wetype.plugin.hld.floatview", "addView / e", "View and LayoutParams", error);
        }
    }

    private static void installScopeReporter() {
        if (scopeReporterInstalled) return;
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            Object context = hook.args[0];
                            if (context instanceof Context) {
                                reportWeChatScope((Context) context);
                            }
                        }
                    });
            scopeReporterInstalled = true;
        } catch (Throwable error) {
            ModuleLogger.logException("WeChat scope reporter hook unavailable", error);
        }
    }

    /** A successful in-process callback proves LSPosed loaded this package scope. */
    private static void reportWeChatScope(Context context) {
        synchronized (WeChatImeFullscreenHook.class) {
            if (scopeReported || scopeReportPending) return;
            scopeReportPending = true;
        }
        Thread reporter = new Thread(() -> {
            try {
                long[] retryDelays = {0L, 500L, 1500L, 4000L};
                for (long delay : retryDelays) {
                    if (delay > 0) Thread.sleep(delay);
                    try {
                        Bundle result = context.getContentResolver().call(
                                ImeScopeStatusProvider.URI, "report_scope", "wechat", null);
                        if (result != null && result.getBoolean("accepted", false)) {
                            scopeReported = true;
                            ModuleLogger.info("WeChat IME process load beacon accepted");
                            return;
                        }
                    } catch (Throwable error) {
                        ModuleLogger.logException("WeChat IME scope report attempt failed", error);
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                scopeReportPending = false;
            }
        }, "MixFlipWechatScope");
        reporter.setDaemon(true);
        reporter.start();
    }

    private static void configureEdgeToEdge(Window window, boolean compact) {
        View decor = window.getDecorView();
        WindowBaseline baseline = WINDOW_BASELINES.get(window);
        if (baseline == null) {
            baseline = new WindowBaseline(decor.getSystemUiVisibility(),
                    window.getNavigationBarColor(), Build.VERSION.SDK_INT >= 29
                    && window.isNavigationBarContrastEnforced(),
                    getOnApplyWindowInsetsListener(decor));
            WINDOW_BASELINES.put(window, baseline);
        }
        if (compact) {
            window.setDecorFitsSystemWindows(false);
            window.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            decor.setOnApplyWindowInsetsListener((view, insets) -> {
                if (Build.VERSION.SDK_INT < 30) return insets;
                return new WindowInsets.Builder(insets)
                        .setInsets(WindowInsets.Type.navigationBars(), Insets.NONE)
                        .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), Insets.NONE)
                        .build();
            });
            decor.requestApplyInsets();
        } else {
            decor.setOnApplyWindowInsetsListener(baseline.onApplyWindowInsetsListener);
            decor.setSystemUiVisibility(baseline.systemUiVisibility);
            window.setDecorFitsSystemWindows(true);
            window.setNavigationBarColor(baseline.navigationBarColor);
            if (Build.VERSION.SDK_INT >= 29) {
                window.setNavigationBarContrastEnforced(baseline.navigationBarContrastEnforced);
            }
            WINDOW_BASELINES.remove(window);
            decor.requestApplyInsets();
        }
    }

    private static View.OnApplyWindowInsetsListener getOnApplyWindowInsetsListener(
            View view) {
        try {
            Field listenerInfoField = View.class.getDeclaredField("mListenerInfo");
            listenerInfoField.setAccessible(true);
            Object listenerInfo = listenerInfoField.get(view);
            if (listenerInfo == null) return null;
            Field listenerField = listenerInfo.getClass()
                    .getDeclaredField("mOnApplyWindowInsetsListener");
            listenerField.setAccessible(true);
            Object listener = listenerField.get(listenerInfo);
            return listener instanceof View.OnApplyWindowInsetsListener
                    ? (View.OnApplyWindowInsetsListener) listener : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void schedule(InputMethodService service, View decor, int attempt) {
        synchronized (PENDING_ADAPTATIONS) {
            if (PENDING_ADAPTATIONS.containsKey(decor)) return;
            PENDING_ADAPTATIONS.put(decor, true);
        }
        new Handler(service.getMainLooper()).postDelayed(
                () -> {
                    synchronized (PENDING_ADAPTATIONS) {
                        PENDING_ADAPTATIONS.remove(decor);
                    }
                    adapt(service, decor, attempt);
                }, 100L);
    }

    /** Re-run after WeChat swaps keyboard modes without changing editor selection. */
    private static void installLayoutRefresher(InputMethodService service, View decor) {
        synchronized (LAYOUT_REFRESHERS) {
            if (LAYOUT_REFRESHERS.containsKey(decor)) return;
        }
        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (!observer.isAlive()) return;
        WeakReference<InputMethodService> serviceRef = new WeakReference<>(service);
        WeakReference<View> decorRef = new WeakReference<>(decor);
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
            InputMethodService currentService = serviceRef.get();
            View currentDecor = decorRef.get();
            if (currentService == null || currentDecor == null) return;
            refreshDisplayState(currentService, currentDecor);
        };
        synchronized (LAYOUT_REFRESHERS) {
            if (LAYOUT_REFRESHERS.containsKey(decor)) return;
            LAYOUT_REFRESHERS.put(decor, listener);
        }
        observer.addOnGlobalLayoutListener(listener);
    }

    private static void observeDisplayRotation(InputMethodService service, View decor) {
        try {
            rotationService = new WeakReference<>(service);
            rotationDecor = new WeakReference<>(decor);
            Display display = getContextDisplay(service);
            rotationDisplayId = display == null ? -1 : display.getDisplayId();
            if (rotationDisplayListener != null) return;

            DisplayManager manager = (DisplayManager) service.getSystemService(
                    Context.DISPLAY_SERVICE);
            if (manager == null) return;
            DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {}

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId != rotationDisplayId) return;
                    InputMethodService currentService = rotationService.get();
                    View currentDecor = rotationDecor.get();
                    if (currentService != null && currentDecor != null) {
                        refreshDisplayState(currentService, currentDecor);
                    }
                }
            };
            manager.registerDisplayListener(listener, new Handler(service.getMainLooper()));
            rotationDisplayManager = manager;
            rotationDisplayListener = listener;
        } catch (Throwable error) {
            ModuleLogger.logException("WeChat display rotation observer unavailable", error);
        }
    }

    private static void installRotatedViewAttachObserver() {
        if (viewAttachObserverInstalled) return;
        synchronized (WeChatImeFullscreenHook.class) {
            if (viewAttachObserverInstalled) return;
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            InputMethodService service = rotationService.get();
                            if (service == null) return;
                            View view = (View) hook.thisObject;
                            String className = view.getClass().getName();
                            String packageName = view.getContext().getPackageName();
                            if (!"com.tencent.wetype".equals(packageName)
                                    && !className.startsWith("com.tencent.wetype.")) return;
                            View root = view.getRootView();
                            if (isCompactCover(service)) {
                                if (findKeyboard(root) != null
                                        && findByClassName(root, CANDIDATE_VIEW_CLASS) != null) {
                                    schedule(service, root, 0);
                                }
                            } else if (isNormalCover(service)) {
                                schedule(service, root, 0);
                            } else {
                                restoreNativeLayoutAfterViewAttach(root);
                            }
                        }
                    });
            viewAttachObserverInstalled = true;
        }
    }

    /** Restore mutations left on a hidden keyboard when the system swaps input views. */
    private static void restoreNativeLayoutAfterViewAttach(View root) {
        View keyboard = findKeyboard(root);
        if (keyboard == null) return;
        try {
            if (keyboard.getHeight() <= 0) return;
            String type = String.valueOf(XposedHelpers.callMethod(keyboard, "getKeyboardType"));
            KeyboardBaseline baseline = KEYBOARD_BASELINES.get(keyboard);
            if (baseline == null) {
                baseline = KEYBOARD_TYPE_BASELINES.get(type);
                if (baseline != null) {
                    KEYBOARD_BASELINES.put(keyboard, baseline);
                } else {
                    ensureKeyboardBaseline(keyboard);
                    ModuleLogger.debug("Captured native WeChat keyboard baseline on view attach");
                    return;
                }
            }
            if (baseline == null || baseline.appliedScale < 0.0f) return;
            View candidate = findByClassName(root, CANDIDATE_VIEW_CLASS);
            if (candidate != null) restoreLayout(candidate, keyboard);
            restoreKeyboard(keyboard);
            restorePunctuationRows();
            ModuleLogger.info("Restored native WeChat keyboard layout on normal view attach");
        } catch (Throwable error) {
            ModuleLogger.logException("WeChat native keyboard attach restore failed", error);
        }
    }

    private static Display getContextDisplay(Context context) {
        Display display = context.getDisplay();
        if (display != null) return display;
        WindowManager windowManager = (WindowManager) context.getSystemService(
                Context.WINDOW_SERVICE);
        return windowManager == null ? null : windowManager.getDefaultDisplay();
    }

    private static String getKeyboardType(View keyboard) {
        try {
            return String.valueOf(XposedHelpers.callMethod(keyboard, "getKeyboardType"));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int readNativeKeyboardWidthMetric(View keyboard, String methodName) {
        try {
            Class<?> metricClass = XposedHelpers.findClass(
                    "com.tencent.wetype.plugin.hld.utils.m1",
                    keyboard.getClass().getClassLoader());
            Object keyboardType = XposedHelpers.callMethod(keyboard, "getKeyboardType");
            Object instance = null;
            for (Field field : metricClass.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || field.getType() != metricClass) continue;
                field.setAccessible(true);
                instance = field.get(null);
                if (instance != null) break;
            }
            if (instance == null) return 0;
            Object value = XposedHelpers.callMethod(instance, methodName, keyboardType);
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Throwable error) {
            ModuleLogger.logException("WeChat native keyboard width API unavailable: " + methodName, error);
            return 0;
        }
    }

    private static void resetNativeKeyboardCaches(View keyboard, Context context) {
        try {
            Class<?> metricClass = XposedHelpers.findClass(
                    "com.tencent.wetype.plugin.hld.utils.m1",
                    keyboard.getClass().getClassLoader());
            for (Field field : metricClass.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || field.getType() != metricClass) continue;
                field.setAccessible(true);
                Object instance = field.get(null);
                if (instance == null) continue;
                XposedHelpers.callMethod(instance, "i2", context, true);
                return;
            }
        } catch (Throwable error) {
            ModuleLogger.logException("WeChat native keyboard cache reset failed", error);
        }
    }

    private static int getConfigurationDisplayRotation(Context context) {
        try {
            Object windowConfig = XposedHelpers.getObjectField(
                    context.getResources().getConfiguration(), "windowConfiguration");
            Object rotation = XposedHelpers.callMethod(windowConfig, "getDisplayRotation");
            return rotation instanceof Number ? ((Number) rotation).intValue() : -1;
        } catch (Throwable ignored) {
            Display display = getContextDisplay(context);
            return display == null ? -1 : display.getRotation();
        }
    }

    private static void refreshDisplayState(InputMethodService service, View decor) {
        boolean compact = isCompactCover(service);
        Boolean wasCompact = COMPACT_DISPLAY_STATES.put(decor, compact);
        if (wasCompact == null || wasCompact.booleanValue() != compact) {
            ModuleLogger.logFeatureState("WeChat cover layout", compact ? "SUCCESS" : "RESTORED",
                    "rotation=" + getConfigurationDisplayRotation(service));
        }
        if (compact) {
            View normalMenu = findByClassName(decor,
                    "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingRecyclerView");
            if (normalMenu != null) restoreNormalWidthScale(normalMenu);
            View normalKeyboard = findKeyboard(decor);
            if (normalKeyboard != null) {
                restoreNormalWidthScale(normalKeyboard);
                restoreNormalKeyText(normalKeyboard);
                restoreNormalKeyRects(normalKeyboard);
                restoreNormalPunctuation(normalKeyboard);
            }
            if (!Boolean.TRUE.equals(wasCompact)) {
                if (normalKeyboard != null) restoreNineKeyRows(normalKeyboard);
                configureWindowForCompactDisplay(service);
                installLayoutRefresher(service, decor);
            }
            schedule(service, decor, 0);
        } else {
            if (!isNormalCover(service)) {
                View normalKeyboard = findKeyboard(decor);
                if (normalKeyboard != null) restoreNormalWidthScale(normalKeyboard);
            }
            if (Boolean.TRUE.equals(wasCompact)) {
                restoreWindowForNormalDisplay(service);
                View keyboard = findKeyboard(decor);
                View candidate = findByClassName(decor, CANDIDATE_VIEW_CLASS);
                restorePunctuationRows();
                if (keyboard == null) {
                    compactKeyboardWidth = 0;
                    compactT9MetricWidth = 0;
                    new Handler(service.getMainLooper()).postDelayed(() -> {
                        if (isCompactCover(service)) return;
                        View currentKeyboard = findKeyboard(decor);
                        if (currentKeyboard == null) return;
                        resetNativeKeyboardCaches(currentKeyboard, service);
                        restoreKeyboard(currentKeyboard);
                    }, 400L);
                    disableNativeWidthHooks();
                    return;
                }
                KeyboardBaseline baseline = KEYBOARD_BASELINES.get(keyboard);
                if (baseline == null) {
                    baseline = KEYBOARD_TYPE_BASELINES.get(getKeyboardType(keyboard));
                }
                int nativeWidth = baseline != null && baseline.nativeWidth > 0
                        ? baseline.nativeWidth : keyboard.getWidth();
                int nativeT9Width = baseline != null && baseline.nativeT9Width > 0
                        ? baseline.nativeT9Width : nativeWidth;
                compactKeyboardWidth = nativeWidth > 0 ? nativeWidth : decor.getWidth();
                compactT9MetricWidth = isNineKeyKeyboardType(getKeyboardType(keyboard))
                        ? nativeT9Width : 0;
                if (candidate != null) restoreLayout(candidate, keyboard);
                restoreKeyboard(keyboard);
                keyboard.requestLayout();
                normalWidthRefreshPending = true;
                new Handler(service.getMainLooper()).postDelayed(() -> {
                    if (isCompactCover(service)) {
                        normalWidthRefreshPending = false;
                        schedule(service, decor, 0);
                        return;
                    }
                    View currentKeyboard = findKeyboard(decor);
                    if (currentKeyboard != null) {
                        resetNativeKeyboardCaches(currentKeyboard, service);
                        restoreKeyboard(currentKeyboard);
                    }
                    normalWidthRefreshPending = false;
                    disableNativeWidthHooks();
                    compactKeyboardWidth = 0;
                    compactT9MetricWidth = 0;
                    View currentCandidate = findByClassName(decor, CANDIDATE_VIEW_CLASS);
                    if (currentCandidate != null) refreshCandidateLists(currentCandidate);
                    if (isNormalCover(service)) schedule(service, decor, 0);
                }, 500L);
            } else {
                if (!normalWidthRefreshPending && !isNormalCover(service)) {
                    compactKeyboardWidth = 0;
                    compactT9MetricWidth = 0;
                    disableNativeWidthHooks();
                }
                if (isNormalCover(service) && !normalWidthRefreshPending) {
                    schedule(service, decor, 0);
                } else {
                    restoreNativeLayoutAfterViewAttach(decor);
                }
            }
        }
    }

    private static void configureWindowForCompactDisplay(InputMethodService service) {
        try {
            Dialog dialog = service.getWindow();
            Window window = dialog == null ? null : dialog.getWindow();
            if (window == null) return;
            WindowManager.LayoutParams attrs = window.getAttributes();
            ORIGINAL_CUTOUT_MODES.putIfAbsent(window, attrs.layoutInDisplayCutoutMode);
            int desired = WindowManager.LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            if (attrs.layoutInDisplayCutoutMode != desired) {
                attrs.layoutInDisplayCutoutMode = desired;
                window.setAttributes(attrs);
            }
            configureEdgeToEdge(window, true);
        } catch (Throwable error) {
            ModuleLogger.logException("WeChat compact window setup failed", error);
        }
    }

    private static void restoreWindowForNormalDisplay(InputMethodService service) {
        try {
            Dialog dialog = service.getWindow();
            Window window = dialog == null ? null : dialog.getWindow();
            if (window == null) return;
            configureEdgeToEdge(window, false);
            Integer originalCutoutMode = ORIGINAL_CUTOUT_MODES.remove(window);
            if (originalCutoutMode != null) {
                WindowManager.LayoutParams attrs = window.getAttributes();
                if (attrs.layoutInDisplayCutoutMode != originalCutoutMode) {
                    attrs.layoutInDisplayCutoutMode = originalCutoutMode;
                    window.setAttributes(attrs);
                }
            }
        } catch (Throwable error) {
            ModuleLogger.logException("WeChat normal window restore failed", error);
        }
    }

    private static void adapt(InputMethodService service, View decor, int attempt) {
        View observed = findKeyboard(decor);
        if (observed != null) {
            updateKeepSymbols(observed, isNormalCover(service) || isCompactCover(service));
            String state = observed.getClass().getName() + "@"
                    + System.identityHashCode(observed) + " type=" + getKeyboardType(observed);
            if (!state.equals(lastObservedKeyboard)) {
                View previous = lastActiveKeyboard.get();
                lastActiveKeyboard = new WeakReference<>(observed);
                lastObservedKeyboard = state;
                ModuleLogger.info("Active WeChat keyboard changed: " + state);
                if (previous != null && previous != observed
                        && isRecreatableKeyboard(previous)
                        && isRecreatableKeyboard(observed)) {
                    scheduleInactiveKeyboardDisposal(service, decor, previous, observed);
                }
            }
        }
        if (isNormalCover(service)) {
            adaptNormalNineKey(service, decor, attempt);
            return;
        }
        if (!isCompactCover(service)) return;
        View keyboard = findKeyboard(decor);
        View candidate = findByClassName(decor, CANDIDATE_VIEW_CLASS);
        if (keyboard == null || candidate == null) {
            if (attempt == 0) {
                StringBuilder hierarchy = new StringBuilder();
                appendViewHierarchy(decor, hierarchy, 0, new int[]{100});
                ModuleLogger.warn("WeChat view lookup missing keyboard="
                        + (keyboard != null) + " candidate=" + (candidate != null)
                        + " hierarchy=" + hierarchy);
            }
            retry(service, decor, attempt, "keyboard or candidate panel not ready");
            return;
        }
        try {
            if (!isNineKeyKeyboardType(getKeyboardType(keyboard))) {
                restoreNineKeyRows(keyboard);
            }
            ensureKeyboardBaseline(keyboard);
            boolean paddingChanged = clearBottomPadding(keyboard);
            boolean requestedLayout = fillAvailableKeyboardHeight(candidate, keyboard)
                    || paddingChanged;
            alignRootWithParentBottom(candidate);
            if (requestedLayout || keyboard.getHeight() <= 0) {
                retry(service, decor, attempt, "waiting for keyboard remeasure");
                return;
            }
            if (!applyNativeKeyboardGeometry(keyboard)) {
                retry(service, decor, attempt, "keyboard dimensions not ready");
                return;
            }
            centerKeyboardContent(keyboard);
            if (isNineKeyKeyboardType(getKeyboardType(keyboard))) {
                addRotatedNineKeySpacing(keyboard, decor);
            }
            KeyboardBaseline applied = KEYBOARD_BASELINES.get(keyboard);
            if (applied != null) applied.geometrySignature = keyGeometrySignature(keyboard);
            fitVisiblePunctuationRows(keyboard);
            centerSymbolKeyboardPages(keyboard);
            keyboard.invalidate();
        } catch (Throwable error) {
            ModuleLogger.logExceptionThrottled("WeChat cover layout adjustment failed", error);
            retry(service, decor, attempt, "layout adaptation failed");
        }
    }

    private static void updateKeepSymbols(View keyboard, boolean cover) {
        try {
            Class<?> action = XposedHelpers.findClass("com.tencent.wetype.plugin.hld.key.d",
                    keyboard.getClass().getClassLoader());
            String type = getKeyboardType(keyboard);
            boolean symbols = cover && ("ChineseNumberSymbols".equals(type)
                    || "EnglishNumberSymbols".equals(type) || "Number".equals(type)
                    || "Symbols".equals(type));
            if (symbols) {
                if (savedKeepSymbols == null) {
                    savedKeepSymbols = XposedHelpers.getStaticBooleanField(action, "e");
                }
                XposedHelpers.setStaticBooleanField(action, "e", true);
            } else if (!symbols && savedKeepSymbols != null) {
                XposedHelpers.setStaticBooleanField(action, "e", savedKeepSymbols);
                savedKeepSymbols = null;
            }
            if (lastSymbolRetentionState == null
                    || lastSymbolRetentionState.booleanValue() != symbols) {
                lastSymbolRetentionState = symbols;
                ModuleLogger.logFeatureState("WeChat symbol keyboard state",
                        symbols ? "RETAINED" : "RESTORED", "keyboardType=" + type);
            }
        } catch (Throwable error) {
            ModuleLogger.logException("WeChat symbol keyboard state update failed", error);
        }
    }

    private static boolean isRecreatableKeyboard(View keyboard) {
        String type = getKeyboardType(keyboard);
        return type.contains("Qwerty") || isNineKeyKeyboardType(type);
    }

    private static void scheduleInactiveKeyboardDisposal(InputMethodService service, View decor,
            View oldKeyboard, View newKeyboard) {
        WeakReference<View> oldRef = new WeakReference<>(oldKeyboard);
        WeakReference<View> newRef = new WeakReference<>(newKeyboard);
        new Handler(service.getMainLooper()).postDelayed(() -> {
            View old = oldRef.get();
            View expected = newRef.get();
            if (old == null || expected == null || !decor.isAttachedToWindow()
                    || findKeyboard(decor) != expected || lastActiveKeyboard.get() != expected) return;
            try {
                Class<?> model = XposedHelpers.findClass(
                        "com.tencent.wetype.plugin.hld.model.N", old.getClass().getClassLoader());
                Object owner = XposedHelpers.getStaticObjectField(model, "a");
                Object value = XposedHelpers.getStaticObjectField(model, "e");
                if (!(value instanceof Map)) return;
                Map<?, ?> cache = (Map<?, ?>) value;
                Object key = null;
                for (Map.Entry<?, ?> entry : cache.entrySet()) {
                    if (entry.getValue() == old) { key = entry.getKey(); break; }
                }
                if (key == null) return;
                XposedHelpers.callMethod(owner, "e0", old);
                cache.remove(key);
                if (old.getParent() instanceof ViewGroup) {
                    ((ViewGroup) old.getParent()).removeView(old);
                }
                ModuleLogger.debug("Disposed inactive WeChat keyboard id=" + key
                        + " view=" + System.identityHashCode(old));
            } catch (Throwable error) {
                ModuleLogger.logException("WeChat inactive keyboard disposal failed", error);
            }
        }, 800L);
    }

    private static void adaptNormalNineKey(InputMethodService service, View decor, int attempt) {
        fitNormalSettingsMenu(decor);
        View keyboard = findKeyboard(decor);
        if (keyboard == null || keyboard.getWidth() <= 0 || keyboard.getHeight() <= 0) {
            retry(service, decor, attempt, "normal keyboard not ready");
            return;
        }
        View candidate = findByClassName(decor, CANDIDATE_VIEW_CLASS);
        clearBottomPadding(keyboard);
        boolean qwerty = getKeyboardType(keyboard).contains("Qwerty");
        compactKeyboardWidth = 0;
        compactT9MetricWidth = 0;
        disableNativeWidthHooks();
        try {
            if (qwerty || "Symbols".equals(getKeyboardType(keyboard))) {
                restoreNormalKeyRects(keyboard);
                fitNormalKeyboardWidth(keyboard);
                if (candidate != null) alignNormalKeyboardBottom(decor, candidate, keyboard);
                return;
            }
            if (!isNineKeyKeyboardType(getKeyboardType(keyboard))) {
                restoreNineKeyRows(keyboard);
            }
            restoreNormalWidthScale(keyboard);
            fitNormalKeyRects(keyboard);
            if (isNineKeyKeyboardType(getKeyboardType(keyboard))) {
                alignNormalPunctuation(keyboard);
                fitNormalNineKeyText(keyboard);
                addRotatedNineKeySpacing(keyboard, decor);
            }
            if (candidate != null) alignNormalKeyboardBottom(decor, candidate, keyboard);
        } catch (Throwable error) {
            ModuleLogger.logException("WeChat normal key fitting failed", error);
        }
    }

    private static final Map<View, Map<Object, Rect[]>> NORMAL_KEY_RECTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static void fitNormalKeyRects(View keyboard) throws Throwable {
        Map<?, ?> buttons = findKeyButtons(keyboard);
        if (buttons == null) return;
        Map<Object, Rect[]> previous = NORMAL_KEY_RECTS.get(keyboard);
        if (previous != null && previous.size() == buttons.size()
                && previous.keySet().containsAll(buttons.values())) {
            boolean unchanged = true;
            for (Map.Entry<Object, Rect[]> item : previous.entrySet()) {
                Rect current = (Rect) XposedHelpers.callMethod(item.getKey(), "t");
                Rect expected = item.getValue()[2];
                if (current.left != expected.left || current.right != expected.right) {
                    unchanged = false;
                    break;
                }
            }
            if (unchanged) return;
        }
        if (previous != null) restoreNormalKeyRects(keyboard);
        int available = ((View) keyboard.getParent()).getWidth();
        Rect visible = new Rect();
        if (keyboard.getGlobalVisibleRect(visible) && visible.width() > 0) {
            available = Math.min(available, visible.width());
        }
        int left = Integer.MAX_VALUE, right = Integer.MIN_VALUE;
        Map<Object, Rect[]> originals = new java.util.IdentityHashMap<>();
        for (Object button : buttons.values()) {
            Object draw = XposedHelpers.callMethod(button, "t");
            Object hit = XposedHelpers.callMethod(button, "F");
            if (!(draw instanceof Rect) || !(hit instanceof Rect)) continue;
            Rect d = (Rect) draw, h = (Rect) hit;
            if (d.width() <= 0 || h.width() <= 0) continue;
            originals.put(button, new Rect[]{new Rect(d), new Rect(h), new Rect(d), new Rect(h)});
            left = Math.min(left, Math.min(d.left, h.left));
            right = Math.max(right, Math.max(d.right, h.right));
        }
        if (available <= 0 || right <= left || originals.isEmpty()) return;
        int margin = Math.round(4 * keyboard.getResources().getDisplayMetrics().density);
        float scale = Math.min(1f, (available - 2f * margin) / (right - left));
        if (scale >= 1f) return;
        for (Map.Entry<Object, Rect[]> item : originals.entrySet()) {
            Rect draw = (Rect) XposedHelpers.callMethod(item.getKey(), "t");
            Rect hit = (Rect) XposedHelpers.callMethod(item.getKey(), "F");
            Rect d = item.getValue()[0], h = item.getValue()[1];
            draw.left = margin + Math.round((d.left - left) * scale);
            draw.right = margin + Math.round((d.right - left) * scale);
            hit.left = margin + Math.round((h.left - left) * scale);
            hit.right = margin + Math.round((h.right - left) * scale);
            item.getValue()[2].set(draw);
            item.getValue()[3].set(hit);
        }
        keyboard.setTranslationX(0);
        NORMAL_KEY_RECTS.put(keyboard, originals);
        keyboard.invalidate();
    }

    private static void restoreNormalKeyRects(View keyboard) {
        Map<Object, Rect[]> saved = NORMAL_KEY_RECTS.remove(keyboard);
        if (saved == null) return;
        for (Map.Entry<Object, Rect[]> item : saved.entrySet()) {
            try {
                Rect draw = (Rect) XposedHelpers.callMethod(item.getKey(), "t");
                Rect hit = (Rect) XposedHelpers.callMethod(item.getKey(), "F");
                Rect[] original = item.getValue();
                draw.left = original[0].left;
                draw.right = original[0].right;
                hit.left = original[1].left;
                hit.right = original[1].right;
            } catch (Throwable ignored) { }
        }
    }

    private static long keyGeometrySignature(View keyboard) {
        try {
            Map<?, ?> buttons = findKeyButtons(keyboard);
            if (buttons == null) return Long.MIN_VALUE;
            long signature = buttons.size();
            for (Object button : buttons.values()) {
                Object value = XposedHelpers.callMethod(button, "t");
                if (!(value instanceof Rect)) continue;
                Rect rect = (Rect) value;
                long part = System.identityHashCode(button);
                part = 31 * part + rect.left;
                part = 31 * part + rect.top;
                part = 31 * part + rect.right;
                part = 31 * part + rect.bottom;
                signature += part;
            }
            return signature;
        } catch (Throwable ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static final Map<View, float[]> NORMAL_PUNCTUATION_BASELINES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static void alignNormalPunctuation(View keyboard) throws Throwable {
        View symbol = findByClassName(keyboard, SYMBOL_SCROLL_VIEW_CLASS);
        if (symbol == null || symbol.getLayoutParams() == null) return;
        Map<?, ?> buttons = findKeyButtons(keyboard);
        if (buttons == null) return;
        Rect first = null;
        for (Object button : buttons.values()) {
            Object value = XposedHelpers.callMethod(button, "t");
            if (!(value instanceof Rect) || ((Rect) value).width() <= 0) continue;
            Rect rect = (Rect) value;
            if (first == null || rect.left < first.left) first = new Rect(rect);
        }
        if (first == null || first.width() <= 0 || symbol.getWidth() <= 0) return;
        ViewGroup.LayoutParams params = symbol.getLayoutParams();
        NORMAL_PUNCTUATION_BASELINES.putIfAbsent(symbol,
                new float[]{params.width, symbol.getTranslationX()});
        if (params.width != first.width()) {
            params.width = first.width();
            symbol.setLayoutParams(params);
        }
        int[] keyboardLocation = new int[2];
        int[] symbolLocation = new int[2];
        keyboard.getLocationOnScreen(keyboardLocation);
        symbol.getLocationOnScreen(symbolLocation);
        float target = symbol.getTranslationX() + keyboardLocation[0] + first.left - symbolLocation[0];
        if (Math.abs(target - symbol.getTranslationX()) > 0.5f) symbol.setTranslationX(target);
    }

    private static void restoreNormalPunctuation(View keyboard) {
        View symbol = findByClassName(keyboard, SYMBOL_SCROLL_VIEW_CLASS);
        if (symbol == null) return;
        float[] baseline = NORMAL_PUNCTUATION_BASELINES.remove(symbol);
        if (baseline == null) return;
        ViewGroup.LayoutParams params = symbol.getLayoutParams();
        if (params != null) {
            params.width = (int) baseline[0];
            symbol.setLayoutParams(params);
        }
        symbol.setTranslationX(baseline[1]);
    }

    private static final Map<View, Map<Object, int[]>> NORMAL_KEY_TEXT =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static void fitNormalNineKeyText(View keyboard) throws Throwable {
        Map<?, ?> buttons = findKeyButtons(keyboard);
        if (buttons == null) return;
        Map<Object, int[]> saved = NORMAL_KEY_TEXT.computeIfAbsent(keyboard,
                ignored -> new java.util.IdentityHashMap<>());
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float scale = 1f;
        for (Object button : buttons.values()) {
            Object value = XposedHelpers.callMethod(button, "t");
            if (!(value instanceof Rect)) continue;
            String text = (String) XposedHelpers.getObjectField(button, "p");
            if (text == null || text.length() < 3 || text.length() > 5) continue;
            int[] sizes = saved.get(button);
            int size = sizes != null ? sizes[0] : XposedHelpers.getIntField(button, "q");
            if (size <= 0) continue;
            paint.setTextSize(size);
            float measured = paint.measureText(text);
            if (measured > 0) scale = Math.min(scale, ((Rect) value).width() * 0.85f / measured);
        }
        scale = Math.max(0.65f, Math.min(1f, scale));
        for (Object button : buttons.values()) {
            int[] sizes = saved.get(button);
            if (sizes == null) {
                sizes = new int[]{XposedHelpers.getIntField(button, "q"),
                        XposedHelpers.getIntField(button, "r")};
                saved.put(button, sizes);
            }
            XposedHelpers.setIntField(button, "q", Math.round(sizes[0] * scale));
            XposedHelpers.setIntField(button, "r", Math.round(sizes[1] * scale));
        }
    }

    private static void restoreNormalKeyText(View keyboard) {
        Map<Object, int[]> saved = NORMAL_KEY_TEXT.remove(keyboard);
        if (saved == null) return;
        for (Map.Entry<Object, int[]> item : saved.entrySet()) {
            try {
                XposedHelpers.setIntField(item.getKey(), "q", item.getValue()[0]);
                XposedHelpers.setIntField(item.getKey(), "r", item.getValue()[1]);
            } catch (Throwable ignored) { }
        }
    }

    private static final Map<View, Map<Object, Rect>> ROTATED_SPACED_BUTTONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static Map<?, ?> findKeyButtons(View keyboard) throws Throwable {
        Map<?, ?> buttons = null;
        for (Class<?> type = keyboard.getClass(); type != null && buttons == null;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(keyboard);
                if (!(value instanceof Map) || ((Map<?, ?>) value).isEmpty()) continue;
                Object first = ((Map<?, ?>) value).values().iterator().next();
                if (first == null) continue;
                try {
                    if (XposedHelpers.callMethod(first, "t") instanceof Rect
                            && XposedHelpers.callMethod(first, "F") instanceof Rect) {
                        buttons = (Map<?, ?>) value;
                        break;
                    }
                } catch (Throwable ignored) { }
            }
        }
        return buttons;
    }

    private static final Map<View, Map<Object, Rect[]>> NINE_KEY_ROW_BASELINES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Discard our previous changes before accepting a partially rebuilt native layout. */
    private static void restoreNineKeyRows(View keyboard) {
        Map<Object, Rect[]> saved = NINE_KEY_ROW_BASELINES.remove(keyboard);
        if (saved == null) return;
        for (Map.Entry<Object, Rect[]> entry : saved.entrySet()) {
            try {
                Rect[] rects = entry.getValue();
                Rect hit = (Rect) XposedHelpers.callMethod(entry.getKey(), "F");
                Rect draw = (Rect) XposedHelpers.callMethod(entry.getKey(), "t");
                if (hit.top == rects[1].top && hit.bottom == rects[1].bottom) {
                    hit.top = rects[0].top;
                    hit.bottom = rects[0].bottom;
                }
                if (draw.top == rects[3].top && draw.bottom == rects[3].bottom) {
                    draw.top = rects[2].top;
                    draw.bottom = rects[2].bottom;
                }
            } catch (Throwable ignored) { }
        }
    }

    private static void addRotatedNineKeySpacing(View keyboard, View decor) throws Throwable {
        Map<?, ?> buttons = findKeyButtons(keyboard);
        if (buttons == null || buttons.isEmpty()) return;
        Map<Object, Rect[]> saved = NINE_KEY_ROW_BASELINES.get(keyboard);
        boolean unchanged = saved != null && saved.size() == buttons.size()
                && saved.keySet().containsAll(buttons.values());
        if (unchanged) {
            for (Object button : buttons.values()) {
                Rect hit = (Rect) XposedHelpers.callMethod(button, "F");
                Rect expected = saved.get(button)[1];
                if (hit.top != expected.top || hit.bottom != expected.bottom) {
                    unchanged = false; break;
                }
            }
        }
        if (!unchanged) {
            restoreNineKeyRows(keyboard);
            saved = new java.util.IdentityHashMap<>();
            for (Object button : buttons.values()) {
                Object hitValue = XposedHelpers.callMethod(button, "F");
                Object drawValue = XposedHelpers.callMethod(button, "t");
                if (hitValue instanceof Rect && drawValue instanceof Rect) {
                    Rect hit = (Rect) hitValue, draw = (Rect) drawValue;
                    saved.put(button, new Rect[]{new Rect(hit), new Rect(hit),
                            new Rect(draw), new Rect(draw)});
                }
            }
            NINE_KEY_ROW_BASELINES.put(keyboard, saved);
        }
        java.util.ArrayList<Integer> heights = new java.util.ArrayList<>();
        for (Rect[] rects : saved.values()) if (rects[0].height() > 0) heights.add(rects[0].height());
        if (heights.isEmpty()) return;
        Collections.sort(heights);
        int typicalHeight = heights.get(heights.size() / 2);
        java.util.ArrayList<Integer> centers = new java.util.ArrayList<>();
        for (Rect[] rects : saved.values()) {
            Rect hit = rects[0];
            if (hit.height() > typicalHeight * 3 / 2 || hit.height() <= 0) continue;
            int center = hit.centerY();
            boolean found = false;
            for (int existing : centers) if (Math.abs(existing - center) < typicalHeight / 4) {
                found = true; break;
            }
            if (!found) centers.add(center);
        }
        Collections.sort(centers);
        if (centers.size() != 4) {
            StringBuilder message = new StringBuilder("MixFlipCustom: invalid nine-key rows ")
                    .append(centers).append(" typical=").append(typicalHeight);
            for (Rect[] rects : saved.values()) message.append(" ").append(rects[0]);
            ModuleLogger.warn(message.toString());
            return;
        }
        int gap = Math.max(1, Math.round(2f * keyboard.getResources().getDisplayMetrics().density));
        int[] position = new int[2];
        int[] screen = new int[2];
        keyboard.getLocationOnScreen(position);
        decor.getLocationOnScreen(screen);
        int bottomLimit = Math.min(keyboard.getHeight(),
                screen[1] + decor.getHeight() - position[1] - gap);
        if (bottomLimit <= gap * 12) return;
        for (Map.Entry<Object, Rect[]> entry : saved.entrySet()) {
            Rect original = entry.getValue()[0];
            Rect draw = (Rect) XposedHelpers.callMethod(entry.getKey(), "t");
            Rect hit = (Rect) XposedHelpers.callMethod(entry.getKey(), "F");
            if (original.height() <= 0 || draw.width() <= 0) continue;
            int first = 0, last = centers.size() - 1;
            while (first < last && centers.get(first) < original.top) first++;
            while (last > first && centers.get(last) > original.bottom) last--;
            if (original.height() <= typicalHeight * 3 / 2) {
                int nearest = 0;
                for (int i = 1; i < centers.size(); i++) {
                    if (Math.abs(centers.get(i) - original.centerY())
                            < Math.abs(centers.get(nearest) - original.centerY())) nearest = i;
                }
                first = nearest;
                last = nearest;
            } else {
                // The tall punctuation key spans the first three rows. Native hit
                // rectangles can overlap the fourth row after a rebuild.
                first = 0;
                last = 2;
            }
            int top = Math.round(first * bottomLimit / 4f);
            int bottom = Math.round((last + 1) * bottomLimit / 4f);
            hit.top = top;
            hit.bottom = bottom;
            entry.getValue()[1].set(hit);
            draw.top = top + gap;
            draw.bottom = bottom - gap;
            entry.getValue()[3].set(draw);
        }
        keyboard.invalidate();
    }

    private static void refreshCandidateLists(View root) {
        if (root.getClass().getName().equals("androidx.recyclerview.widget.RecyclerView")) {
            try {
                Object adapter = XposedHelpers.callMethod(root, "getAdapter");
                if (adapter != null) XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
            } catch (Throwable ignored) { }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) refreshCandidateLists(group.getChildAt(i));
        }
    }

    private static void fitNormalSettingsMenu(View decor) {
        View menu = findByClassName(decor,
                "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingRecyclerView");
        if (menu == null || !(menu.getParent() instanceof View)) return;
        int available = ((View) menu.getParent()).getWidth();
        if (available <= 0) return;
        try {
            Class<?> metrics = XposedHelpers.findClass(
                    "com.tencent.wetype.plugin.hld.utils.m1", menu.getClass().getClassLoader());
            Object instance = null;
            for (Field field : metrics.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != metrics) continue;
                field.setAccessible(true);
                instance = field.get(null);
                if (instance != null) break;
            }
            if (instance == null) return;
            int nativeWidth = ((Number) XposedHelpers.callMethod(instance, "y1", (Object) null)).intValue();
            if (nativeWidth <= available) return;
            ViewGroup.LayoutParams params = menu.getLayoutParams();
            if (params == null) return;
            NORMAL_WIDTH_SCALES.putIfAbsent(menu, new float[]{params.width, menu.getScaleX(),
                    menu.getPivotX(), menu.getTranslationX()});
            if (params.width != nativeWidth) {
                params.width = nativeWidth;
                menu.setLayoutParams(params);
            }
            menu.setPivotX(0);
            menu.setScaleX(available / (float) nativeWidth);
            menu.setTranslationX(0);
        } catch (Throwable error) {
            ModuleLogger.logExceptionThrottled("WeChat menu fitting failed", error);
        }
    }

    private static void fitNormalKeyboardWidth(View keyboard) {
        ViewGroup.LayoutParams params = keyboard.getLayoutParams();
        if (params == null || !(keyboard.getParent() instanceof View)) return;
        int available = ((View) keyboard.getParent()).getWidth();
        Rect bounds = getKeyboardButtonBounds(keyboard);
        if (available <= 0 || bounds == null || bounds.right <= available) return;
        float[] baseline = NORMAL_WIDTH_SCALES.get(keyboard);
        if (baseline == null) {
            baseline = new float[]{params.width, keyboard.getScaleX(), keyboard.getPivotX(),
                    keyboard.getTranslationX()};
            NORMAL_WIDTH_SCALES.put(keyboard, baseline);
        }
        int nativeWidth = Math.max(bounds.right + bounds.left, keyboard.getWidth());
        if (params.width != nativeWidth) {
            params.width = nativeWidth;
            keyboard.setLayoutParams(params);
        }
        keyboard.setPivotX(0);
        keyboard.setScaleX(available / (float) nativeWidth);
        keyboard.setTranslationX(0);
    }

    private static void restoreNormalWidthScale(View keyboard) {
        float[] baseline = NORMAL_WIDTH_SCALES.remove(keyboard);
        if (baseline == null) return;
        ViewGroup.LayoutParams params = keyboard.getLayoutParams();
        if (params != null) {
            params.width = (int) baseline[0];
            keyboard.setLayoutParams(params);
        }
        keyboard.setScaleX(baseline[1]);
        keyboard.setPivotX(baseline[2]);
        keyboard.setTranslationX(baseline[3]);
    }

    private static void alignNormalKeyboardBottom(View decor, View candidate, View keyboard) {
        View content = candidate.getParent() instanceof View ? (View) candidate.getParent() : null;
        View frame = content != null && content.getParent() instanceof View
                ? (View) content.getParent() : null;
        View root = frame != null && frame.getParent() instanceof View
                ? (View) frame.getParent() : null;
        Rect bounds = getKeyboardButtonBounds(keyboard);
        if (root == null || bounds == null || decor.getHeight() <= 0) return;
        float original = ROOT_TRANSLATIONS.computeIfAbsent(root, View::getTranslationY);
        int[] keyboardLocation = new int[2];
        int[] decorLocation = new int[2];
        keyboard.getLocationOnScreen(keyboardLocation);
        decor.getLocationOnScreen(decorLocation);
        float bottomAtOriginal = keyboardLocation[1] + bounds.bottom
                - root.getTranslationY() + original;
        float margin = 8.0f * keyboard.getResources().getDisplayMetrics().density;
        float shift = Math.max(0.0f,
                decorLocation[1] + decor.getHeight() - margin - bottomAtOriginal);
        float target = original + Math.min(shift, 40.0f
                * keyboard.getResources().getDisplayMetrics().density);
        if (Math.abs(root.getTranslationY() - target) > 0.5f) {
            root.setTranslationY(target);
        }
    }

    private static boolean fillAvailableKeyboardHeight(View candidate, View keyboard) {
        if (!(candidate.getParent() instanceof ViewGroup)) return false;
        View content = (View) candidate.getParent();
        if (!(content.getParent() instanceof View)) return false;
        View contentFrame = (View) content.getParent();
        if (!(contentFrame.getParent() instanceof View)) return false;
        View imeRoot = (View) contentFrame.getParent();
        int availableRootHeight = imeRoot.getParent() instanceof View
                ? ((View) imeRoot.getParent()).getHeight() : 0;
        Integer rememberedHeight = AVAILABLE_ROOT_HEIGHTS.get(imeRoot);
        if (rememberedHeight == null && availableRootHeight > 0) {
            AVAILABLE_ROOT_HEIGHTS.put(imeRoot, availableRootHeight);
            rememberedHeight = availableRootHeight;
        }

        View keyboardHost = keyboard.getParent() instanceof View
                ? (View) keyboard.getParent() : null;
        View keyboardSlot = keyboardHost != null && keyboardHost.getParent() instanceof View
                ? (View) keyboardHost.getParent() : null;
        if (keyboardHost == null || keyboardSlot == null
                || !(keyboardSlot.getLayoutParams() instanceof LinearLayout.LayoutParams)) {
            return false;
        }

        int fullWidth = imeRoot.getWidth() > 0
                ? imeRoot.getWidth() : keyboard.getRootView().getWidth();
        int cameraWidth = getCutoutWidth(candidate, fullWidth);
        int arcDepth = cameraWidth > 0 && candidate.getHeight() > 0
                ? Math.min(Math.round(candidate.getHeight() * 0.60f),
                        Math.round(cameraWidth * 0.225f)) : 0;
        boolean changed = false;
        if (rememberedHeight == null || rememberedHeight <= 0) return false;
        int fittedRootHeight = Math.max(1, rememberedHeight - arcDepth);
        if (isNineKeyKeyboardType(getKeyboardType(keyboard))
                && keyboard.getRootView().getHeight() > 0) {
            fittedRootHeight = Math.max(1, Math.round(keyboard.getRootView().getHeight() * 0.60f));
        }
        changed |= setHeight(imeRoot, fittedRootHeight, null);
        changed |= setHeight(contentFrame, fittedRootHeight, null);
        changed |= setHeight(content, fittedRootHeight, null);
        changed |= setWidthAndGravity(candidate, fullWidth, Gravity.LEFT);
        if (cameraWidth > 0 && arcDepth > 0) {
            changed |= applyCandidateArc(candidate, cameraWidth, arcDepth);
        }
        changed |= setHeight(keyboardSlot, 0, 1.0f);
        changed |= setWidthAndGravity(keyboardSlot, fullWidth, Gravity.LEFT);
        changed |= setHeight(keyboardHost, ViewGroup.LayoutParams.MATCH_PARENT, null);
        changed |= setWidthAndGravity(keyboardHost,
                fullWidth, Gravity.LEFT);
        changed |= setHeight(keyboard, ViewGroup.LayoutParams.MATCH_PARENT, null);
        changed |= setWidthAndGravity(keyboard, fullWidth, Gravity.LEFT);
        if (changed) {
            imeRoot.requestLayout();
            contentFrame.requestLayout();
            content.requestLayout();
        }
        return changed;
    }

    private static int getCutoutWidth(View view, int fullWidth) {
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null || fullWidth <= 0) return 0;
        int width = 0;
        List<Rect> cutouts = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 35) {
            cutouts.addAll(insets.getBoundingRects(WindowInsets.Type.displayCutout()));
        } else if (insets.getDisplayCutout() != null) {
            cutouts.addAll(insets.getDisplayCutout().getBoundingRects());
        }
        for (Rect bounds : cutouts) {
            if (bounds.left <= 0 && bounds.right > 0) {
                width = Math.max(width, Math.min(fullWidth, bounds.right));
            } else if (bounds.right >= fullWidth && bounds.left < fullWidth) {
                width = Math.max(width, Math.min(fullWidth, fullWidth - bounds.left));
            }
        }
        if (width == 0 && Build.VERSION.SDK_INT >= 30) {
            Insets safe = insets.getInsets(WindowInsets.Type.displayCutout());
            width = Math.max(safe.left, safe.right);
        }
        return Math.min(width, fullWidth);
    }

    private static boolean applyCandidateArc(View candidate, int cutoutWidth, int arcDepth) {
        int[] candidateLocation = new int[2];
        candidate.getLocationOnScreen(candidateLocation);
        boolean changed = false;
        for (View node = candidate; node != null;
                node = node.getParent() instanceof View ? (View) node.getParent() : null) {
            Drawable background = node.getBackground();
            if (background != null) {
                int[] nodeLocation = new int[2];
                node.getLocationOnScreen(nodeLocation);
                changed |= applyArcToBackground(node,
                        candidateLocation[0] - nodeLocation[0],
                        candidateLocation[1] - nodeLocation[1], cutoutWidth, arcDepth);
            }
        }
        int[] original = ORIGINAL_PADDING.computeIfAbsent(candidate, view -> new int[]{
                view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(),
                view.getPaddingBottom()});
        int leftPadding = original[0] + cutoutWidth;
        if (candidate.getPaddingLeft() != leftPadding) {
            candidate.setPadding(leftPadding, original[1], original[2], original[3]);
            changed = true;
        }
        return changed;
    }

    private static boolean applyArcToBackground(View view, int notchLeft, int notchTop,
            int notchWidth, int notchDepth) {
        ArcNotchDrawable arc = ARC_NOTCH_BACKGROUNDS.get(view);
        if (arc == null) {
            arc = new ArcNotchDrawable(view.getBackground());
            ARC_NOTCH_BACKGROUNDS.put(view, arc);
            view.setBackground(arc);
        }
        return arc.setGeometry(notchLeft, notchTop, notchWidth, notchDepth);
    }

    private static void alignRootWithParentBottom(View candidate) {
        View content = candidate.getParent() instanceof View ? (View) candidate.getParent() : null;
        View contentFrame = content != null && content.getParent() instanceof View
                ? (View) content.getParent() : null;
        View imeRoot = contentFrame != null && contentFrame.getParent() instanceof View
                ? (View) contentFrame.getParent() : null;
        if (imeRoot == null || !(imeRoot.getParent() instanceof View)) return;
        View parent = (View) imeRoot.getParent();
        float original = ROOT_TRANSLATIONS.computeIfAbsent(imeRoot, View::getTranslationY);
        int[] rootLocation = new int[2];
        int[] parentLocation = new int[2];
        imeRoot.getLocationOnScreen(rootLocation);
        parent.getLocationOnScreen(parentLocation);
        float layoutTop = rootLocation[1] - imeRoot.getTranslationY();
        float targetTranslation = original + parentLocation[1] + parent.getHeight()
                - (layoutTop + imeRoot.getHeight());
        if (Math.abs(imeRoot.getTranslationY() - targetTranslation) > 0.5f) {
            imeRoot.setTranslationY(targetTranslation);
        }
    }

    private static boolean setHeight(View view, int height, Float weight) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) return false;
        rememberLayoutBaseline(view, params);
        boolean changed = params.height != height;
        params.height = height;
        if (weight != null && params instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams linear = (LinearLayout.LayoutParams) params;
            changed |= linear.weight != weight;
            linear.weight = weight;
        }
        if (changed) view.setLayoutParams(params);
        return changed;
    }

    private static boolean setWidthAndGravity(View view, int width, int gravity) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) return false;
        rememberLayoutBaseline(view, params);
        boolean changed = params.width != width;
        params.width = width;
        if (params instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams linear = (LinearLayout.LayoutParams) params;
            changed |= linear.gravity != gravity;
            linear.gravity = gravity;
        }
        if (changed) view.setLayoutParams(params);
        return changed;
    }

    private static void rememberLayoutBaseline(View view, ViewGroup.LayoutParams params) {
        if (ORIGINAL_LAYOUTS.containsKey(view)) return;
        float weight = params instanceof LinearLayout.LayoutParams
                ? ((LinearLayout.LayoutParams) params).weight : 0.0f;
        int gravity = params instanceof LinearLayout.LayoutParams
                ? ((LinearLayout.LayoutParams) params).gravity : Gravity.NO_GRAVITY;
        ORIGINAL_LAYOUTS.put(view, new LayoutBaseline(params.width, params.height, weight,
                gravity));
    }

    private static boolean clearBottomPadding(View start) {
        boolean changed = false;
        for (View node = start; node != null; node = node.getParent() instanceof View
                ? (View) node.getParent() : null) {
            int bottom = node.getPaddingBottom();
            if (bottom <= 0) continue;
            ORIGINAL_PADDING.putIfAbsent(node, new int[]{node.getPaddingLeft(),
                    node.getPaddingTop(), node.getPaddingRight(), bottom});
            node.setPadding(node.getPaddingLeft(), node.getPaddingTop(),
                    node.getPaddingRight(), 0);
            changed = true;
        }
        return changed;
    }

    private static void restoreLayout(View candidate, View keyboard) {
        View content = candidate.getParent() instanceof View
                ? (View) candidate.getParent() : null;
        View contentFrame = content != null && content.getParent() instanceof View
                ? (View) content.getParent() : null;
        View imeRoot = contentFrame != null && contentFrame.getParent() instanceof View
                ? (View) contentFrame.getParent() : null;
        if (imeRoot != null) restoreHeight(imeRoot);
        if (imeRoot != null) AVAILABLE_ROOT_HEIGHTS.remove(imeRoot);
        if (imeRoot != null) {
            Float originalTranslation = ROOT_TRANSLATIONS.remove(imeRoot);
            if (originalTranslation != null) imeRoot.setTranslationY(originalTranslation);
        }
        if (contentFrame != null) restoreHeight(contentFrame);
        if (content != null) restoreHeight(content);

        View keyboardHost = keyboard.getParent() instanceof View
                ? (View) keyboard.getParent() : null;
        View keyboardSlot = keyboardHost != null && keyboardHost.getParent() instanceof View
                ? (View) keyboardHost.getParent() : null;
        if (keyboardSlot != null) restoreHeight(keyboardSlot);
        if (keyboardHost != null) restoreHeight(keyboardHost);
        restoreHeight(keyboard);

        View symbolView = findByClassName(keyboard, SYMBOL_SCROLL_VIEW_CLASS);
        if (symbolView != null) {
            restoreHeight(symbolView);
            try {
                Object recycler = XposedHelpers.callMethod(symbolView, "getScrollContainerRv");
                if (recycler instanceof View) {
                    View symbolGrid = (View) recycler;
                    Float originalTranslation = SYMBOL_TRANSLATIONS.remove(symbolGrid);
                    if (originalTranslation != null) {
                        symbolGrid.setTranslationX(originalTranslation);
                    }
                }
            } catch (Throwable ignored) {
                // Some keyboard modes have no symbol grid attached.
            }
        }
        restorePunctuationRows();
        View symbolPager = findByClassName(keyboard,
                "com.tencent.wetype.plugin.hld.symbol.ImeSymbolPagerView");
        if (symbolPager instanceof ViewGroup) {
            ViewGroup pages = (ViewGroup) symbolPager;
            for (int index = 0; index < pages.getChildCount(); index++) {
                Float original = SYMBOL_TRANSLATIONS.remove(pages.getChildAt(index));
                if (original != null) pages.getChildAt(index).setTranslationX(original);
            }
        }

        for (View node = keyboard; node != null; node = node.getParent() instanceof View
                ? (View) node.getParent() : null) {
            int[] padding = ORIGINAL_PADDING.remove(node);
            if (padding != null) node.setPadding(padding[0], padding[1], padding[2], padding[3]);
        }
        int[] candidatePadding = ORIGINAL_PADDING.remove(candidate);
        if (candidatePadding != null) {
            candidate.setPadding(candidatePadding[0], candidatePadding[1],
                    candidatePadding[2], candidatePadding[3]);
        }
        for (View node = candidate; node != null;
                node = node.getParent() instanceof View ? (View) node.getParent() : null) {
            ArcNotchDrawable arc = ARC_NOTCH_BACKGROUNDS.remove(node);
            if (arc != null) node.setBackground(arc.getOriginal());
        }
    }

    private static void restoreHeight(View view) {
        LayoutBaseline baseline = ORIGINAL_LAYOUTS.remove(view);
        if (baseline == null || view.getLayoutParams() == null) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        params.width = baseline.width;
        params.height = baseline.height;
        if (params instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams linear = (LinearLayout.LayoutParams) params;
            linear.weight = baseline.weight;
            linear.gravity = baseline.gravity;
        }
        view.setLayoutParams(params);
    }

    private static void ensureKeyboardBaseline(View keyboard) throws Throwable {
        observeKeyboardDataUpdates(keyboard);
        String type = String.valueOf(XposedHelpers.callMethod(keyboard, "getKeyboardType"));
        KeyboardBaseline current = KEYBOARD_BASELINES.get(keyboard);
        if (current != null && current.type.equals(type)) return;

        Object data = XposedHelpers.callMethod(keyboard, "getOriginData");

        Object originalData = XposedHelpers.callMethod(data, "deepCopy");
        Object config = XposedHelpers.callMethod(originalData, "getConfig");
        int nativeWidth = readNativeKeyboardWidthMetric(keyboard, "I0");
        int nativeT9Width = readNativeKeyboardWidthMetric(keyboard, "y1");
        KeyboardBaseline baseline = new KeyboardBaseline(originalData, type,
                copyFloatList(XposedHelpers.callMethod(config, "getLineHeight")),
                copyFloatList(XposedHelpers.callMethod(config, "getLineGapHeights")),
                copyIntegerList(XposedHelpers.callMethod(config, "getLineAlign")),
                ((Number) XposedHelpers.callMethod(config, "getVHeight")).floatValue(),
                ((Number) XposedHelpers.callMethod(config, "getLineGapHeight")).intValue(),
                keyboard.getHeight(), keyboard.getTranslationX(),
                keyboard.getResources().getDisplayMetrics().density,
                nativeWidth, nativeT9Width);
        ModuleLogger.debug("Captured native keyboard metrics type=" + type
                + " width=" + nativeWidth + " t9=" + nativeT9Width
                + " viewWidth=" + keyboard.getWidth());
        KEYBOARD_BASELINES.put(keyboard, baseline);
    }

    private static boolean applyNativeKeyboardGeometry(View keyboard) throws Throwable {
        KeyboardBaseline baseline = KEYBOARD_BASELINES.get(keyboard);
        if (baseline == null || baseline.height <= 0 || keyboard.getHeight() <= 0) return false;
        int availableWidth = keyboard.getWidth() > 0
                ? keyboard.getWidth() : keyboard.getRootView().getWidth();
        compactKeyboardWidth = availableWidth;
        ensureNativeWidthHook(keyboard);
        float scale = keyboard.getHeight() / (float) baseline.height;
        if (Math.abs(scale - baseline.appliedScale) < 0.01f
                && availableWidth == baseline.appliedWidth
                && baseline.geometrySignature == keyGeometrySignature(keyboard)) {
            compactT9MetricWidth = baseline.appliedMetricWidth;
            return true;
        }
        compactT9MetricWidth = isNineKeyKeyboardType(baseline.type)
                ? Math.round(availableWidth * 1.06f) : 0;

        resetNativeKeyboardCaches(keyboard, keyboard.getContext());
        Object data = XposedHelpers.callMethod(baseline.data, "deepCopy");
        Object config = XposedHelpers.callMethod(data, "getConfig");
        configureKeyboardData(config, baseline, scale);
        clearIndependentWidthScaleCache(keyboard);
        updateKeyboardData(keyboard, data);
        float fillScale = getBottomFillScale(keyboard);
        float appliedHeightScale = scale;
        if (Math.abs(fillScale - 1.0f) > 0.01f
                || isNineKeyKeyboardType(baseline.type)) {
            data = XposedHelpers.callMethod(baseline.data, "deepCopy");
            config = XposedHelpers.callMethod(data, "getConfig");
            float nineKeyScale = keyboard.getHeight() > 620 ? 0.62f : 0.88f;
            appliedHeightScale = scale * fillScale
                    * (isNineKeyKeyboardType(baseline.type) ? nineKeyScale : 1.0f);
            configureKeyboardData(config, baseline, appliedHeightScale);
            clearIndependentWidthScaleCache(keyboard);
            updateKeyboardData(keyboard, data);
        }
        if (isNineKeyKeyboardType(baseline.type)) {
            int targetWidth = Math.round(availableWidth * 0.92f);
            for (int attempt = 0; attempt < 6; attempt++) {
                Rect bounds = getKeyboardButtonBounds(keyboard);
                if (bounds == null || bounds.width() <= targetWidth) break;
                int adjusted = Math.max(1, Math.round(compactT9MetricWidth
                        * targetWidth / (float) bounds.width()));
                if (adjusted >= compactT9MetricWidth) break;
                compactT9MetricWidth = adjusted;
                data = XposedHelpers.callMethod(baseline.data, "deepCopy");
                config = XposedHelpers.callMethod(data, "getConfig");
                configureKeyboardData(config, baseline, appliedHeightScale);
                clearIndependentWidthScaleCache(keyboard);
                updateKeyboardData(keyboard, data);
            }
        }
        ROTATED_SPACED_BUTTONS.remove(keyboard);
        baseline.appliedScale = scale;
        baseline.appliedWidth = availableWidth;
        baseline.appliedMetricWidth = compactT9MetricWidth;
        if (isNineKeyKeyboardType(baseline.type)) {
            ModuleLogger.debug("WeChat T9 geometry height=" + keyboard.getHeight()
                    + " bounds=" + getKeyboardButtonBounds(keyboard) + " scale="
                    + appliedHeightScale + " lineHeights=" + baseline.lineHeight
                    + " gaps=" + scaledLineGapHeights(baseline, appliedHeightScale));
        }
        return true;
    }

    private static void centerKeyboardContent(View keyboard) throws Throwable {
        KeyboardBaseline baseline = KEYBOARD_BASELINES.get(keyboard);
        if (baseline == null || keyboard.getWidth() <= 0) return;
        Rect bounds = getKeyboardButtonBounds(keyboard);
        if (bounds == null || bounds.width() <= 0) return;
        float offset = (keyboard.getWidth() - bounds.width()) / 2.0f - bounds.left;
        float targetTranslation = baseline.translationX + offset;
        if (Math.abs(keyboard.getTranslationX() - targetTranslation) > 0.5f) {
            keyboard.setTranslationX(targetTranslation);
        }
    }

    private static void fitVisiblePunctuationRows(View keyboard) throws Throwable {
        View symbolView = findByClassName(keyboard, SYMBOL_SCROLL_VIEW_CLASS);
        if (symbolView == null) return;
        if (isNineKeyKeyboardType(getKeyboardType(keyboard)) && keyboard.getHeight() > 0) {
            int targetHeight = Math.round(keyboard.getHeight() * 0.75f);
            if (setHeight(symbolView, targetHeight, null)) return;
        }
        if (symbolView.getHeight() <= 0) return;

        Object recycler = XposedHelpers.callMethod(symbolView, "getScrollContainerRv");
        if (!(recycler instanceof ViewGroup)) return;
        ViewGroup list = (ViewGroup) recycler;
        int availableHeight = list.getHeight() - list.getPaddingTop() - list.getPaddingBottom();
        if (availableHeight <= 0) return;

        Object adapter = XposedHelpers.callMethod(symbolView, "getAdapter");
        Object data = XposedHelpers.callMethod(adapter, "getDataList");
        if (data instanceof List && !((List<?>) data).isEmpty()
                && ((List<?>) data).get(0) instanceof String) {
            // The native list uses a fixed 68dp item height, which clips its fourth
            // punctuation row on the shorter cover-screen keyboard.
            int itemHeight = Math.max(1, availableHeight / 4);
            SymbolRowBaseline baseline = SYMBOL_ROW_BASELINES.get(adapter);
            if (baseline == null) {
                baseline = new SymbolRowBaseline(getNativeSymbolItemHeight(list), list);
                SYMBOL_ROW_BASELINES.put(adapter, baseline);
                for (int index = 0; index < list.getChildCount(); index++) {
                    View item = list.getChildAt(index);
                    ViewGroup.LayoutParams params = item.getLayoutParams();
                    if (params != null) {
                        SYMBOL_ITEM_LAYOUT_HEIGHTS.put(item, params.height);
                    }
                }
            } else {
                baseline.list = new WeakReference<>(list);
            }
            XposedHelpers.callMethod(adapter, "k", itemHeight);

            boolean changed = false;
            for (int index = 0; index < list.getChildCount(); index++) {
                View item = list.getChildAt(index);
                ViewGroup.LayoutParams params = item.getLayoutParams();
                if (params != null && params.height != itemHeight) {
                    params.height = itemHeight;
                    item.setLayoutParams(params);
                    changed = true;
                }
            }
            if (changed) list.requestLayout();
        }
        centerSymbolGrid(keyboard, list);
    }

    private static int getNativeSymbolItemHeight(ViewGroup list) {
        for (int index = 0; index < list.getChildCount(); index++) {
            View item = list.getChildAt(index);
            ViewGroup.LayoutParams params = item.getLayoutParams();
            if (params != null && params.height > 0) return params.height;
            if (item.getHeight() > 0) return item.getHeight();
        }
        return Math.max(1, Math.round(68.0f
                * list.getResources().getDisplayMetrics().density));
    }

    private static void restorePunctuationRows() {
        List<Map.Entry<Object, SymbolRowBaseline>> baselines;
        synchronized (SYMBOL_ROW_BASELINES) {
            baselines = new ArrayList<>(SYMBOL_ROW_BASELINES.entrySet());
            SYMBOL_ROW_BASELINES.clear();
        }
        for (Map.Entry<Object, SymbolRowBaseline> entry : baselines) {
            Object adapter = entry.getKey();
            SymbolRowBaseline baseline = entry.getValue();
            if (adapter == null || baseline == null) continue;
            try {
                XposedHelpers.callMethod(adapter, "k", baseline.itemHeight);
                ViewGroup list = baseline.list.get();
                if (list == null) continue;
                boolean changed = false;
                for (int index = 0; index < list.getChildCount(); index++) {
                    View item = list.getChildAt(index);
                    ViewGroup.LayoutParams params = item.getLayoutParams();
                    if (params == null) continue;
                    Integer originalHeight = SYMBOL_ITEM_LAYOUT_HEIGHTS.remove(item);
                    int restoredHeight = originalHeight == null
                            ? baseline.itemHeight : originalHeight;
                    if (params.height != restoredHeight) {
                        params.height = restoredHeight;
                        item.setLayoutParams(params);
                        changed = true;
                    }
                }
                if (changed) list.requestLayout();
            } catch (Throwable error) {
                ModuleLogger.logException("WeChat punctuation row restore failed", error);
            }
        }
    }

    /** WeChat 4.0 renders the full symbol page in its own self-drawn keyboard view. */
    private static void centerSymbolKeyboardPages(View keyboard) {
        View pager = findByClassName(keyboard,
                "com.tencent.wetype.plugin.hld.symbol.ImeSymbolPagerView");
        if (!(pager instanceof ViewGroup)) return;
        ViewGroup pages = (ViewGroup) pager;
        int[] pagerLocation = new int[2];
        pager.getLocationOnScreen(pagerLocation);
        for (int index = 0; index < pages.getChildCount(); index++) {
            View page = pages.getChildAt(index);
            if (!"com.tencent.wetype.plugin.hld.symbol.ImeSymbolKeyboardView"
                    .equals(page.getClass().getName()) || page.getWidth() <= 0) continue;
            int[] pageLocation = new int[2];
            page.getLocationOnScreen(pageLocation);
            int visibleWidth = Math.min(pagerLocation[0] + pager.getWidth(),
                    pageLocation[0] + page.getWidth())
                    - Math.max(pagerLocation[0], pageLocation[0]);
            if (visibleWidth < pager.getWidth() * 0.65f) continue;

            Rect bounds = getKeyboardButtonBounds(page);
            if (bounds == null || bounds.width() <= 0
                    || bounds.width() > page.getWidth() * 1.05f) continue;
            float originalTranslation = SYMBOL_TRANSLATIONS.computeIfAbsent(
                    page, View::getTranslationX);
            float targetTranslation = originalTranslation
                    + (page.getWidth() - bounds.width()) / 2.0f - bounds.left;
            if (Math.abs(page.getTranslationX() - targetTranslation) > 0.5f) {
                page.setTranslationX(targetTranslation);
            }
        }
    }

    private static void centerSymbolGrid(View keyboard, ViewGroup list) {
        if (keyboard.getWidth() <= 0 || list.getChildCount() == 0) return;
        if (list.getWidth() < keyboard.getWidth() * 0.65f) {
            Float originalTranslation = SYMBOL_TRANSLATIONS.remove(list);
            if (originalTranslation != null) list.setTranslationX(originalTranslation);
            return;
        }
        int[] keyboardLocation = new int[2];
        keyboard.getLocationOnScreen(keyboardLocation);
        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        for (int index = 0; index < list.getChildCount(); index++) {
            View item = list.getChildAt(index);
            if (item.getVisibility() != View.VISIBLE || item.getWidth() <= 0) continue;
            int[] itemLocation = new int[2];
            item.getLocationOnScreen(itemLocation);
            left = Math.min(left, itemLocation[0]);
            right = Math.max(right, itemLocation[0] + item.getWidth());
        }
        if (left == Integer.MAX_VALUE || right <= left) return;

        float originalTranslation = SYMBOL_TRANSLATIONS.computeIfAbsent(
                list, View::getTranslationX);
        float appliedTranslation = list.getTranslationX() - originalTranslation;
        float baseLeft = left - keyboardLocation[0] - appliedTranslation;
        float contentWidth = right - left;
        float targetTranslation = originalTranslation
                + (keyboard.getWidth() - contentWidth) / 2.0f - baseLeft;
        if (Math.abs(list.getTranslationX() - targetTranslation) > 0.5f) {
            list.setTranslationX(targetTranslation);
        }
    }

    private static void configureKeyboardData(Object config, KeyboardBaseline baseline,
            float scale) throws Throwable {
        XposedHelpers.callMethod(config, "setLineHeight", scaledList(baseline.lineHeight, scale));
        XposedHelpers.callMethod(config, "setLineGapHeights",
                scaledLineGapHeights(baseline, scale));
        XposedHelpers.callMethod(config, "setLineAlign",
                new ArrayList<>(baseline.lineAlign));
        XposedHelpers.callMethod(config, "setVHeight", Float.valueOf(baseline.vHeight * scale));
        XposedHelpers.callMethod(config, "setLineGapHeight",
                Integer.valueOf(Math.round(baseline.lineGapHeight * scale)));
        XposedHelpers.callMethod(config, "setGravity", Integer.valueOf(Gravity.LEFT));
    }

    private static List<Float> scaledLineGapHeights(KeyboardBaseline baseline, float scale) {
        List<Float> result = scaledList(baseline.lineGapHeights, scale);
        if (isNineKeyKeyboardType(baseline.type)) {
            float extraGap = Math.round(11.0f * baseline.density);
            for (int index = 0; index < result.size(); index++) {
                result.set(index, result.get(index) + extraGap);
            }
        }
        return result;
    }

    private static float getBottomFillScale(View keyboard) {
        try {
            Rect bounds = getKeyboardButtonBounds(keyboard);
            if (bounds == null) return 1.0f;
            int usedHeight = bounds.bottom;
            KeyboardBaseline baseline = KEYBOARD_BASELINES.get(keyboard);
            boolean nineKey = baseline != null && isNineKeyKeyboardType(baseline.type);
            if (usedHeight <= 0 || (usedHeight >= keyboard.getHeight() && !nineKey)) {
                return 1.0f;
            }
            int bottomMargin = Math.round((nineKey ? 12.0f : 11.0f)
                    * keyboard.getResources().getDisplayMetrics().density);
            float fit = (keyboard.getHeight() - bottomMargin) / (float) usedHeight;
            return Math.max(0.75f, Math.min(1.15f, fit));
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    /** Read key bounds from either the legacy debug tree or WeChat 4.0 layout JSON. */
    private static Rect getKeyboardButtonBounds(View keyboard) {
        try {
            String tree = String.valueOf(XposedHelpers.callMethod(keyboard,
                    "getKeyboardViewTree"));
            Matcher matcher = KEYBOARD_BUTTON_BOUNDS.matcher(tree);
            int left = Integer.MAX_VALUE;
            int top = Integer.MAX_VALUE;
            int right = Integer.MIN_VALUE;
            int bottom = Integer.MIN_VALUE;
            while (matcher.find()) {
                left = Math.min(left, Integer.parseInt(matcher.group(1)));
                top = Math.min(top, Integer.parseInt(matcher.group(2)));
                right = Math.max(right, Integer.parseInt(matcher.group(3)));
                bottom = Math.max(bottom, Integer.parseInt(matcher.group(4)));
            }
            if (left != Integer.MAX_VALUE && right > left && bottom > top) {
                int[] location = new int[2];
                keyboard.getLocationOnScreen(location);
                return new Rect(left - location[0], top - location[1],
                        right - location[0], bottom - location[1]);
            }
        } catch (Throwable ignored) {
            // WeChat 4.0 removed the legacy keyboard debug-tree method.
        }

        try {
            String layout = String.valueOf(XposedHelpers.callMethod(keyboard,
                    "getLayoutInfo"));
            JSONArray keys = new JSONObject(layout).optJSONArray("keys");
            if (keys == null) return null;
            int left = Integer.MAX_VALUE;
            int top = Integer.MAX_VALUE;
            int right = Integer.MIN_VALUE;
            int bottom = Integer.MIN_VALUE;
            for (int index = 0; index < keys.length(); index++) {
                JSONObject key = keys.optJSONObject(index);
                JSONArray rect = key == null ? null : key.optJSONArray("drawRect");
                if (rect == null || rect.length() < 4) continue;
                left = Math.min(left, rect.optInt(0));
                top = Math.min(top, rect.optInt(1));
                right = Math.max(right, rect.optInt(2));
                bottom = Math.max(bottom, rect.optInt(3));
            }
            if (left != Integer.MAX_VALUE && right > left && bottom > top) {
                return new Rect(left, top, right, bottom);
            }
        } catch (Throwable error) {
            ModuleLogger.logExceptionThrottled("WeChat keyboard geometry reflection failed", error);
        }
        return null;
    }

    /** WeChat can rebuild key data without requesting a new Android view layout. */
    private static void observeKeyboardDataUpdates(View keyboard) {
        synchronized (OBSERVED_DATA_SETTERS) {
            for (Class<?> type = keyboard.getClass(); type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (parameters.length != 1
                            || !"KeyboardData".equals(parameters[0].getSimpleName())
                            || OBSERVED_DATA_SETTERS.containsKey(method)) continue;
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            if (Boolean.TRUE.equals(APPLYING_KEYBOARD_DATA.get())) return;
                            InputMethodService service = rotationService.get();
                            View decor = rotationDecor.get();
                            if (service == null || decor == null || !decor.isAttachedToWindow()) return;
                            if (findKeyboard(decor) == hook.thisObject
                                    && (isCompactCover(service) || isNormalCover(service))) {
                                schedule(service, decor, 0);
                            }
                        }
                    });
                    OBSERVED_DATA_SETTERS.put(method, true);
                }
            }
        }
    }

    private static void updateKeyboardData(View keyboard, Object data) throws Throwable {
        StringBuilder candidates = new StringBuilder();
        for (Class<?> type = keyboard.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 1) continue;
                Class<?> parameter = method.getParameterTypes()[0];
                if (!"KeyboardData".equals(parameter.getSimpleName())) continue;
                candidates.append(method.getName()).append('(')
                        .append(parameter.getName()).append('@')
                        .append(System.identityHashCode(parameter.getClassLoader()))
                        .append(", accepts=").append(parameter.isInstance(data)).append(");");
                if (!parameter.isInstance(data)) continue;
                method.setAccessible(true);
                try {
                    Boolean previous = APPLYING_KEYBOARD_DATA.get();
                    APPLYING_KEYBOARD_DATA.set(true);
                    try {
                        method.invoke(keyboard, data);
                    } finally {
                        if (previous == null) APPLYING_KEYBOARD_DATA.remove();
                        else APPLYING_KEYBOARD_DATA.set(previous);
                    }
                    return;
                } catch (java.lang.reflect.InvocationTargetException error) {
                    throw error.getCause() == null ? error : error.getCause();
                }
            }
        }
        StringBuilder hierarchy = new StringBuilder();
        for (Class<?> type = keyboard.getClass(); type != null; type = type.getSuperclass()) {
            if (hierarchy.length() > 0) hierarchy.append(" -> ");
            hierarchy.append(type.getName()).append('@')
                    .append(System.identityHashCode(type.getClassLoader()));
        }
        throw new NoSuchMethodException("WeChat keyboard data setter unavailable for "
                + keyboard.getClass().getName() + " and " + data.getClass().getName()
                + '@' + System.identityHashCode(data.getClass().getClassLoader())
                + " candidates=" + candidates + " hierarchy=" + hierarchy);
    }

    private static void ensureNativeWidthHook(View keyboard) throws Throwable {
        ClassLoader loader = keyboard.getClass().getClassLoader();
        synchronized (WIDTH_HOOKS) {
            if (WIDTH_HOOKS.containsKey(loader)) return;
            Class<?> metricClass = XposedHelpers.findClass(
                    "com.tencent.wetype.plugin.hld.utils.m1", loader);
            Class<?> keyboardType = XposedHelpers.callMethod(keyboard, "getKeyboardType")
                    .getClass();
            List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
            try {
                hooks.add(XposedHelpers.findAndHookMethod(metricClass, "I0", keyboardType,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam hook) {
                                int width = compactKeyboardWidth;
                                if (width > 0) hook.setResult(width);
                            }
                        }));
                hooks.add(XposedHelpers.findAndHookMethod(metricClass, "y1", keyboardType,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam hook) {
                                int width = compactKeyboardWidth;
                                if (width <= 0) return;
                                if (isNineKeyKeyboardType(hook.args[0])) {
                                    int targetWidth = compactT9MetricWidth > 0
                                            ? compactT9MetricWidth : Math.round(width * 1.06f);
                                    hook.setResult(targetWidth);
                                } else {
                                    hook.setResult(width);
                                }
                            }
                        }));
                WIDTH_HOOKS.put(loader, hooks);
                ModuleLogger.info("WeChat native keyboard width hooks enabled for "
                        + "rotated cover layout");
            } catch (Throwable error) {
                for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
                throw error;
            }
        }
    }

    private static void disableNativeWidthHooks() {
        synchronized (WIDTH_HOOKS) {
            for (List<XC_MethodHook.Unhook> hooks : WIDTH_HOOKS.values()) {
                for (XC_MethodHook.Unhook hook : hooks) {
                    try {
                        hook.unhook();
                    } catch (Throwable error) {
                        ModuleLogger.logException("WeChat native keyboard width hook removal failed", error);
                    }
                }
            }
            if (!WIDTH_HOOKS.isEmpty()) {
                WIDTH_HOOKS.clear();
                ModuleLogger.info("WeChat native keyboard width hooks disabled for "
                        + "normal layout");
            }
        }
    }

    private static boolean isNineKeyKeyboardType(Object keyboardType) {
        String type = String.valueOf(keyboardType);
        return type.contains("T9") || type.contains("Nine") || "Number".equals(type);
    }

    private static void clearIndependentWidthScaleCache(View keyboard) {
        try {
            Class<?> metricClass = XposedHelpers.findClass(
                    "com.tencent.wetype.plugin.hld.utils.m1",
                    keyboard.getClass().getClassLoader());
            int cleared = 0;
            for (Field field : metricClass.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object cache = field.get(null);
                    if (cache == null) continue;
                    for (Method method : cache.getClass().getDeclaredMethods()) {
                        Class<?>[] args = method.getParameterTypes();
                        if (!"g".equals(method.getName()) || args.length != 3
                                || args[0] != int.class || args[1] != boolean.class
                                || method.getReturnType() != double.class) continue;
                        XposedHelpers.callMethod(cache, "clear");
                        cleared++;
                        break;
                    }
                } catch (Throwable ignored) {
                    // Keep scanning: WeChat obfuscates the cache field name per release.
                }
            }
            if (cleared == 0) {
                ModuleLogger.debug("WeChat independent key-width cache was not found");
            }
        } catch (Throwable error) {
            ModuleLogger.logException("WeChat key-width cache refresh failed", error);
        }
    }


    private static void restoreKeyboard(View keyboard) {
        restoreNineKeyRows(keyboard);
        KeyboardBaseline baseline = KEYBOARD_BASELINES.remove(keyboard);
        if (baseline == null) {
            try {
                String type = String.valueOf(XposedHelpers.callMethod(keyboard,
                        "getKeyboardType"));
                baseline = KEYBOARD_TYPE_BASELINES.get(type);
            } catch (Throwable ignored) {
                // A fresh view may not have a keyboard type until it is attached.
            }
        }
        if (baseline == null) return;
        try {
            Object data = XposedHelpers.callMethod(baseline.data, "deepCopy");
            clearIndependentWidthScaleCache(keyboard);
            updateKeyboardData(keyboard, data);
            clearIndependentWidthScaleCache(keyboard);
            keyboard.setTranslationX(baseline.translationX);
            keyboard.requestLayout();
            keyboard.invalidate();
            baseline.appliedScale = -1.0f;
            baseline.appliedWidth = -1;
            baseline.appliedMetricWidth = -1;
            baseline.geometrySignature = Long.MIN_VALUE;
            NORMAL_KEY_RECTS.remove(keyboard);
            ROTATED_SPACED_BUTTONS.remove(keyboard);
        } catch (Throwable error) {
            ModuleLogger.logException("WeChat keyboard restore failed", error);
        }
    }

    private static List<Float> copyFloatList(Object values) {
        List<Float> result = new ArrayList<>();
        if (values instanceof List) {
            for (Object value : (List<?>) values) {
                result.add(value instanceof Number
                        ? ((Number) value).floatValue() : 0.0f);
            }
        }
        return result;
    }

    private static List<Float> scaledList(List<Float> values, float scale) {
        List<Float> result = new ArrayList<>(values.size());
        for (Float value : values) result.add(value * scale);
        return result;
    }

    private static List<Integer> copyIntegerList(Object values) {
        List<Integer> result = new ArrayList<>();
        if (values instanceof List) {
            for (Object value : (List<?>) values) {
                result.add(value instanceof Number ? ((Number) value).intValue() : 0);
            }
        }
        return result;
    }

    private static View findKeyboard(View view) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (KEYBOARD_VIEW_CLASS.equals(type.getName())
                    || KEYBOARD_BASE_VIEW_CLASS.equals(type.getName())) return view;
            if (type.getName().startsWith("com.tencent.wetype.plugin.hld.keyboard.")
                    && hasMethod(type, "getOriginData")
                    && hasMethod(type, "getKeyboardType")) return view;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View result = findKeyboard(group.getChildAt(index));
            if (result != null) return result;
        }
        return null;
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (name.equals(method.getName())) return true;
        }
        return false;
    }

    private static void appendViewHierarchy(View view, StringBuilder out, int depth,
            int[] remaining) {
        if (remaining[0] <= 0) return;
        remaining[0]--;
        if (out.length() > 0) out.append(';');
        out.append(depth).append(':').append(view.getClass().getName())
                .append('@').append(view.getWidth()).append('x').append(view.getHeight());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                appendViewHierarchy(group.getChildAt(index), out, depth + 1, remaining);
            }
        }
    }

    private static View findByClassName(View root, String className) {
        if (className.equals(root.getClass().getName())) return root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int index = 0; index < group.getChildCount(); index++) {
            View result = findByClassName(group.getChildAt(index), className);
            if (result != null) return result;
        }
        return null;
    }

    private static void retry(InputMethodService service, View decor, int attempt, String reason) {
        if (attempt >= 12) {
            ModuleLogger.warn("WeChat keyboard layout did not settle: " + reason);
            return;
        }
        schedule(service, decor, attempt + 1);
    }

    private static boolean isNormalCover(Context context) {
        if (context == null) return false;
        Configuration config = context.getResources().getConfiguration();
        int shortSide = Math.min(config.screenWidthDp, config.screenHeightDp);
        int longSide = Math.max(config.screenWidthDp, config.screenHeightDp);
        return config.orientation == Configuration.ORIENTATION_PORTRAIT
                && getConfigurationDisplayRotation(context) == Surface.ROTATION_0
                && shortSide > 0 && shortSide <= MAX_COMPACT_SHORT_SIDE_DP
                && longSide <= MAX_COMPACT_LONG_SIDE_DP;
    }

    private static boolean isCompactCover(Context context) {
        if (context == null) return false;
        Configuration config = context.getResources().getConfiguration();
        if (config.orientation != Configuration.ORIENTATION_PORTRAIT) return false;
        int rotation = getConfigurationDisplayRotation(context);
        if (rotation < 0) {
            Display display = getContextDisplay(context);
            rotation = display == null ? -1 : display.getRotation();
        }
        if (rotation != Surface.ROTATION_180) return false;
        int shortSide = Math.min(config.screenWidthDp, config.screenHeightDp);
        int longSide = Math.max(config.screenWidthDp, config.screenHeightDp);
        return shortSide > 0 && shortSide <= MAX_COMPACT_SHORT_SIDE_DP
                && longSide <= MAX_COMPACT_LONG_SIDE_DP;
    }

    private static final class LayoutBaseline {
        final int width;
        final int height;
        final float weight;
        final int gravity;

        LayoutBaseline(int width, int height, float weight, int gravity) {
            this.width = width;
            this.height = height;
            this.weight = weight;
            this.gravity = gravity;
        }
    }

    private static final class WindowBaseline {
        final int systemUiVisibility;
        final int navigationBarColor;
        final boolean navigationBarContrastEnforced;
        final View.OnApplyWindowInsetsListener onApplyWindowInsetsListener;

        WindowBaseline(int systemUiVisibility, int navigationBarColor,
                boolean navigationBarContrastEnforced,
                View.OnApplyWindowInsetsListener onApplyWindowInsetsListener) {
            this.systemUiVisibility = systemUiVisibility;
            this.navigationBarColor = navigationBarColor;
            this.navigationBarContrastEnforced = navigationBarContrastEnforced;
            this.onApplyWindowInsetsListener = onApplyWindowInsetsListener;
        }
    }

    private static final class SymbolRowBaseline {
        final int itemHeight;
        WeakReference<ViewGroup> list;

        SymbolRowBaseline(int itemHeight, ViewGroup list) {
            this.itemHeight = itemHeight;
            this.list = new WeakReference<>(list);
        }
    }

    private static final class ArcNotchDrawable extends Drawable {
        private final Drawable original;
        private int notchLeft;
        private int notchTop;
        private int notchWidth;
        private int notchDepth;

        ArcNotchDrawable(Drawable original) {
            this.original = original;
        }

        Drawable getOriginal() {
            return original;
        }

        boolean setGeometry(int left, int top, int width, int depth) {
            if (notchLeft == left && notchTop == top
                    && notchWidth == width && notchDepth == depth) return false;
            notchLeft = left;
            notchTop = top;
            notchWidth = width;
            notchDepth = depth;
            invalidateSelf();
            return true;
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            if (original == null || notchWidth <= 0 || notchDepth <= 0) {
                if (original != null) original.draw(canvas);
                return;
            }
            Rect bounds = getBounds();
            float left = bounds.left + notchLeft;
            float top = bounds.top + notchTop;
            float width = Math.min(notchWidth, Math.max(0, bounds.right - left));
            RectF cameraArc = new RectF(left, top - notchDepth,
                    left + width, top + notchDepth);
            Path visiblePanel = new Path();
            visiblePanel.addRect(new RectF(bounds), Path.Direction.CW);
            Path cutout = new Path();
            cutout.addOval(cameraArc, Path.Direction.CW);
            visiblePanel.op(cutout, Path.Op.DIFFERENCE);
            int save = canvas.save();
            canvas.clipPath(visiblePanel);
            original.setBounds(bounds);
            original.draw(canvas);
            canvas.restoreToCount(save);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            if (original != null) original.setBounds(bounds);
        }

        @Override
        public boolean getPadding(Rect padding) {
            return original != null && original.getPadding(padding);
        }

        @Override
        public int getIntrinsicWidth() {
            return original == null ? -1 : original.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return original == null ? -1 : original.getIntrinsicHeight();
        }

        @Override
        public int getOpacity() {
            return original == null ? android.graphics.PixelFormat.TRANSLUCENT
                    : original.getOpacity();
        }

        @Override
        public void setAlpha(int alpha) {
            if (original != null) original.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            if (original != null) original.setColorFilter(colorFilter);
        }

        @Override
        public boolean isStateful() {
            return original != null && original.isStateful();
        }

        @Override
        protected boolean onStateChange(int[] state) {
            return original != null && original.setState(state);
        }
    }

    private static final class KeyboardBaseline {
        final Object data;
        final String type;
        final List<Float> lineHeight;
        final List<Float> lineGapHeights;
        final List<Integer> lineAlign;
        final float vHeight;
        final int lineGapHeight;
        final int height;
        final float translationX;
        final float density;
        final int nativeWidth;
        final int nativeT9Width;
        float appliedScale = -1.0f;
        int appliedWidth = -1;
        int appliedMetricWidth = -1;
        long geometrySignature = Long.MIN_VALUE;

        KeyboardBaseline(Object data, String type, List<Float> lineHeight,
                List<Float> lineGapHeights, List<Integer> lineAlign, float vHeight,
                int lineGapHeight, int height, float translationX, float density,
                int nativeWidth, int nativeT9Width) {
            this.data = data;
            this.type = type;
            this.lineHeight = lineHeight;
            this.lineGapHeights = lineGapHeights;
            this.lineAlign = lineAlign;
            this.vHeight = vHeight;
            this.lineGapHeight = lineGapHeight;
            this.height = height;
            this.translationX = translationX;
            this.density = density;
            this.nativeWidth = nativeWidth;
            this.nativeT9Width = nativeT9Width;
        }

    }

    private WeChatImeFullscreenHook() {}
}


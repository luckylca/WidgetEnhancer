package com.lucky.mixflipouter;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
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
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.graphics.Insets;
import android.widget.LinearLayout;
import android.view.Gravity;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.WeakHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

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
    private static final String CANDIDATE_VIEW_CLASS =
            "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView";
    private static final String SYMBOL_SCROLL_VIEW_CLASS =
            "com.tencent.wetype.plugin.hld.oldsymbol.ImeSboAndSybKeysScrollView";
    private static final int MAX_COMPACT_SHORT_SIDE_DP = 420;
    private static final int MAX_COMPACT_LONG_SIDE_DP = 540;
    private static final Pattern KEYBOARD_BUTTON_BOUNDS = Pattern.compile(
            "\\[(-?\\d+),\\s*(-?\\d+)\\]\\[(-?\\d+),\\s*(-?\\d+)\\]");
    private static volatile int compactKeyboardWidth;
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
    private static final Map<View, int[]> ORIGINAL_PADDING =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, ArcNotchDrawable> ARC_NOTCH_BACKGROUNDS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, KeyboardBaseline> KEYBOARD_BASELINES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Integer> AVAILABLE_ROOT_HEIGHTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ClassLoader, Boolean> WIDTH_HOOKED_LOADERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    static void install(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.tencent.wetype".equals(param.packageName)) return;
        try {
            installScopeReporter();
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
                            reportWeChatScope(service);
                            Window window = null;
                            try {
                                Dialog dialog = service.getWindow();
                                window = dialog == null ? null : dialog.getWindow();
                                if (window == null) return;
                                WindowManager.LayoutParams attrs = window.getAttributes();
                                int current = attrs.layoutInDisplayCutoutMode;
                                Integer original = ORIGINAL_CUTOUT_MODES.putIfAbsent(window, current);
                                if (original == null) original = current;
                                boolean compact = isCompactCover(service);
                                int desired = compact ? WindowManager.LayoutParams
                                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS : original;
                                if (current != desired) {
                                    attrs.layoutInDisplayCutoutMode = desired;
                                    window.setAttributes(attrs);
                                }
                                configureEdgeToEdge(window, compact);
                                if (!compact) compactKeyboardWidth = 0;
                            } catch (Throwable error) {
                                XposedBridge.log("MixFlipCustom: WeChat window setup failed: "
                                        + error);
                            }
                            if (window != null) schedule(service, window.getDecorView(), 0);
                        }
                    });
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
                                XposedBridge.log("MixFlipCustom: candidate refresh failed: " + error);
                            }
                        }
                    });
            XposedBridge.log("MixFlipCustom: WeChat compact-display keyboard adaptation enabled");
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: WeChat keyboard hook unavailable: " + error);
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
            XposedBridge.log("MixFlipCustom: WeChat scope reporter hook unavailable: " + error);
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
                            XposedBridge.log("MixFlipCustom: WeChat IME scope active");
                            return;
                        }
                    } catch (Throwable error) {
                        XposedBridge.log("MixFlipCustom: WeChat scope report attempt failed: "
                                + error);
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
                    && window.isNavigationBarContrastEnforced());
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
            decor.setOnApplyWindowInsetsListener(null);
            decor.setSystemUiVisibility(baseline.systemUiVisibility);
            window.setDecorFitsSystemWindows(true);
            window.setNavigationBarColor(baseline.navigationBarColor);
            if (Build.VERSION.SDK_INT >= 29) {
                window.setNavigationBarContrastEnforced(baseline.navigationBarContrastEnforced);
            }
            WINDOW_BASELINES.remove(window);
        }
    }

    private static void schedule(InputMethodService service, View decor, int attempt) {
        new Handler(service.getMainLooper()).postDelayed(
                () -> adapt(service, decor, attempt), 100L);
    }

    private static void adapt(InputMethodService service, View decor, int attempt) {
        View keyboard = findKeyboard(decor);
        View candidate = findByClassName(decor, CANDIDATE_VIEW_CLASS);
        if (keyboard == null || candidate == null) {
            retry(service, decor, attempt, "keyboard or candidate panel not ready");
            return;
        }
        if (!isCompactCover(service)) {
            restoreLayout(candidate, keyboard);
            restoreKeyboard(keyboard);
            return;
        }

        try {
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
            fitVisiblePunctuationRows(keyboard);
            keyboard.invalidate();
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: WeChat cover layout failed: " + error);
            retry(service, decor, attempt, "layout adaptation failed");
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
        Object data = XposedHelpers.callMethod(keyboard, "getOriginData");
        String type = String.valueOf(XposedHelpers.callMethod(keyboard, "getKeyboardType"));
        KeyboardBaseline current = KEYBOARD_BASELINES.get(keyboard);
        if (current != null && current.type.equals(type)) return;

        Object originalData = XposedHelpers.callMethod(data, "deepCopy");
        Object config = XposedHelpers.callMethod(originalData, "getConfig");
        KeyboardBaseline baseline = new KeyboardBaseline(originalData, type,
                copyFloatList(XposedHelpers.callMethod(config, "getLineHeight")),
                copyFloatList(XposedHelpers.callMethod(config, "getLineGapHeights")),
                copyIntegerList(XposedHelpers.callMethod(config, "getLineAlign")),
                ((Number) XposedHelpers.callMethod(config, "getVHeight")).floatValue(),
                ((Number) XposedHelpers.callMethod(config, "getLineGapHeight")).intValue(),
                keyboard.getHeight(), keyboard.getTranslationX());
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
                && availableWidth == baseline.appliedWidth) return true;

        Object data = XposedHelpers.callMethod(baseline.data, "deepCopy");
        Object config = XposedHelpers.callMethod(data, "getConfig");
        configureKeyboardData(config, baseline, scale);
        clearIndependentWidthScaleCache(keyboard);
        XposedHelpers.callMethod(keyboard, "O1", data);
        float fillScale = getBottomFillScale(keyboard);
        if (Math.abs(fillScale - 1.0f) > 0.01f) {
            data = XposedHelpers.callMethod(baseline.data, "deepCopy");
            config = XposedHelpers.callMethod(data, "getConfig");
            configureKeyboardData(config, baseline, scale * fillScale);
            clearIndependentWidthScaleCache(keyboard);
            XposedHelpers.callMethod(keyboard, "O1", data);
        }
        baseline.appliedScale = scale;
        baseline.appliedWidth = availableWidth;
        return true;
    }

    private static void centerKeyboardContent(View keyboard) throws Throwable {
        KeyboardBaseline baseline = KEYBOARD_BASELINES.get(keyboard);
        if (baseline == null || keyboard.getWidth() <= 0) return;
        String tree = String.valueOf(XposedHelpers.callMethod(keyboard,
                "getKeyboardViewTree"));
        Matcher matcher = KEYBOARD_BUTTON_BOUNDS.matcher(tree);
        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        while (matcher.find()) {
            left = Math.min(left, Integer.parseInt(matcher.group(1)));
            right = Math.max(right, Integer.parseInt(matcher.group(3)));
        }
        if (left == Integer.MAX_VALUE || right <= left) return;
        float offset = (keyboard.getWidth() - (right - left)) / 2.0f;
        float targetTranslation = baseline.translationX + offset;
        if (Math.abs(keyboard.getTranslationX() - targetTranslation) > 0.5f) {
            keyboard.setTranslationX(targetTranslation);
        }
    }

    private static void fitVisiblePunctuationRows(View keyboard) throws Throwable {
        View symbolView = findByClassName(keyboard, SYMBOL_SCROLL_VIEW_CLASS);
        if (symbolView == null || symbolView.getHeight() <= 0) return;

        Object recycler = XposedHelpers.callMethod(symbolView, "getScrollContainerRv");
        if (!(recycler instanceof ViewGroup)) return;
        ViewGroup list = (ViewGroup) recycler;
        int availableHeight = list.getHeight() - list.getPaddingTop() - list.getPaddingBottom();
        if (availableHeight <= 0) return;

        Object adapter = XposedHelpers.callMethod(symbolView, "getAdapter");
        Object data = XposedHelpers.callMethod(adapter, "getDataList");
        if (!(data instanceof List) || ((List<?>) data).isEmpty()
                || !(((List<?>) data).get(0) instanceof String)) return;

        // The native list uses a fixed 68dp item height, which clips its fourth
        // punctuation row on the shorter cover-screen keyboard.
        int itemHeight = Math.max(1, availableHeight / 4);
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
        if (changed) {
            list.requestLayout();
        }
    }

    private static void configureKeyboardData(Object config, KeyboardBaseline baseline,
            float scale) throws Throwable {
        XposedHelpers.callMethod(config, "setLineHeight", scaledList(baseline.lineHeight, scale));
        XposedHelpers.callMethod(config, "setLineGapHeights",
                scaledList(baseline.lineGapHeights, scale));
        XposedHelpers.callMethod(config, "setLineAlign",
                new ArrayList<>(baseline.lineAlign));
        XposedHelpers.callMethod(config, "setVHeight", Float.valueOf(baseline.vHeight * scale));
        XposedHelpers.callMethod(config, "setLineGapHeight",
                Integer.valueOf(Math.round(baseline.lineGapHeight * scale)));
        XposedHelpers.callMethod(config, "setGravity", Integer.valueOf(Gravity.LEFT));
    }

    private static float getBottomFillScale(View keyboard) {
        try {
            String tree = String.valueOf(XposedHelpers.callMethod(keyboard,
                    "getKeyboardViewTree"));
            Matcher matcher = KEYBOARD_BUTTON_BOUNDS.matcher(tree);
            int lastButtonBottom = Integer.MIN_VALUE;
            while (matcher.find()) {
                lastButtonBottom = Math.max(lastButtonBottom,
                        Integer.parseInt(matcher.group(4)));
            }
            int[] location = new int[2];
            keyboard.getLocationOnScreen(location);
            int usedHeight = lastButtonBottom - location[1];
            if (usedHeight <= 0 || usedHeight >= keyboard.getHeight()) return 1.0f;
            int bottomMargin = Math.round(11.0f
                    * keyboard.getResources().getDisplayMetrics().density);
            float fit = (keyboard.getHeight() - bottomMargin) / (float) usedHeight;
            return Math.max(0.75f, Math.min(1.15f, fit));
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    private static void ensureNativeWidthHook(View keyboard) throws Throwable {
        ClassLoader loader = keyboard.getClass().getClassLoader();
        if (Boolean.TRUE.equals(WIDTH_HOOKED_LOADERS.get(loader))) return;
        Class<?> metricClass = XposedHelpers.findClass(
                "com.tencent.wetype.plugin.hld.utils.m1", loader);
        Class<?> keyboardType = XposedHelpers.callMethod(keyboard, "getKeyboardType")
                .getClass();
        XposedHelpers.findAndHookMethod(metricClass, "I0", keyboardType,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam hook) {
                        int width = compactKeyboardWidth;
                        if (width > 0) hook.setResult(width);
                    }
                });
        XposedHelpers.findAndHookMethod(metricClass, "y1", keyboardType,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam hook) {
                        int width = compactKeyboardWidth;
                        if (width > 0) hook.setResult(width);
                    }
                });
        WIDTH_HOOKED_LOADERS.put(loader, true);
        XposedBridge.log("MixFlipCustom: native keyboard width and key scale hooks installed");
    }

    private static void clearIndependentWidthScaleCache(View keyboard) {
        try {
            Class<?> metricClass = XposedHelpers.findClass(
                    "com.tencent.wetype.plugin.hld.utils.m1",
                    keyboard.getClass().getClassLoader());
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
                        return;
                    }
                } catch (Throwable ignored) {
                    // Keep scanning: WeChat obfuscates the cache field name per release.
                }
            }
            XposedBridge.log("MixFlipCustom: independent key-width cache was not found");
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: key width cache refresh failed: " + error);
        }
    }


    private static void restoreKeyboard(View keyboard) {
        KeyboardBaseline baseline = KEYBOARD_BASELINES.remove(keyboard);
        if (baseline == null) return;
        compactKeyboardWidth = 0;
        try {
            Object data = XposedHelpers.callMethod(baseline.data, "deepCopy");
            XposedHelpers.callMethod(keyboard, "O1", data);
            keyboard.setTranslationX(baseline.translationX);
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: WeChat keyboard restore failed: " + error);
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
            if (KEYBOARD_VIEW_CLASS.equals(type.getName())) return view;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            View result = findKeyboard(group.getChildAt(index));
            if (result != null) return result;
        }
        return null;
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
            XposedBridge.log("MixFlipCustom: WeChat keyboard layout did not settle: " + reason);
            return;
        }
        schedule(service, decor, attempt + 1);
    }

    private static boolean isCompactCover(Context context) {
        if (context == null) return false;
        Configuration config = context.getResources().getConfiguration();
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

        WindowBaseline(int systemUiVisibility, int navigationBarColor,
                boolean navigationBarContrastEnforced) {
            this.systemUiVisibility = systemUiVisibility;
            this.navigationBarColor = navigationBarColor;
            this.navigationBarContrastEnforced = navigationBarContrastEnforced;
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
        float appliedScale = -1.0f;
        int appliedWidth = -1;

        KeyboardBaseline(Object data, String type, List<Float> lineHeight,
                List<Float> lineGapHeights, List<Integer> lineAlign, float vHeight,
                int lineGapHeight, int height, float translationX) {
            this.data = data;
            this.type = type;
            this.lineHeight = lineHeight;
            this.lineGapHeights = lineGapHeights;
            this.lineAlign = lineAlign;
            this.vHeight = vHeight;
            this.lineGapHeight = lineGapHeight;
            this.height = height;
            this.translationX = translationX;
        }
    }

    private WeChatImeFullscreenHook() {}
}


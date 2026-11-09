package com.lucky.mixflipouter;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Lets WeChat Input Method stand in for Sogou while the MIX Flip is folded.
 *
 * HyperOS selects the cover keyboard through SogouInputMethodSwitcher and
 * InputMethodManagerServiceImpl. Treating WeChat as the supported cover IME
 * lets the vendor's normal fold/unfold path select it outside and restore the
 * separately saved internal-screen IME when unfolded. This is based on the
 * Sogou lock points in this device's HyperOS build and MixFlipMod's cover-IME
 * approach; it does not disable the vendor's fold/unfold restoration path.
 */
public final class OuterScreenInputMethodHook {
    private static final String IMMS_IMPL =
            "com.android.server.inputmethod.InputMethodManagerServiceImpl";
    private static final String IMMS =
            "com.android.server.inputmethod.InputMethodManagerService";
    private static final String SOGOU_SWITCHER =
            "com.android.server.inputmethod.SogouInputMethodSwitcher";
    private static final String SETTINGS_REPOSITORY =
            "com.android.server.inputmethod.InputMethodSettingsRepository";
    private static final String WECHAT_IME_PACKAGE = "com.tencent.wetype";
    private static final String SOGOU_IME_PACKAGE = "com.sohu.inputmethod.sogou.xiaomi";
    private static final String INTERNAL_IME_KEY = "internal_screen_input_method_in_flip";
    private static final String WECHAT_IME_PREFIX = "com.tencent.wetype/";
    private static volatile boolean scopeReporterInstalled;
    private static volatile boolean systemScopeReported;
    private static volatile boolean systemScopeReportPending;
    private static volatile boolean replacementObserverInstalled;
    private static volatile boolean foldCheckFailureLogged;
    private static volatile String lastSelectionSkip = "";
    private static volatile String lastSelectionResult = "";
    private static volatile Boolean lastFoldedState;
    private static volatile int selectionVerificationGeneration;
    private static ContentObserver replacementObserver;

    static void install(ClassLoader loader) throws Throwable {
        installScopeReporter(loader);
        XC_MethodHook allowWeChatOnCover = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam hook) {
                reportSystemScopeFromService(hook.args);
                String methodId = (String) hook.args[hook.args.length - 1];
                applyImeLockDecision(hook, methodId, loader);
            }
        };
        ModuleLogger.logHookStart("outer-screen IME lock policy", IMMS_IMPL,
                "isSogouMethodLocked", "InputMethodManagerService, String");
        XposedHelpers.findAndHookMethod(IMMS_IMPL, loader,
                "isSogouMethodLocked", XposedHelpers.findClass(IMMS, loader),
                String.class, allowWeChatOnCover);
        ModuleLogger.logHookSuccess("outer-screen IME lock policy", IMMS_IMPL,
                "isSogouMethodLocked", "InputMethodManagerService, String");

        ModuleLogger.logHookStart("outer-screen Sogou switcher policy", SOGOU_SWITCHER,
                "isSogouMethodLocked", "String");
        XposedHelpers.findAndHookMethod(SOGOU_SWITCHER, loader,
                "isSogouMethodLocked", String.class, allowWeChatOnCover);
        ModuleLogger.logHookSuccess("outer-screen Sogou switcher policy", SOGOU_SWITCHER,
                "isSogouMethodLocked", "String");

        ModuleLogger.logHookStart("outer-screen fold state observer", IMMS_IMPL,
                "onDisplayDeviceStateChanged", "int");
        XposedHelpers.findAndHookMethod(IMMS_IMPL, loader,
                "onDisplayDeviceStateChanged", int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam hook) {
                        reportSystemScopeFromService(new Object[]{hook.thisObject});
                        postSelectionSync(loader, hook.thisObject);
                    }
                });
        ModuleLogger.logHookSuccess("outer-screen fold state observer", IMMS_IMPL,
                "onDisplayDeviceStateChanged", "int");
        ModuleLogger.info("Outer-screen input-method selection hooks installed");
        XposedBridge.log("WidgetEnhancer: system_server IME hooks registered");
    }

    private static void installScopeReporter(ClassLoader loader) {
        try {
            Class<?> implClass = XposedHelpers.findClass(IMMS_IMPL, loader);
            java.util.Set<?> hooks = XposedBridge.hookAllMethods(
                    implClass, "init", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            if (scopeReporterInstalled) return;
                            try {
                                Context context = null;
                                for (Object arg : hook.args) {
                                    if (arg instanceof Context) {
                                        context = (Context) arg;
                                        break;
                                    }
                                }
                                if (context == null) {
                                    context = (Context) XposedHelpers.getObjectField(
                                            hook.thisObject, "mContext");
                                }
                                if (context == null) {
                                    throw new IllegalStateException(
                                            "InputMethodManagerServiceImpl has no Context");
                                }
                                installReplacementObserver(context, loader);
                                reportScope(context, "system");
                                scopeReporterInstalled = true;
                                postSelectionSync(loader, hook.thisObject);
                            } catch (Throwable error) {
                                ModuleLogger.logExceptionThrottled(
                                        "system_server scope report setup failed", error);
                            }
                        }
                    });
            if (hooks == null || hooks.isEmpty()) {
                throw new NoSuchMethodError(IMMS_IMPL + ".init");
            }
            ModuleLogger.info("system_server scope reporter installed");
        } catch (Throwable error) {
            ModuleLogger.logHookFailure("system_server IME scope reporter", IMMS_IMPL,
                    "init", "Context and system-server load beacon", error);
        }
    }

    private static void reportScope(Context context, String scope) {
        if (context == null) return;
        if ("system".equals(scope)) {
            // The initial load-package callback may precede system Context creation.
            // Bootstrap the logger again once IMMS supplies a usable Context.
            ModuleLogger.initialize(context, "android", "android");
            synchronized (OuterScreenInputMethodHook.class) {
                if (systemScopeReported || systemScopeReportPending) return;
                systemScopeReportPending = true;
            }
        }
        Thread reporter = new Thread(() -> {
            try {
                Bundle result = context.getContentResolver().call(
                        ImeScopeStatusProvider.URI, "report_scope", scope, null);
                if (result != null && "system".equals(scope)
                        && result.getBoolean("accepted", false)) {
                    systemScopeReported = true;
                } else if (result == null) {
                    ModuleLogger.warn("Scope report returned no result: " + scope);
                } else if ("system".equals(scope)) {
                    ModuleLogger.warn("system_server scope report was rejected");
                }
            } catch (Throwable error) {
                ModuleLogger.logException("Scope report failed for " + scope, error);
            } finally {
                if ("system".equals(scope)) {
                    systemScopeReportPending = false;
                }
            }
        }, "MixFlipImeScope");
        reporter.setDaemon(true);
        reporter.start();
    }

    private static void reportSystemScopeFromService(Object[] args) {
        if (systemScopeReported || systemScopeReportPending) return;
        for (Object arg : args) {
            if (arg == null || arg instanceof String) continue;
            try {
                Object value = XposedHelpers.getObjectField(arg, "mContext");
                if (value instanceof Context) {
                    reportScope((Context) value, "system");
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isWeChatIme(String methodId) {
        return methodId != null && methodId.startsWith(WECHAT_IME_PREFIX);
    }

    private static void applyImeLockDecision(XC_MethodHook.MethodHookParam hook,
                                             String methodId, ClassLoader loader) {
        if (!isFolded(loader)) return;
        ImeReplacementSettings.ReadResult preference =
                ImeReplacementSettings.targetStatusInSystemServer();
        if (!preference.known) {
            ModuleLogger.warnThrottled("ime_target_unknown",
                    "Folded IME lock decision skipped because replacement target is UNKNOWN: "
                            + preference.error, 30_000L);
            return;
        }
        String selectedPackage = preference.target;
        if (selectedPackage.isEmpty()) return;
        if (methodId != null && methodId.startsWith(selectedPackage + "/")) {
            hook.setResult(true);
            ModuleLogger.warnThrottled("ime_lock_target_allowed",
                    "HyperOS cover IME lock allows selected target " + selectedPackage,
                    30_000L);
        } else if (methodId != null && methodId.startsWith(SOGOU_IME_PACKAGE + "/")) {
            // HyperOS keeps the current IME if it is already considered a valid
            // cover keyboard. Unlock Sogou so its fold handler asks for the
            // preferred locked method, which is WeChat while this setting is on.
            hook.setResult(false);
            ModuleLogger.warnThrottled("ime_lock_sogou_released",
                    "HyperOS cover IME lock released Sogou for target " + selectedPackage,
                    30_000L);
        }
    }

    private static void installReplacementObserver(Context context, ClassLoader loader) {
        synchronized (OuterScreenInputMethodHook.class) {
            if (replacementObserverInstalled) return;
            replacementObserverInstalled = true;
        }
        try {
            ImeReplacementSettings.setSystemResolver(context.getContentResolver());
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    postSelectionSync(loader, null);
                }
            };
            context.getContentResolver().registerContentObserver(
                    ImeScopeStatusProvider.URI, false, observer);
            replacementObserver = observer;
            ModuleLogger.info("Input-method replacement settings observer installed");
        } catch (Throwable error) {
            synchronized (OuterScreenInputMethodHook.class) {
                replacementObserverInstalled = false;
            }
            ModuleLogger.logException("Input-method replacement observer unavailable", error);
        }
    }

    private static void postSelectionSync(ClassLoader loader, Object serviceImpl) {
        Context context = null;
        try {
            if (serviceImpl != null) {
                Object value = XposedHelpers.getObjectField(serviceImpl, "mContext");
                if (value instanceof Context) context = (Context) value;
            }
            if (context == null) {
                Class<?> implClass = XposedHelpers.findClass(IMMS_IMPL, loader);
                Object instance = XposedHelpers.callStaticMethod(implClass, "getInstance");
                if (instance != null) {
                    Object value = XposedHelpers.getObjectField(instance, "mContext");
                    if (value instanceof Context) context = (Context) value;
                }
            }
        } catch (Throwable error) {
            ModuleLogger.debug("Unable to find current input-method context: "
                    + error.getClass().getSimpleName());
        }
        if (context == null) return;
        ModuleLogger.initialize(context, "android", "android");
        Context systemContext = context;
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> applySelectionForCurrentFoldState(systemContext, loader), 250L);
    }

    private static void applySelectionForCurrentFoldState(Context context, ClassLoader loader) {
        try {
            Class<?> implClass = XposedHelpers.findClass(IMMS_IMPL, loader);
            Object impl = XposedHelpers.callStaticMethod(implClass, "getInstance");
            if (impl == null) {
                ModuleLogger.warnThrottled("ime_service_missing",
                        "Fold observer ran but InputMethodManagerServiceImpl#getInstance returned null",
                        30_000L);
                return;
            }
            boolean folded = XposedHelpers.getBooleanField(impl, "mDeviceFolded");
            logFoldState(folded);

            Object imms = XposedHelpers.getObjectField(impl, "mImms");
            int userId = XposedHelpers.getIntField(imms, "mCurrentImeUserId");
            if (!folded) {
                verifyInternalImeRestored(context, userId, selectionVerificationGeneration);
                return;
            }
            ImeReplacementSettings.ReadResult preference =
                    ImeReplacementSettings.targetStatusInSystemServer();
            if (!preference.known) {
                String key = "unknown:" + preference.error;
                if (!key.equals(lastSelectionSkip)) {
                    lastSelectionSkip = key;
                    ModuleLogger.logFeatureState("outer-screen input method", "UNKNOWN",
                            "未修改系统输入法；替换目标读取失败：" + preference.error);
                }
                return;
            }
            String selectedPackage = preference.target;
            boolean enabled = !selectedPackage.isEmpty();
            String target = enabled
                    ? inputMethodIdForPackage(context, loader, selectedPackage, userId)
                    : getSecureStringForUser(context, INTERNAL_IME_KEY, userId);
            if ((target == null || target.isEmpty()) && !enabled) {
                target = inputMethodIdForPackage(context, loader, SOGOU_IME_PACKAGE, userId);
            }
            if (target == null || target.isEmpty()) {
                String skipKey = enabled + ":" + selectedPackage;
                if (!skipKey.equals(lastSelectionSkip)) {
                    lastSelectionSkip = skipKey;
                    ModuleLogger.logFeatureState("outer-screen input method", "SKIPPED",
                            enabled ? "已选输入法尚未安装或未启用"
                                    : "无法解析系统默认输入法");
                }
                return;
            }

            String current = getSecureStringForUser(context, "default_input_method", userId);
            if (enabled && (current == null || (!current.startsWith(WECHAT_IME_PACKAGE + "/")
                    && !current.startsWith(ImeReplacementSettings.DOUBAO_PACKAGE + "/")))) {
                String internal = getSecureStringForUser(context, INTERNAL_IME_KEY, userId);
                if (internal == null || internal.isEmpty()) {
                    if (current == null || !putSecureStringForUser(context,
                            INTERNAL_IME_KEY, current, userId)) {
                        ModuleLogger.error("Unable to preserve the internal-screen IME before switching");
                        return;
                    }
                }
            }
            if (!target.equals(current)) {
                try {
                    XposedHelpers.callMethod(impl, "hideImeInternal");
                } catch (Throwable error) {
                    ModuleLogger.logException("Unable to hide current IME before switching", error);
                }
                boolean writeAccepted = putSecureStringForUser(context,
                        "default_input_method", target, userId);
                String observed = getSecureStringForUser(context, "default_input_method", userId);
                if (!writeAccepted || !target.equals(observed)) {
                    logSelectionState("WRITE_VERIFICATION_FAILED", target, observed,
                            "Settings.Secure.putStringForUser accepted=" + writeAccepted);
                    return;
                }
                logSelectionState("WRITE_VERIFIED", target, observed,
                        "Settings.Secure 写入后立即读取一致；等待 HyperOS 延迟复核");
            } else {
                logSelectionState("ALREADY_SELECTED", target, current,
                        "折叠状态下实际默认输入法已是目标");
            }
            scheduleSelectionVerification(context, userId, target, selectedPackage, loader);
        } catch (Throwable error) {
            ModuleLogger.logException("Folded input-method switch failed", error);
        }
    }

    private static void scheduleSelectionVerification(Context context, int userId, String target,
                                                       String selectedPackage,
                                                       ClassLoader loader) {
        int generation = ++selectionVerificationGeneration;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Class<?> implClass = XposedHelpers.findClass(IMMS_IMPL, loader);
                Object impl = XposedHelpers.callStaticMethod(implClass, "getInstance");
                if (impl == null || !XposedHelpers.getBooleanField(impl, "mDeviceFolded")) {
                    logSelectionState("VERIFY_CANCELLED_BY_UNFOLD", target,
                            getSecureStringForUser(context, "default_input_method", userId),
                            "复核前设备已展开");
                    return;
                }
                String current = getSecureStringForUser(
                        context, "default_input_method", userId);
                if (generation != selectionVerificationGeneration) return;
                if (target.equals(current)) {
                    logSelectionState("SUCCESS", target, current,
                            "即时及延迟复核均与目标一致");
                } else {
                    logSelectionState("HYPEROS_OVERRIDE", target, current,
                            "延迟复核发现 HyperOS 或其他组件改写了默认输入法");
                }
            } catch (Throwable error) {
                ModuleLogger.logExceptionThrottled(
                        "Delayed cover IME selection verification failed", error);
            }
        }, 1200L);
    }

    private static void verifyInternalImeRestored(Context context, int userId, int generation) {
        String expected = getSecureStringForUser(context, INTERNAL_IME_KEY, userId);
        String actual = getSecureStringForUser(context, "default_input_method", userId);
        if (expected == null || expected.isEmpty()) {
            logSelectionState("UNFOLDED_NO_SAVED_IME", "", actual,
                    "未找到展开屏保存的输入法 ID，保留 HyperOS 原有恢复行为");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (generation != selectionVerificationGeneration) return;
                String delayed = getSecureStringForUser(
                        context, "default_input_method", userId);
                if (expected.equals(delayed)) {
                    logSelectionState("RESTORE_VERIFIED", expected, delayed,
                            "展开后默认输入法与折叠前保存的输入法一致");
                } else {
                    logSelectionState("RESTORE_MISMATCH", expected, delayed,
                            "展开后的默认输入法与保存值不同；保留系统原有恢复流程");
                }
            } catch (Throwable error) {
                ModuleLogger.logExceptionThrottled(
                        "Expanded-screen input-method restore verification failed", error);
            }
        }, 1200L);
        logSelectionState("UNFOLDED_RESTORE_PENDING", expected, actual,
                "等待 HyperOS 完成展开屏输入法恢复");
    }

    private static void logFoldState(boolean folded) {
        Boolean previous = lastFoldedState;
        if (previous == null || previous.booleanValue() != folded) {
            lastFoldedState = folded;
            ModuleLogger.logFeatureState("MIX Flip fold state",
                    folded ? "FOLDED" : "UNFOLDED",
                    folded ? "外屏模式已启用" : "已展开到内屏模式");
        }
    }

    private static void logSelectionState(String state, String target, String actual,
                                          String detail) {
        String key = state + ":" + target + ":" + actual;
        if (key.equals(lastSelectionResult)) return;
        lastSelectionResult = key;
        ModuleLogger.logFeatureState("outer-screen input method", state,
                "target=" + (target == null ? "null" : target) + ", actual="
                        + (actual == null ? "null" : actual) + "; " + detail);
    }

    private static String inputMethodIdForPackage(Context context, ClassLoader loader,
                                                   String packageName, int userId) {
        try {
            Class<?> repository = XposedHelpers.findClass(SETTINGS_REPOSITORY, loader);
            Object settings = XposedHelpers.callStaticMethod(repository, "get", userId);
            Object methodMap = XposedHelpers.callMethod(settings, "getMethodMap");
            Object values = XposedHelpers.getObjectField(methodMap, "mMap");
            if (!(values instanceof Map)) return null;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) values).entrySet()) {
                Object info = entry.getValue();
                Object candidatePackage = XposedHelpers.callMethod(info, "getPackageName");
                if (packageName.equals(candidatePackage)) return (String) entry.getKey();
            }
        } catch (Throwable error) {
            ModuleLogger.logException("Unable to resolve input method " + packageName, error);
        }
        String enabledMethods = getSecureStringForUser(context, "enabled_input_methods", userId);
        if (enabledMethods != null) {
            String prefix = packageName + "/";
            for (String methodId : enabledMethods.split(":")) {
                if (methodId.startsWith(prefix)) return methodId;
            }
        }
        return null;
    }

    private static String getSecureStringForUser(Context context, String key, int userId) {
        Object value = XposedHelpers.callStaticMethod(Settings.Secure.class,
                "getStringForUser", context.getContentResolver(), key, userId);
        return value instanceof String ? (String) value : null;
    }

    private static boolean putSecureStringForUser(Context context, String key, String value,
                                                  int userId) {
        Object result = XposedHelpers.callStaticMethod(Settings.Secure.class, "putStringForUser",
                context.getContentResolver(), key, value, userId);
        return !(result instanceof Boolean) || Boolean.TRUE.equals(result);
    }

    private static boolean isFolded(ClassLoader loader) {
        try {
            Class<?> implClass = XposedHelpers.findClass(IMMS_IMPL, loader);
            Object impl = XposedHelpers.callStaticMethod(implClass, "getInstance");
            boolean folded = impl != null && XposedHelpers.getBooleanField(impl, "mDeviceFolded");
            logFoldState(folded);
            return folded;
        } catch (Throwable error) {
            if (!foldCheckFailureLogged) {
                foldCheckFailureLogged = true;
                ModuleLogger.logException("Fold-state input-method API unavailable", error);
            }
            return false;
        }
    }

    private OuterScreenInputMethodHook() {}
}

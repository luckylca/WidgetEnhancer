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
    private static ContentObserver replacementObserver;

    static void install(ClassLoader loader) {
        installScopeReporter(loader);
        try {
            XC_MethodHook allowWeChatOnCover = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam hook) {
                    reportSystemScopeFromService(hook.args);
                    String methodId = (String) hook.args[hook.args.length - 1];
                    applyImeLockDecision(hook, methodId, loader);
                }
            };
            XposedHelpers.findAndHookMethod(IMMS_IMPL, loader,
                    "isSogouMethodLocked", XposedHelpers.findClass(IMMS, loader),
                    String.class, allowWeChatOnCover);
            XposedHelpers.findAndHookMethod(SOGOU_SWITCHER, loader,
                    "isSogouMethodLocked", String.class, allowWeChatOnCover);
            XposedHelpers.findAndHookMethod(IMMS_IMPL, loader,
                    "onDisplayDeviceStateChanged", int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            postSelectionSync(loader, hook.thisObject);
                        }
                    });
            XposedBridge.log("MixFlipCustom: WeChat outer-screen IME selection installed");
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: outer-screen IME selection unavailable: " + error);
        }
    }

    private static void installScopeReporter(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(IMMS_IMPL, loader, "init",
                    XposedHelpers.findClass(IMMS, loader), Handler.class,
                    XposedHelpers.findClass(
                            "com.android.server.inputmethod.UserDataRepository", loader),
                    Context.class,
                    XposedHelpers.findClass(
                            "com.android.server.inputmethod.InputMethodMenuControllerNew", loader),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            if (scopeReporterInstalled) return;
                            scopeReporterInstalled = true;
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
                                installReplacementObserver(context, loader);
                                reportScope(context, "system");
                                postSelectionSync(loader, hook.thisObject);
                            } catch (Throwable error) {
                                XposedBridge.log("MixFlipCustom: system scope report failed: "
                                        + error);
                            }
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: system scope reporter unavailable: " + error);
        }
    }

    private static void reportScope(Context context, String scope) {
        if (context == null) return;
        if ("system".equals(scope)) {
            synchronized (OuterScreenInputMethodHook.class) {
                if (systemScopeReported || systemScopeReportPending) return;
                systemScopeReportPending = true;
            }
        }
        Thread reporter = new Thread(() -> {
            try {
                ContentResolver resolver = context.getContentResolver();
                Bundle result = resolver.call(ImeScopeStatusProvider.URI,
                        "report_scope", scope, null);
                if (result != null && "system".equals(scope)) {
                    systemScopeReported = true;
                } else if (result == null) {
                    XposedBridge.log("MixFlipCustom: scope report returned no result: " + scope);
                }
            } catch (Throwable error) {
                XposedBridge.log("MixFlipCustom: scope report failed for " + scope + ": "
                        + error);
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
        if (!isFolded(loader) || !ImeReplacementSettings.isEnabledInSystemServer()) return;
        if (isWeChatIme(methodId)) {
            hook.setResult(true);
        } else if (methodId != null && methodId.startsWith(SOGOU_IME_PACKAGE + "/")) {
            // HyperOS keeps the current IME if it is already considered a valid
            // cover keyboard. Unlock Sogou so its fold handler asks for the
            // preferred locked method, which is WeChat while this setting is on.
            hook.setResult(false);
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
            XposedBridge.log("MixFlipCustom: IME replacement setting observer installed");
        } catch (Throwable error) {
            synchronized (OuterScreenInputMethodHook.class) {
                replacementObserverInstalled = false;
            }
            XposedBridge.log("MixFlipCustom: IME replacement observer unavailable: " + error);
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
        } catch (Throwable ignored) {
        }
        if (context == null) return;
        Context systemContext = context;
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> applySelectionForCurrentFoldState(systemContext, loader), 250L);
    }

    private static void applySelectionForCurrentFoldState(Context context, ClassLoader loader) {
        try {
            Class<?> implClass = XposedHelpers.findClass(IMMS_IMPL, loader);
            Object impl = XposedHelpers.callStaticMethod(implClass, "getInstance");
            if (impl == null || !XposedHelpers.getBooleanField(impl, "mDeviceFolded")) return;

            Object imms = XposedHelpers.getObjectField(impl, "mImms");
            int userId = XposedHelpers.getIntField(imms, "mCurrentImeUserId");
            boolean enabled = ImeReplacementSettings.isEnabledInSystemServer();
            String target = enabled
                    ? inputMethodIdForPackage(context, loader, WECHAT_IME_PACKAGE, userId)
                    : getSecureStringForUser(context, INTERNAL_IME_KEY, userId);
            if ((target == null || target.isEmpty()) && !enabled) {
                target = inputMethodIdForPackage(context, loader, SOGOU_IME_PACKAGE, userId);
            }
            if (target == null || target.isEmpty()) {
                XposedBridge.log("MixFlipCustom: folded IME switch skipped; enabled=" + enabled
                        + ", target unavailable for "
                        + (enabled ? WECHAT_IME_PACKAGE : SOGOU_IME_PACKAGE));
                return;
            }

            String current = getSecureStringForUser(context, "default_input_method", userId);
            if (enabled && (current == null || !current.startsWith(WECHAT_IME_PACKAGE + "/"))) {
                String internal = getSecureStringForUser(context, INTERNAL_IME_KEY, userId);
                if (internal == null || internal.isEmpty()) {
                    putSecureStringForUser(context, INTERNAL_IME_KEY, current, userId);
                }
            }
            if (!target.equals(current)) {
                try {
                    XposedHelpers.callMethod(impl, "hideImeInternal");
                } catch (Throwable ignored) {
                }
                putSecureStringForUser(context, "default_input_method", target, userId);
                XposedBridge.log("MixFlipCustom: folded IME switched to " + target
                        + (enabled ? " (WeChat setting enabled)" : " (WeChat setting disabled)"));
            }
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: folded IME switch failed: " + error);
        }
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
            XposedBridge.log("MixFlipCustom: cannot resolve IME " + packageName + ": " + error);
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

    private static void putSecureStringForUser(Context context, String key, String value,
                                               int userId) {
        XposedHelpers.callStaticMethod(Settings.Secure.class, "putStringForUser",
                context.getContentResolver(), key, value, userId);
    }

    private static boolean isFolded(ClassLoader loader) {
        try {
            Class<?> implClass = XposedHelpers.findClass(IMMS_IMPL, loader);
            Object impl = XposedHelpers.callStaticMethod(implClass, "getInstance");
            return impl != null && XposedHelpers.getBooleanField(impl, "mDeviceFolded");
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: folded IME check failed: " + error);
            return false;
        }
    }

    private OuterScreenInputMethodHook() {}
}

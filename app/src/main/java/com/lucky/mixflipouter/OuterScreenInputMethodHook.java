package com.lucky.mixflipouter;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;

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
    private static final String WECHAT_IME_PREFIX = "com.tencent.wetype/";
    private static volatile boolean scopeReporterInstalled;
    private static volatile boolean systemScopeReported;
    private static volatile boolean systemScopeReportPending;

    static void install(ClassLoader loader) {
        installScopeReporter(loader);
        try {
            XC_MethodHook allowWeChatOnCover = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam hook) {
                    reportSystemScopeFromService(hook.args);
                    String methodId = (String) hook.args[hook.args.length - 1];
                    if (isWeChatIme(methodId) && isFolded(loader)
                            && ImeReplacementSettings.isEnabledInSystemServer()) {
                        hook.setResult(true);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(IMMS_IMPL, loader,
                    "isSogouMethodLocked", XposedHelpers.findClass(IMMS, loader),
                    String.class, allowWeChatOnCover);
            XposedHelpers.findAndHookMethod(SOGOU_SWITCHER, loader,
                    "isSogouMethodLocked", String.class, allowWeChatOnCover);
            XposedBridge.log("MixFlipCustom: WeChat outer-screen IME selection installed");
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: outer-screen IME selection unavailable: " + error);
        }
    }

    private static void installScopeReporter(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(IMMS, loader, "onBootPhase", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            int phase = (Integer) hook.args[0];
                            if (phase < 500 || scopeReporterInstalled) return;
                            scopeReporterInstalled = true;
                            try {
                                Context context = (Context) XposedHelpers.getObjectField(
                                        hook.thisObject, "mContext");
                                reportScope(context, "system");
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

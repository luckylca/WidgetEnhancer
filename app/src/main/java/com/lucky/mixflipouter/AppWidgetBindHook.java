package com.lucky.mixflipouter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * system_server hook: FlipHome lacks BIND_APPWIDGET (signature-level, cannot
 * be granted), so every AppWidget bind pops the system consent sheet. With
 * the module active we trust FlipHome outright — force the permission
 * verdict in AppWidgetServiceImpl.SecurityPolicy for it so binds complete
 * silently. Verified against HyperOS ruyi services.jar: the gate is
 * SecurityPolicy.hasCallerBindPermissionOrBindWhiteListedLocked(String).
 * Everything else is untouched.
 */
public final class AppWidgetBindHook {
    private static final String POLICY_CLASS =
            "com.android.server.appwidget.AppWidgetServiceImpl$SecurityPolicy";
    private static final String CHECK_METHOD = "hasCallerBindPermissionOrBindWhiteListedLocked";

    static void install(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(POLICY_CLASS, loader, CHECK_METHOD,
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam hook) {
                            if (Contract.TARGET_PACKAGE.equals(hook.args[0])) {
                                hook.setResult(true);
                            }
                        }
                    });
            XposedBridge.log("MixFlipCustom: AppWidget bind consent bypass installed");
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: appwidget bind hook unavailable: " + error);
        }
    }

    private AppWidgetBindHook() {}
}

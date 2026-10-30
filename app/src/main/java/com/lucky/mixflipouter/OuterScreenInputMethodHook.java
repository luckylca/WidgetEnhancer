package com.lucky.mixflipouter;

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

    static void install(ClassLoader loader) {
        try {
            XC_MethodHook allowWeChatOnCover = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam hook) {
                    String methodId = (String) hook.args[hook.args.length - 1];
                    if (isWeChatIme(methodId) && isFolded(loader)) {
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

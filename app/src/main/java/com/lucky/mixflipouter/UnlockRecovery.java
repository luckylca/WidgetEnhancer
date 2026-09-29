package com.lucky.mixflipouter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;

import de.robv.android.xposed.XposedBridge;

/**
 * During direct boot (before the first unlock) the module provider is not
 * published, so catalogue injection and runtime host creation fail silently.
 * Arms a one-shot USER_UNLOCKED receiver inside the FlipHome process that
 * replays the injection once the provider becomes reachable.
 */
final class UnlockRecovery {
    private static final long[] RETRY_DELAYS_MS = {1500L, 5000L, 12000L};
    private static final Object LOCK = new Object();
    private static boolean armed;

    static void onApplicationAttach(Context context, ClassLoader loader) {
        if (context == null || loader == null) return;
        if (!Contract.TARGET_PACKAGE.equals(context.getPackageName())) return;
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null || users.isUserUnlocked()) return;
        synchronized (LOCK) {
            if (armed) return;
            armed = true;
        }
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context target, Intent intent) {
                    try {
                        target.unregisterReceiver(this);
                    } catch (Throwable ignored) {
                    }
                    XposedBridge.log("MixFlipCustom: user unlocked, replaying widget injection");
                    retry(context, loader, 0);
                }
            }, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            XposedBridge.log("MixFlipCustom: unlock recovery armed");
        } catch (Throwable error) {
            synchronized (LOCK) {
                armed = false;
            }
            XposedBridge.log("MixFlipCustom: unlock recovery arm failed: " + error);
        }
    }

    private static void retry(Context context, ClassLoader loader, int attempt) {
        Handler main = new Handler(Looper.getMainLooper());
        main.postDelayed(() -> {
            try {
                LiveRefreshBridge.install(context, loader);
                LiveRefreshBridge.refreshNow();
                int injected = WidgetConfig.list(context).size();
                XposedBridge.log("MixFlipCustom: unlock recovery pass " + (attempt + 1)
                        + ", configs=" + injected);
                if (injected == 0 && attempt + 1 < RETRY_DELAYS_MS.length) {
                    retry(context, loader, attempt + 1);
                }
            } catch (Throwable error) {
                XposedBridge.log("MixFlipCustom: unlock recovery failed: " + error);
                if (attempt + 1 < RETRY_DELAYS_MS.length) retry(context, loader, attempt + 1);
            }
        }, RETRY_DELAYS_MS[attempt]);
    }

    private UnlockRecovery() {
    }
}

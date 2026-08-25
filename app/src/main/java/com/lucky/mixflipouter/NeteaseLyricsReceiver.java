package com.lucky.mixflipouter;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/** Package-visibility-safe bridge from the hooked NetEase process to the private provider API. */
public final class NeteaseLyricsReceiver extends BroadcastReceiver {
    static final String ACTION_PUBLISH =
            "com.lucky.mixflipouter.action.PUBLISH_NETEASE_LYRICS";
    static final String ACTION_REPORT =
            "com.lucky.mixflipouter.action.REPORT_NETEASE_LYRICS_HOOK";
    static final String EXTRA_PAYLOAD = "payload";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !trustedSender(context)) {
            setResultCode(Activity.RESULT_CANCELED);
            return;
        }
        Bundle payload = intent.getBundleExtra(EXTRA_PAYLOAD);
        try {
            if (ACTION_PUBLISH.equals(intent.getAction())) {
                Bundle result = context.getContentResolver().call(
                        Contract.PROVIDER_URI, "publish_lyrics_internal", null, payload);
                if (result != null && result.getBoolean("ok")) {
                    setResultExtras(result);
                    setResultCode(Activity.RESULT_OK);
                    return;
                }
            } else if (ACTION_REPORT.equals(intent.getAction())) {
                context.getContentResolver().call(
                        Contract.PROVIDER_URI, "report_lyrics_hook_internal", null, payload);
                setResultCode(Activity.RESULT_OK);
                return;
            }
        } catch (Throwable ignored) {
        }
        setResultCode(Activity.RESULT_CANCELED);
    }

    private boolean trustedSender(Context context) {
        if (Build.VERSION.SDK_INT < 35) return false;
        int uid = getSentFromUid();
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String packageName : packages) {
            if (Contract.NETEASE_PACKAGE.equals(packageName)) return true;
        }
        return false;
    }
}

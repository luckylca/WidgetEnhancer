package com.lucky.mixflipouter;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public final class MixFlipApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        // SuperLyricBridge is deliberately NOT started here: an app process
        // must never register a SuperLyric receiver unless lyrics are actually
        // requested (see SuperLyricBridge.ensureStarted).
    }
}

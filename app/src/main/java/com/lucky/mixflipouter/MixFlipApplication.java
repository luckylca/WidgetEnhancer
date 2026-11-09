package com.lucky.mixflipouter;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public final class MixFlipApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ModuleLogger.beginProcess(getPackageName(), getPackageName());
        ModuleLogger.initialize(this, getPackageName(), getPackageName());
        installOwnCrashRecorder();
        DynamicColors.applyToActivitiesIfAvailable(this);
        // SuperLyricBridge is deliberately NOT started here: an app process
        // must never register a SuperLyric receiver unless lyrics are actually
        // requested (see SuperLyricBridge.ensureStarted).
    }

    private void installOwnCrashRecorder() {
        try {
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            if (previous instanceof ModuleCrashHandler) return;
            Thread.setDefaultUncaughtExceptionHandler(new ModuleCrashHandler(previous));
        } catch (Throwable error) {
            ModuleLogger.logException("Unable to install module-app crash recorder", error);
        }
    }

    private static final class ModuleCrashHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler delegate;
        ModuleCrashHandler(Thread.UncaughtExceptionHandler delegate) { this.delegate = delegate; }

        @Override public void uncaughtException(Thread thread, Throwable error) {
            ModuleLogger.logException("Uncaught exception in WidgetEnhancer app", error);
            if (delegate != null) delegate.uncaughtException(thread, error);
        }
    }
}

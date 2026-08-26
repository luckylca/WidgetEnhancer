package com.lucky.mixflipouter;

import android.app.Activity;
import android.app.Application;
import android.app.BroadcastOptions;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Version-adapted NetEase lyric publisher. No UI TextView scraping is used. */
final class NeteaseLyricHook {
    private static final String LOADER_CLASS = "com.netease.cloudmusic.module.lyric.e";
    private static final String CONSUMER_CLASS = "com.netease.cloudmusic.module.lyric.m0";
    private static final String STATUS_CONTROLLER_CLASS = "f82.c";
    private static final String PLAY_SERVICE_CLASS =
            "com.netease.cloudmusic.service.MainProcessPlayService";
    private static final String INFO_CLASS = "com.netease.cloudmusic.meta.LyricInfo";
    private static final int MAX_LINES = 320;
    private static final int MAX_TEXT_LENGTH = 240;
    private static final long RETRY_INTERVAL_MS = 5_000;
    private static final long STATUS_WATCHDOG_INTERVAL_MS = 3_000;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean SERVICE_ATTACH_HOOK_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean RUNTIME_ADAPTER_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean REPORT_SENT = new AtomicBoolean();
    private static final AtomicBoolean SEND_IN_FLIGHT = new AtomicBoolean();
    private static final AtomicBoolean LOAD_CALLBACK_SEEN = new AtomicBoolean();
    private static final AtomicBoolean TIMER_CALLBACK_SEEN = new AtomicBoolean();
    private static final AtomicBoolean STATUS_REQUEST_IN_FLIGHT = new AtomicBoolean();
    private static long lastRequestedMusicId;
    private static long lastPublishedMusicId;
    private static long lastStatusRequestElapsed;
    private static String lastPublishedKey = "";
    private static String lastAttemptKey = "";
    private static long lastAttemptElapsed;
    private static volatile Object statusWatchdogService;
    private static volatile Class<?> statusWatchdogControllerClass;
    private static volatile Class<?> statusWatchdogLyricLoaderClass;
    private static final Runnable STATUS_WATCHDOG = new Runnable() {
        @Override
        public void run() {
            Object service = statusWatchdogService;
            Class<?> controllerClass = statusWatchdogControllerClass;
            Class<?> lyricLoaderClass = statusWatchdogLyricLoaderClass;
            if (service == null || controllerClass == null || lyricLoaderClass == null) return;
            try {
                Object musicInfo = XposedHelpers.callMethod(service, "getCurrentMusic");
                if (musicInfo != null) {
                    Object controller = XposedHelpers.callStaticMethod(controllerClass, "j");
                    requestStatusLyricsForMusic(lyricLoaderClass, musicInfo, controller);
                }
            } catch (Throwable error) {
                logError("NetEase status lyric watchdog failed", error);
            } finally {
                if (statusWatchdogService == service) {
                    MAIN_HANDLER.postDelayed(this, STATUS_WATCHDOG_INTERVAL_MS);
                }
            }
        }
    };

    static void install(ClassLoader loader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        installServiceAttachHook();

        ArrayList<String> installed = new ArrayList<>();
        ArrayList<String> failures = new ArrayList<>();
        Class<?> infoClass;
        try {
            infoClass = XposedHelpers.findClass(INFO_CLASS, loader);
        } catch (Throwable error) {
            reportWhenReady(false, "网易云歌词模型不兼容：" + error.getClass().getSimpleName());
            XposedBridge.log("MixFlipCustom: NetEase lyric model unavailable: " + error);
            return;
        }

        hookLoader(loader, infoClass, installed, failures);
        hookConsumer(loader, infoClass, installed, failures);
        hookStatusController(loader, installed, failures);

        boolean ok = !installed.isEmpty();
        String message = ok
                ? "网易云歌词入口已匹配：" + String.join(" + ", installed)
                : "网易云歌词入口不兼容：" + String.join(" / ", failures);
        reportWhenReady(ok, message);
        XposedBridge.log("MixFlipCustom: NetEase lyric adapter "
                + (ok ? "installed: " + String.join(",", installed)
                : "failed: " + String.join(",", failures)));
    }

    /** NetEase can replace its base ClassLoader with Tinker after the package hook runs. */
    private static void installServiceAttachHook() {
        if (!SERVICE_ATTACH_HOOK_INSTALLED.compareAndSet(false, true)) return;
        try {
            XposedBridge.hookAllMethods(Service.class, "attach", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam hook) {
                    Object service = hook.thisObject;
                    if (service == null
                            || !PLAY_SERVICE_CLASS.equals(service.getClass().getName())) return;
                    attachRuntimePlayService(service);
                }
            });
        } catch (Throwable error) {
            logError("NetEase service attach hook failed", error);
        }
    }

    private static void attachRuntimePlayService(Object service) {
        try {
            ClassLoader loader = service.getClass().getClassLoader();
            Class<?> controllerClass = XposedHelpers.findClass(STATUS_CONTROLLER_CLASS, loader);
            Class<?> lyricLoaderClass = XposedHelpers.findClass(LOADER_CLASS, loader);
            if (RUNTIME_ADAPTER_INSTALLED.compareAndSet(false, true)) {
                ArrayList<String> installed = new ArrayList<>();
                ArrayList<String> failures = new ArrayList<>();
                Class<?> infoClass = XposedHelpers.findClass(INFO_CLASS, loader);
                hookLoader(loader, infoClass, installed, failures);
                hookConsumer(loader, infoClass, installed, failures);
                hookPlayService(controllerClass, lyricLoaderClass,
                        service.getClass(), installed, failures);
                XposedBridge.log("MixFlipCustom: NetEase runtime lyric adapter installed: "
                        + String.join(",", installed));
                if (!failures.isEmpty()) {
                    XposedBridge.log("MixFlipCustom: NetEase runtime lyric adapter warnings: "
                            + String.join(" / ", failures));
                }
            }
            startStatusWatchdog(controllerClass, lyricLoaderClass, service);
            scheduleStatusControllerInitialization(
                    controllerClass, lyricLoaderClass, service, 0, 250);
        } catch (Throwable error) {
            RUNTIME_ADAPTER_INSTALLED.set(false);
            logError("NetEase runtime play service attachment failed", error);
        }
    }

    private static void hookLoader(ClassLoader loader, Class<?> infoClass,
                                   List<String> installed, List<String> failures) {
        try {
            Class<?> loaderClass = XposedHelpers.findClass(LOADER_CLASS, loader);
            XposedHelpers.findAndHookMethod(loaderClass, "o0", infoClass, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            schedulePublish(hook.args[0], "netease-loader");
                        }
                    });
            installed.add("loader");
        } catch (Throwable error) {
            failures.add("loader=" + error.getClass().getSimpleName());
            XposedBridge.log("MixFlipCustom: NetEase loader hook failed: " + error);
        }
    }

    private static void hookConsumer(ClassLoader loader, Class<?> infoClass,
                                     List<String> installed, List<String> failures) {
        Class<?> consumerClass;
        try {
            consumerClass = XposedHelpers.findClass(CONSUMER_CLASS, loader);
        } catch (Throwable error) {
            failures.add("consumer=" + error.getClass().getSimpleName());
            XposedBridge.log("MixFlipCustom: NetEase consumer class unavailable: " + error);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(consumerClass, "onLrcLoaded", infoClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            if (LOAD_CALLBACK_SEEN.compareAndSet(false, true)) {
                                XposedBridge.log("MixFlipCustom: NetEase onLrcLoaded callback observed");
                            }
                            schedulePublish(hook.args[0], "netease-consumer");
                        }
                    });
            installed.add("consumer-load");
        } catch (Throwable error) {
            failures.add("consumer-load=" + error.getClass().getSimpleName());
            XposedBridge.log("MixFlipCustom: NetEase consumer load hook failed: " + error);
        }

        try {
            XposedHelpers.findAndHookMethod(consumerClass, "onLrcTimerUpdate",
                    int.class, long.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            if (TIMER_CALLBACK_SEEN.compareAndSet(false, true)) {
                                XposedBridge.log("MixFlipCustom: NetEase lyric timer callback observed");
                            }
                            Object info = XposedHelpers.getObjectField(
                                    hook.thisObject, "mLyricInfo");
                            schedulePublish(info, "netease-timer");
                        }
                    });
            installed.add("consumer-timer");
        } catch (Throwable error) {
            failures.add("consumer-timer=" + error.getClass().getSimpleName());
            XposedBridge.log("MixFlipCustom: NetEase consumer timer hook failed: " + error);
        }
    }

    /** Activates NetEase's own status-lyric consumer without spoofing device properties. */
    private static void hookStatusController(ClassLoader loader,
                                             List<String> installed, List<String> failures) {
        try {
            Class<?> controllerClass = XposedHelpers.findClass(STATUS_CONTROLLER_CLASS, loader);
            Class<?> lyricLoaderClass = XposedHelpers.findClass(LOADER_CLASS, loader);
            Class<?> serviceClass = XposedHelpers.findClass(PLAY_SERVICE_CLASS, loader);
            hookPlayService(controllerClass, lyricLoaderClass, serviceClass,
                    installed, failures);
            installed.add("status-driver");
        } catch (Throwable error) {
            failures.add("status-driver=" + error.getClass().getSimpleName());
            logError("NetEase status driver hook failed", error);
        }
    }

    private static void hookPlayService(Class<?> controllerClass, Class<?> lyricLoaderClass,
                                        Class<?> serviceClass,
                                        List<String> installed, List<String> failures) {
        try {
            XposedHelpers.findAndHookMethod(serviceClass, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam hook) {
                    startStatusWatchdog(
                            controllerClass, lyricLoaderClass, hook.thisObject);
                    scheduleStatusControllerInitialization(
                            controllerClass, lyricLoaderClass,
                            hook.thisObject, 0, 1_500);
                }
            });
            installed.add("play-service");
        } catch (Throwable error) {
            failures.add("play-service=" + error.getClass().getSimpleName());
            logError("NetEase play service hook failed", error);
        }

        try {
            XposedBridge.hookAllMethods(serviceClass, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam hook) {
                    stopStatusWatchdog(hook.thisObject);
                }
            });
            installed.add("play-watchdog");
        } catch (Throwable error) {
            failures.add("play-watchdog=" + error.getClass().getSimpleName());
            logError("NetEase play service watchdog cleanup hook failed", error);
        }

        try {
            XposedHelpers.findAndHookMethod(serviceClass, "setPlayResource",
                    int.class, Intent.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            scheduleStatusControllerInitialization(
                                    controllerClass, lyricLoaderClass,
                                    hook.thisObject, 0, 500);
                        }
                    });
            installed.add("play-resource");
        } catch (Throwable error) {
            failures.add("play-resource=" + error.getClass().getSimpleName());
            logError("NetEase play resource hook failed", error);
        }

        try {
            XposedHelpers.findAndHookMethod(serviceClass, "onPlaybackStatusChanged",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            if ((Integer) hook.args[0] == 3) {
                                scheduleStatusControllerInitialization(
                                        controllerClass, lyricLoaderClass,
                                        hook.thisObject, 0, 250);
                            }
                        }
                    });
            installed.add("play-state");
        } catch (Throwable error) {
            failures.add("play-state=" + error.getClass().getSimpleName());
            logError("NetEase playback state hook failed", error);
        }

        try {
            XposedHelpers.findAndHookMethod(serviceClass, "getCurrentMusic",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            Object musicInfo = hook.getResult();
                            if (musicInfo == null) return;
                            scheduleMusicRequest(
                                    controllerClass, lyricLoaderClass, musicInfo);
                        }
                    });
            installed.add("current-music");
        } catch (Throwable error) {
            failures.add("current-music=" + error.getClass().getSimpleName());
            logError("NetEase current music hook failed", error);
        }

        try {
            XC_MethodHook musicInfoHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam hook) {
                    if (hook.args.length > 0 && hook.args[0] != null) {
                        scheduleMusicRequest(
                                controllerClass, lyricLoaderClass, hook.args[0]);
                    }
                }
            };
            XposedBridge.hookAllMethods(
                    serviceClass, "sendMusicInfoToClient", musicInfoHook);
            XposedBridge.hookAllMethods(
                    serviceClass, "sendSpecifiedMusicInfoToClient", musicInfoHook);
            installed.add("music-info");
        } catch (Throwable error) {
            failures.add("music-info=" + error.getClass().getSimpleName());
            logError("NetEase music info hook failed", error);
        }

        try {
            Object existingService = XposedHelpers.getStaticObjectField(
                    serviceClass, "sPlayService");
            if (existingService != null) {
                startStatusWatchdog(controllerClass, lyricLoaderClass, existingService);
                scheduleStatusControllerInitialization(
                        controllerClass, lyricLoaderClass, existingService, 0, 250);
                installed.add("existing-play-service");
            }
        } catch (Throwable error) {
            failures.add("existing-play-service=" + error.getClass().getSimpleName());
            logError("NetEase existing play service attachment failed", error);
        }
    }

    private static void scheduleMusicRequest(Class<?> controllerClass,
                                             Class<?> lyricLoaderClass,
                                             Object musicInfo) {
        MAIN_HANDLER.post(() -> {
            try {
                Object controller = XposedHelpers.callStaticMethod(controllerClass, "j");
                requestStatusLyricsForMusic(lyricLoaderClass, musicInfo, controller);
            } catch (Throwable error) {
                logError("NetEase current music lyric request failed", error);
            }
        });
    }

    private static void scheduleStatusControllerInitialization(
            Class<?> controllerClass, Class<?> lyricLoaderClass,
            Object service, int attempt, long delayMs) {
        MAIN_HANDLER.postDelayed(() -> {
            if (!initializeStatusController(
                    controllerClass, lyricLoaderClass, service) && attempt < 4) {
                scheduleStatusControllerInitialization(
                        controllerClass, lyricLoaderClass,
                        service, attempt + 1, 1_500);
            }
        }, delayMs);
    }

    private static void startStatusWatchdog(Class<?> controllerClass,
                                            Class<?> lyricLoaderClass,
                                            Object service) {
        statusWatchdogControllerClass = controllerClass;
        statusWatchdogLyricLoaderClass = lyricLoaderClass;
        statusWatchdogService = service;
        MAIN_HANDLER.removeCallbacks(STATUS_WATCHDOG);
        MAIN_HANDLER.postDelayed(STATUS_WATCHDOG, STATUS_WATCHDOG_INTERVAL_MS);
    }

    private static void stopStatusWatchdog(Object service) {
        if (statusWatchdogService != service) return;
        statusWatchdogService = null;
        statusWatchdogControllerClass = null;
        statusWatchdogLyricLoaderClass = null;
        MAIN_HANDLER.removeCallbacks(STATUS_WATCHDOG);
    }

    private static boolean initializeStatusController(
            Class<?> controllerClass, Class<?> lyricLoaderClass,
            Object service) {
        try {
            Object controller = XposedHelpers.callStaticMethod(controllerClass, "j");
            return requestStatusLyrics(lyricLoaderClass, service, controller);
        } catch (Throwable error) {
            logError("NetEase status controller init failed", error);
            return false;
        }
    }

    /** Starts NetEase's native lyric loader without its device-specific modular player bridge. */
    private static boolean requestStatusLyrics(Class<?> lyricLoaderClass,
                                               Object service,
                                               Object controller) {
        try {
            Object musicInfo = XposedHelpers.callMethod(service, "getCurrentMusic");
            return musicInfo != null
                    && requestStatusLyricsForMusic(lyricLoaderClass, musicInfo, controller);
        } catch (Throwable error) {
            logError("NetEase status consumer activation failed", error);
            return false;
        }
    }

    private static boolean requestStatusLyricsForMusic(Class<?> lyricLoaderClass,
                                                       Object musicInfo,
                                                       Object controller) {
        boolean requestStarted = false;
        try {
            long musicId = number(XposedHelpers.callMethod(musicInfo, "getFilterMusicId"));
            if (musicId <= 0) return false;
            long now = SystemClock.elapsedRealtime();
            synchronized (NeteaseLyricHook.class) {
                if (musicId == lastPublishedMusicId) return true;
                if (musicId == lastRequestedMusicId
                        && now - lastStatusRequestElapsed < RETRY_INTERVAL_MS) return true;
                if (!STATUS_REQUEST_IN_FLIGHT.compareAndSet(false, true)) return false;
                lastRequestedMusicId = musicId;
                lastStatusRequestElapsed = now;
                requestStarted = true;
            }
            XposedHelpers.setBooleanField(controller, "d", true);
            Object listener = XposedHelpers.getObjectField(controller, "i");
            Object manager = XposedHelpers.callStaticMethod(lyricLoaderClass, "L");
            Object request = XposedHelpers.callMethod(manager, "F0", listener, musicInfo);
            request = XposedHelpers.callMethod(request, "m", false);
            request = XposedHelpers.callMethod(request, "o", false);
            XposedHelpers.callMethod(request, "k");
            XposedBridge.log("MixFlipCustom: NetEase status lyric consumer activated");
            return true;
        } catch (Throwable error) {
            logError("NetEase status consumer activation failed", error);
            return false;
        } finally {
            if (requestStarted) STATUS_REQUEST_IN_FLIGHT.set(false);
        }
    }

    private static void logError(String message, Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        XposedBridge.log("MixFlipCustom: " + message + ": " + cause);
        XposedBridge.log(cause);
    }

    private static void schedulePublish(Object lyricInfo, String source) {
        if (lyricInfo == null) return;
        try {
            Bundle payload = extract(lyricInfo, source);
            @SuppressWarnings("deprecation")
            ArrayList<Bundle> lines = payload.getParcelableArrayList("lines");
            if (payload.getLong("music_id", 0) <= 0 || lines == null || lines.isEmpty()) return;

            String key = publicationKey(payload, lines);
            long now = SystemClock.elapsedRealtime();
            synchronized (NeteaseLyricHook.class) {
                if (key.equals(lastPublishedKey)) return;
                if (key.equals(lastAttemptKey)
                        && now - lastAttemptElapsed < RETRY_INTERVAL_MS) return;
                if (!SEND_IN_FLIGHT.compareAndSet(false, true)) return;
                lastAttemptKey = key;
                lastAttemptElapsed = now;
            }
            new Thread(() -> send(payload, key), "mixflip-lyric-publish").start();
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: lyric extraction failed: " + error);
        }
    }

    private static Bundle extract(Object lyricInfo, String source) {
        Bundle payload = new Bundle();
        payload.putString("source", source);
        payload.putLong("music_id", number(call(lyricInfo, "getMusicId")));
        payload.putLong("lyric_offset", number(call(lyricInfo, "getLyricUserOffset")));
        Object state = call(lyricInfo, "getLyricInfoType");
        payload.putString("state", state == null ? "" : state.toString());
        ArrayList<Bundle> lines = new ArrayList<>();
        Object rawLines = call(lyricInfo, "getSortLines");
        if (rawLines instanceof List) {
            for (Object rawLine : (List<?>) rawLines) {
                if (rawLine == null || lines.size() >= MAX_LINES) break;
                Bundle line = new Bundle();
                line.putInt("start", integer(call(rawLine, "getStartTime")));
                line.putInt("end", integer(call(rawLine, "getEndTime")));
                line.putString("content", boundedText(call(rawLine, "getContent")));
                line.putString("translation", boundedOptionalText(rawLine, "getTranslateContent"));
                line.putString("romanization", boundedOptionalText(rawLine, "getRomeContent"));
                lines.add(line);
            }
        }
        payload.putParcelableArrayList("lines", lines);
        return payload;
    }

    private static String publicationKey(Bundle payload, List<Bundle> lines) {
        Bundle first = lines.get(0);
        Bundle last = lines.get(lines.size() - 1);
        return payload.getLong("music_id", 0) + ":"
                + payload.getLong("lyric_offset", 0) + ":" + lines.size() + ":"
                + first.getInt("start") + ":" + first.getString("content", "").hashCode() + ":"
                + last.getInt("start") + ":" + last.getString("content", "").hashCode();
    }

    private static void send(Bundle payload, String key) {
        try {
            Context context = currentApplication();
            if (context == null) {
                SEND_IN_FLIGHT.set(false);
                return;
            }
            Intent intent = bridgeIntent(NeteaseLyricsReceiver.ACTION_PUBLISH, payload);
            BroadcastReceiver resultReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent resultIntent) {
                    try {
                        if (getResultCode() == Activity.RESULT_OK) {
                            Bundle result = getResultExtras(false);
                            int count = result == null ? 0 : result.getInt("line_count", 0);
                            synchronized (NeteaseLyricHook.class) {
                                lastPublishedKey = key;
                                if (count > 0) {
                                    lastPublishedMusicId = payload.getLong("music_id", 0);
                                }
                            }
                            XposedBridge.log(
                                    "MixFlipCustom: published NetEase lyric timing, lines=" + count);
                        } else {
                            Bundle result = getResultExtras(false);
                            String message = result == null ? ""
                                    : result.getString("message", "");
                            XposedBridge.log("MixFlipCustom: lyric bridge rejected publication"
                                    + (message.isEmpty() ? "" : ": " + message));
                        }
                    } finally {
                        SEND_IN_FLIGHT.set(false);
                    }
                }
            };
            sendOrderedBridge(context, intent, resultReceiver);
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: lyric publish failed: " + error);
            SEND_IN_FLIGHT.set(false);
        }
    }

    private static void reportWhenReady(boolean ok, String message) {
        Context current = currentApplication();
        if (current != null) {
            report(current, ok, message);
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            report((Context) hook.args[0], ok, message);
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: NetEase health report hook failed: " + error);
        }
    }

    private static void report(Context context, boolean ok, String message) {
        if (context == null || !REPORT_SENT.compareAndSet(false, true)) return;
        try {
            Bundle extras = new Bundle();
            extras.putString("stage", "lyrics");
            extras.putBoolean("ok", ok);
            extras.putString("message", message);
            sendOrderedBridge(context,
                    bridgeIntent(NeteaseLyricsReceiver.ACTION_REPORT, extras),
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context ignored, Intent intent) {
                            if (getResultCode() != Activity.RESULT_OK) REPORT_SENT.set(false);
                        }
                    });
        } catch (Throwable error) {
            REPORT_SENT.set(false);
            XposedBridge.log("MixFlipCustom: NetEase health report failed: " + error);
        }
    }

    private static Intent bridgeIntent(String action, Bundle payload) {
        return new Intent(action)
                .setComponent(new ComponentName(Contract.MODULE_PACKAGE,
                        NeteaseLyricsReceiver.class.getName()))
                .putExtra(NeteaseLyricsReceiver.EXTRA_PAYLOAD, payload);
    }

    private static void sendOrderedBridge(Context context, Intent intent,
                                          BroadcastReceiver resultReceiver) {
        Handler handler = MAIN_HANDLER;
        if (Build.VERSION.SDK_INT >= 34) {
            Bundle options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle();
            context.sendOrderedBroadcast(intent, null, options, resultReceiver, handler,
                    Activity.RESULT_CANCELED, null, null);
            return;
        }
        context.sendOrderedBroadcast(intent, null, resultReceiver, handler,
                Activity.RESULT_CANCELED, null, null);
    }

    private static Object call(Object target, String method) {
        return XposedHelpers.callMethod(target, method);
    }

    private static String boundedOptionalText(Object target, String method) {
        try {
            return boundedText(call(target, method));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String boundedText(Object value) {
        String text = value == null ? "" : value.toString();
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH);
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static int integer(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            return (Context) XposedHelpers.callStaticMethod(activityThread, "currentApplication");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private NeteaseLyricHook() {
    }
}

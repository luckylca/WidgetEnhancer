package com.lucky.mixflipouter;

import android.content.Context;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Version-adapted NetEase lyric publisher. No UI TextView scraping is used. */
final class NeteaseLyricHook {
    private static final String LOADER_CLASS = "com.netease.cloudmusic.module.lyric.e";
    private static final String INFO_CLASS = "com.netease.cloudmusic.meta.LyricInfo";
    private static final int MAX_LINES = 320;
    private static final int MAX_TEXT_LENGTH = 240;

    static void install(ClassLoader loader) {
        try {
            Class<?> loaderClass = XposedHelpers.findClass(LOADER_CLASS, loader);
            Class<?> infoClass = XposedHelpers.findClass(INFO_CLASS, loader);
            XposedHelpers.findAndHookMethod(loaderClass, "o0", infoClass, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam hook) {
                            publish(hook.args[0]);
                        }
                    });
            report(true, "网易云 9.x 歌词加载回调已匹配");
            XposedBridge.log("MixFlipCustom: NetEase lyric adapter installed");
        } catch (Throwable error) {
            report(false, "网易云歌词签名不兼容：" + error.getClass().getSimpleName());
            XposedBridge.log("MixFlipCustom: NetEase lyric adapter failed: " + error);
        }
    }

    private static void publish(Object lyricInfo) {
        if (lyricInfo == null) return;
        try {
            Bundle payload = new Bundle();
            payload.putString("source", "netease-9.x");
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
            new Thread(() -> send(payload), "mixflip-lyric-publish").start();
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: lyric extraction failed: " + error);
        }
    }

    private static void send(Bundle payload) {
        Context context = currentApplication();
        if (context == null) return;
        try {
            Bundle result = context.getContentResolver().call(
                    Contract.PROVIDER_URI, "publish_lyrics", null, payload);
            int count = result == null ? 0 : result.getInt("line_count", 0);
            XposedBridge.log("MixFlipCustom: published NetEase lyric timing, lines=" + count);
        } catch (Throwable error) {
            XposedBridge.log("MixFlipCustom: lyric publish failed: " + error);
        }
    }

    private static void report(boolean ok, String message) {
        Context context = currentApplication();
        if (context == null) return;
        try {
            Bundle extras = new Bundle();
            extras.putString("stage", "lyrics");
            extras.putBoolean("ok", ok);
            extras.putString("message", message);
            context.getContentResolver().call(
                    Contract.PROVIDER_URI, "report_lyrics_hook", null, extras);
        } catch (Throwable ignored) {
        }
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

    private NeteaseLyricHook() {}
}

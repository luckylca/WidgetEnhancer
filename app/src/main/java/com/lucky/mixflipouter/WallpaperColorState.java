package com.lucky.mixflipouter;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mirrors FlipHome's own backdrop color verdict (same inputs official widgets use):
 * on the launcher desk it follows WallpaperUtils.setCurrentWallpaperColorMode
 * (dark wallpaper -> light text); when an app is in the foreground it follows
 * WidgetBgHelper.onAppColorChanged — the app's navigation bar color, judged with
 * the same luminance formula as BlurColorUtil.isColorDark.
 * Updated by HookEntry hooks; consumed by module views drawn over the backdrop.
 */
public final class WallpaperColorState {
    public interface Listener {
        void onWallpaperColorChanged(boolean darkWallpaper);
    }

    /** FlipHome treats color mode 0 as "dark wallpaper" (WallpaperUtils.hasAppliedDarkWallpaper). */
    private static volatile boolean systemDark = true;
    /** Whether the launcher desk is in the foreground (WidgetBgHelper.onDeskChanged). */
    private static volatile boolean onDesk = true;
    /** Luminance verdict of the foreground app's navigation bar color. */
    private static volatile boolean appDark = true;
    private static volatile boolean darkWallpaper = true;
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private WallpaperColorState() {}

    public static boolean isDarkWallpaper() {
        return darkWallpaper;
    }

    /** FlipHome's wallpaper verdict; applies while the desk is in the foreground. */
    public static void setDarkWallpaper(boolean dark) {
        systemDark = dark;
        apply();
    }

    /** WidgetBgHelper.onDeskChanged: true = launcher visible, false = app in foreground. */
    public static void setOnDesk(boolean desk) {
        onDesk = desk;
        apply();
    }

    /** WidgetBgHelper.onAppColorChanged, judged like BlurColorUtil.isColorDark. */
    public static void setAppColor(int color) {
        if (color == 0) return;
        double darkness = 1.0 - (((android.graphics.Color.red(color) * 0.299d)
                + (android.graphics.Color.green(color) * 0.587d)
                + (android.graphics.Color.blue(color) * 0.114d)) / 255.0d);
        appDark = darkness >= 0.5d;
        apply();
    }

    private static void apply() {
        boolean effective = onDesk ? systemDark : appDark;
        if (effective == darkWallpaper) return;
        darkWallpaper = effective;
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) {
                    listener.onWallpaperColorChanged(darkWallpaper);
                }
            }
        });
    }

    public static void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Primary text drawn directly over the wallpaper. */
    public static int primaryTextColor() {
        return darkWallpaper ? 0xE6FFFFFF : 0xE61A1A1A;
    }

    /** Secondary/hint text drawn directly over the wallpaper. */
    public static int secondaryTextColor() {
        return darkWallpaper ? 0x99FFFFFF : 0x991A1A1A;
    }

    /** Translucent placeholder card background over the wallpaper. */
    public static int placeholderBgColor() {
        return darkWallpaper ? 0x1AFFFFFF : 0x14000000;
    }
}

package com.lucky.mixflipouter;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * In-process feed of recent status bar notifications shared by the notification
 * listener and the guarded provider. Holds at most {@link #MAX_ENTRIES} entries,
 * exposes the newest {@link #VISIBLE_ENTRIES} to the outer-screen widget.
 */
final class NotificationStateStore {
    static final int MAX_ENTRIES = 20;
    static final int VISIBLE_ENTRIES = 3;
    private static final Object LOCK = new Object();
    private static final List<Entry> entries = new ArrayList<>();
    private static final long REBIND_MIN_INTERVAL_MS = 30_000L;
    private static final long RESYNC_MIN_INTERVAL_MS = 2_000L;
    private static long revision;
    private static PlaybackNotificationListener listener;
    private static long lastRebindRequestElapsed;
    private static long lastResyncElapsed;

    /**
     * After a force-stop / app update the system sometimes never re-binds the
     * enabled notification listener; nudge it when our process is alive but
     * the listener is not connected. Throttled — NMS logs warnings otherwise.
     */
    static void requestRebindIfDisconnected(Context context) {
        if (context == null || PlaybackNotificationListener.isConnected()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastRebindRequestElapsed < REBIND_MIN_INTERVAL_MS) return;
        lastRebindRequestElapsed = now;
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                    new android.content.ComponentName(context, PlaybackNotificationListener.class));
        } catch (Throwable ignored) {
        }
    }

    static final class Entry {
        String key = "";
        String packageName = "";
        String title = "";
        String text = "";
        long postTime;
        boolean clearable;
        PendingIntent contentIntent;
    }

    static void attach(PlaybackNotificationListener service) {
        listener = service;
        seed(service == null ? null : activeNotifications(service));
    }

    static void detach(PlaybackNotificationListener service) {
        if (listener == service) listener = null;
    }

    static void onPosted(StatusBarNotification notification) {
        Entry entry = map(notification);
        if (entry == null) return;
        synchronized (LOCK) {
            removeLocked(entry.key);
            entries.add(entry);
            sortLocked();
            while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
            revision++;
        }
    }

    static void onRemoved(StatusBarNotification notification) {
        if (notification == null) return;
        synchronized (LOCK) {
            if (removeLocked(notification.getKey())) revision++;
        }
    }

    static void seed(List<StatusBarNotification> active) {
        List<Entry> mapped = new ArrayList<>();
        if (active != null) {
            for (StatusBarNotification notification : active) {
                Entry entry = map(notification);
                if (entry != null) mapped.add(entry);
            }
        }
        synchronized (LOCK) {
            entries.clear();
            entries.addAll(mapped);
            sortLocked();
            while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
            revision++;
        }
    }

    static void clear() {
        synchronized (LOCK) {
            entries.clear();
            revision++;
        }
    }

    /**
     * Incremental post/remove callbacks are not reliable (missed while the
     * process was dead, rewritten keys on MIUI/HyperOS), so the list is
     * periodically replaced wholesale from the live status bar. This keeps
     * the widget showing exactly what the status bar shows.
     */
    static void resyncFromStatusBar() {
        PlaybackNotificationListener service = listener;
        if (service == null) return;
        seed(activeNotifications(service));
    }

    /** Throttled variant for poll-driven callers (widget snapshot requests). */
    static void resyncThrottled() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastResyncElapsed < RESYNC_MIN_INTERVAL_MS) return;
        lastResyncElapsed = now;
        resyncFromStatusBar();
    }

    static long revision() {
        synchronized (LOCK) {
            return revision;
        }
    }

    static Bundle snapshot() {
        resyncThrottled();
        Bundle out = new Bundle();
        synchronized (LOCK) {
            int count = Math.min(VISIBLE_ENTRIES, entries.size());
            out.putLong("revision", revision);
            out.putInt("count", count);
            for (int i = 0; i < count; i++) {
                Entry entry = entries.get(i);
                out.putString("key_" + i, entry.key);
                out.putString("pkg_" + i, entry.packageName);
                out.putString("title_" + i, entry.title);
                out.putString("text_" + i, entry.text);
                out.putLong("time_" + i, entry.postTime);
                out.putBoolean("clearable_" + i, entry.clearable);
            }
        }
        return out;
    }

    static Bundle open(Context context, String key) {
        Bundle result = new Bundle();
        Entry entry;
        synchronized (LOCK) {
            entry = findLocked(key);
        }
        if (entry == null) {
            result.putBoolean("ok", false);
            result.putString("message", "通知已不存在");
            return result;
        }
        if (entry.contentIntent != null) {
            try {
                entry.contentIntent.send();
                result.putBoolean("ok", true);
                return result;
            } catch (Throwable ignored) {
            }
        }
        if (context != null && launchApp(context, entry.packageName)) {
            result.putBoolean("ok", true);
            return result;
        }
        result.putBoolean("ok", false);
        result.putString("message", "无法打开对应应用");
        return result;
    }

    static Bundle dismiss(String key) {
        Bundle result = new Bundle();
        Entry entry;
        synchronized (LOCK) {
            entry = findLocked(key);
        }
        if (entry == null) {
            result.putBoolean("ok", false);
            result.putString("message", "通知已不存在");
            return result;
        }
        if (!entry.clearable) {
            result.putBoolean("ok", false);
            result.putString("message", "该通知不可清除");
            return result;
        }
        PlaybackNotificationListener service = listener;
        if (service != null) {
            try {
                service.cancelNotification(entry.key);
            } catch (Throwable ignored) {
            }
        }
        synchronized (LOCK) {
            removeLocked(key);
            revision++;
        }
        result.putBoolean("ok", true);
        return result;
    }

    static List<Entry> entriesForTest() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(entries));
        }
    }

    static Entry map(StatusBarNotification notification) {
        if (notification == null) return null;
        String packageName = notification.getPackageName();
        if (Contract.MODULE_PACKAGE.equals(packageName)) return null;
        Notification data = notification.getNotification();
        if (data == null) return null;
        if ((data.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;
        // Filter the resident foreground-service notifications (system junk
        // like aicr/milink that lives in the shade's folded section), but
        // keep media controls — the shade surfaces those prominently.
        // Importance is NOT used: HyperOS shows user-app notifications even
        // at IMPORTANCE_MIN, so ranking-based filtering diverges from it.
        if (!isMedia(data) && (data.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return null;
        }
        Entry entry = new Entry();
        entry.key = notification.getKey() == null ? "" : notification.getKey();
        if (entry.key.isEmpty()) return null;
        entry.packageName = packageName == null ? "" : packageName;
        entry.title = stringExtra(data, Notification.EXTRA_TITLE);
        entry.text = stringExtra(data, Notification.EXTRA_TEXT);
        if (entry.text.isEmpty()) entry.text = stringExtra(data, Notification.EXTRA_BIG_TEXT);
        entry.postTime = notification.getPostTime();
        entry.clearable = notification.isClearable();
        entry.contentIntent = data.contentIntent;
        return entry;
    }

    private static boolean isMedia(Notification data) {
        if (Notification.CATEGORY_TRANSPORT.equals(data.category)) return true;
        return data.extras != null
                && data.extras.get(Notification.EXTRA_MEDIA_SESSION) != null;
    }

    private static List<StatusBarNotification> activeNotifications(
            PlaybackNotificationListener service) {
        try {
            StatusBarNotification[] active = service.getActiveNotifications();
            List<StatusBarNotification> out = new ArrayList<>();
            if (active != null) Collections.addAll(out, active);
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean launchApp(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String stringExtra(Notification data, String key) {
        if (data.extras == null) return "";
        CharSequence value = data.extras.getCharSequence(key);
        return value == null ? "" : value.toString().replace('\n', ' ').trim();
    }

    private static Entry findLocked(String key) {
        if (key == null) return null;
        for (Entry entry : entries) {
            if (key.equals(entry.key)) return entry;
        }
        return null;
    }

    private static boolean removeLocked(String key) {
        if (key == null) return false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (key.equals(entries.get(i).key)) {
                entries.remove(i);
                return true;
            }
        }
        return false;
    }

    private static void sortLocked() {
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry left, Entry right) {
                return Long.compare(right.postTime, left.postTime);
            }
        });
    }

    private NotificationStateStore() {}
}

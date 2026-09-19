package com.lucky.mixflipouter;

import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.List;

/** Receives the user-approved active MediaSession list without hooking individual music apps. */
public final class PlaybackNotificationListener extends NotificationListenerService {
    private static final long SESSION_WATCHDOG_INTERVAL_MS = 1_500L;
    private static final long NOTIFICATION_REFRESH_DELAY_MS = 150L;
    private static final long NOTIFICATION_RESYNC_DELAY_MS = 250L;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile PlaybackNotificationListener watchdogOwner;
    private static volatile long watchdogRefreshCount;
    private static volatile long watchdogLastRefreshElapsed;
    private static final MediaSessionManager.OnActiveSessionsChangedListener SESSION_LISTENER =
            PlaybackStateStore::updateSessions;
    private static final Runnable NOTIFICATION_REFRESH = () -> {
        PlaybackNotificationListener owner = watchdogOwner;
        if (owner != null && owner.listenerConnected) owner.refreshSessions();
    };
    /**
     * Post/remove callbacks are unreliable on MIUI/HyperOS (missed while the
     * process was dead, rewritten keys), so every change also schedules a
     * debounced full re-seed from getActiveNotifications() to keep the feed
     * identical to the status bar.
     */
    private static final Runnable NOTIFICATION_RESYNC = NotificationStateStore::resyncFromStatusBar;
    private static final Runnable SESSION_WATCHDOG = new Runnable() {
        @Override
        public void run() {
            PlaybackNotificationListener owner = watchdogOwner;
            if (owner == null || !owner.listenerConnected) return;
            owner.refreshSessions();
            watchdogRefreshCount++;
            watchdogLastRefreshElapsed = android.os.SystemClock.elapsedRealtime();
            MAIN_HANDLER.postDelayed(this, SESSION_WATCHDOG_INTERVAL_MS);
        }
    };
    private MediaSessionManager sessionManager;
    private ComponentName listenerComponent;
    private volatile boolean listenerConnected;

    @Override
    public void onCreate() {
        super.onCreate();
        PlaybackArtworkStore.initialize(this);
        NeteaseLyricFetcher.initialize(this);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        listenerConnected = true;
        try {
            if (sessionManager != null) {
                try { sessionManager.removeOnActiveSessionsChangedListener(SESSION_LISTENER); }
                catch (Throwable ignored) {}
            }
            sessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            listenerComponent = new ComponentName(this, PlaybackNotificationListener.class);
            try { sessionManager.removeOnActiveSessionsChangedListener(SESSION_LISTENER); }
            catch (Throwable ignored) {}
            sessionManager.addOnActiveSessionsChangedListener(SESSION_LISTENER, listenerComponent);
            refreshSessions();
            startWatchdog();
        } catch (Throwable error) {
            PlaybackStateStore.clear();
            startWatchdog();
        }
        NotificationStateStore.attach(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        super.onNotificationPosted(notification);
        NotificationStateStore.onPosted(notification);
        scheduleNotificationResync();
        scheduleNotificationRefresh(notification);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification notification) {
        super.onNotificationRemoved(notification);
        NotificationStateStore.onRemoved(notification);
        scheduleNotificationResync();
        scheduleNotificationRefresh(notification);
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
        super.onNotificationRankingUpdate(rankingMap);
        // Rankings can change without post/remove events; re-seed so the
        // feed stays in sync with the shade.
        scheduleNotificationResync();
    }

    @Override
    public void onListenerDisconnected() {
        listenerConnected = false;
        stopWatchdog();
        if (sessionManager != null) {
            try { sessionManager.removeOnActiveSessionsChangedListener(SESSION_LISTENER); }
            catch (Throwable ignored) {}
        }
        PlaybackStateStore.clear();
        // Keep the last known notifications on purpose: the widget keeps
        // showing them during the disconnect window instead of flashing
        // "暂无通知". attach() re-seeds from the live status bar on reconnect.
        NotificationStateStore.detach(this);
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        listenerConnected = false;
        stopWatchdog();
        if (sessionManager != null) {
            try { sessionManager.removeOnActiveSessionsChangedListener(SESSION_LISTENER); }
            catch (Throwable ignored) {}
        }
        PlaybackStateStore.clear();
        super.onDestroy();
    }

    static boolean isConnected() {
        PlaybackNotificationListener owner = watchdogOwner;
        return owner != null && owner.listenerConnected;
    }

    static long watchdogRefreshCount() {
        return watchdogRefreshCount;
    }

    static long watchdogLastRefreshElapsed() {
        return watchdogLastRefreshElapsed;
    }

    private void refreshSessions() {
        MediaSessionManager manager = sessionManager;
        ComponentName component = listenerComponent;
        if (manager == null || component == null) return;
        try {
            List<MediaController> sessions = manager.getActiveSessions(component);
            PlaybackStateStore.updateSessions(sessions);
        } catch (Throwable ignored) {
            try { requestRebind(component); } catch (Throwable ignoredToo) {}
        }
    }

    private void scheduleNotificationResync() {
        MAIN_HANDLER.removeCallbacks(NOTIFICATION_RESYNC);
        MAIN_HANDLER.postDelayed(NOTIFICATION_RESYNC, NOTIFICATION_RESYNC_DELAY_MS);
    }

    private void scheduleNotificationRefresh(StatusBarNotification notification) {
        if (notification == null
                || !Contract.NETEASE_PACKAGE.equals(notification.getPackageName())) return;
        MAIN_HANDLER.removeCallbacks(NOTIFICATION_REFRESH);
        MAIN_HANDLER.postDelayed(NOTIFICATION_REFRESH, NOTIFICATION_REFRESH_DELAY_MS);
    }

    private void startWatchdog() {
        watchdogOwner = this;
        MAIN_HANDLER.removeCallbacks(SESSION_WATCHDOG);
        if (listenerConnected) {
            MAIN_HANDLER.postDelayed(SESSION_WATCHDOG, SESSION_WATCHDOG_INTERVAL_MS);
        }
    }

    private void stopWatchdog() {
        if (watchdogOwner != this) return;
        watchdogOwner = null;
        MAIN_HANDLER.removeCallbacks(NOTIFICATION_REFRESH);
        MAIN_HANDLER.removeCallbacks(NOTIFICATION_RESYNC);
        MAIN_HANDLER.removeCallbacks(SESSION_WATCHDOG);
    }
}

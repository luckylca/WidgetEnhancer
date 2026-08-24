package com.lucky.mixflipouter;

import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.service.notification.NotificationListenerService;

import java.util.List;

/** Receives the user-approved active MediaSession list without hooking individual music apps. */
public final class PlaybackNotificationListener extends NotificationListenerService {
    private MediaSessionManager sessionManager;
    private final MediaSessionManager.OnActiveSessionsChangedListener listener =
            PlaybackStateStore::updateSessions;

    @Override
    public void onCreate() {
        super.onCreate();
        PlaybackArtworkStore.initialize(this);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        try {
            if (sessionManager != null) {
                try { sessionManager.removeOnActiveSessionsChangedListener(listener); } catch (Throwable ignored) {}
            }
            sessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            ComponentName component = new ComponentName(this, PlaybackNotificationListener.class);
            sessionManager.addOnActiveSessionsChangedListener(listener, component);
            List<MediaController> sessions = sessionManager.getActiveSessions(component);
            PlaybackStateStore.updateSessions(sessions);
        } catch (Throwable error) {
            PlaybackStateStore.clear();
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (sessionManager != null) {
            try { sessionManager.removeOnActiveSessionsChangedListener(listener); } catch (Throwable ignored) {}
        }
        PlaybackStateStore.clear();
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        if (sessionManager != null) {
            try { sessionManager.removeOnActiveSessionsChangedListener(listener); } catch (Throwable ignored) {}
        }
        PlaybackStateStore.clear();
        super.onDestroy();
    }
}

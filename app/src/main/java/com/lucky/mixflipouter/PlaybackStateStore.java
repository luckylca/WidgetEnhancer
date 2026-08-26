package com.lucky.mixflipouter;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** In-process MediaSession state shared by the notification listener and guarded provider. */
final class PlaybackStateStore implements PlaybackProvider {
    private static final Object LOCK = new Object();
    private static final PlaybackStateStore INSTANCE = new PlaybackStateStore();
    private static final long SYSTEM_SCAN_INTERVAL_MS = 1_000L;
    private static final Handler CALLBACK_HANDLER = new Handler(Looper.getMainLooper());
    private static final List<MediaController> controllers = new ArrayList<>();
    private static MediaController activeController;
    private static Snapshot snapshot = new Snapshot();
    private static MediaSessionManager sessionManager;
    private static ComponentName listenerComponent;
    private static long lastSystemScanElapsed;
    private static long refreshCount;
    private static long lastRefreshElapsed;

    private static final MediaController.Callback CALLBACK = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { refresh(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { refresh(); }
        @Override public void onSessionDestroyed() { refreshSystemSessions(true); }
    };

    static void initialize(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            sessionManager = (MediaSessionManager) app.getSystemService(
                    Context.MEDIA_SESSION_SERVICE);
            listenerComponent = new ComponentName(app, PlaybackNotificationListener.class);
            lastSystemScanElapsed = 0;
        }
        NeteaseLyricFetcher.initialize(app);
        if (!PlaybackNotificationListener.isConnected()) {
            try { NotificationListenerService.requestRebind(listenerComponent); }
            catch (Throwable ignored) {}
        }
        refreshSystemSessions(true);
    }

    static void updateSessions(List<MediaController> availableControllers) {
        synchronized (LOCK) {
            if (sameSessionsLocked(availableControllers)) {
                activeController = choose(controllers, activeController);
                refreshLocked();
                return;
            }
            unregisterCallbacksLocked();
            controllers.clear();
            if (availableControllers != null) {
                for (MediaController controller : availableControllers) {
                    if (controller != null && !containsSession(controllers, controller)) {
                        controllers.add(controller);
                    }
                }
            }
            registerCallbacksLocked();
            activeController = choose(controllers, activeController);
            refreshLocked();
        }
    }

    static void clear() {
        updateSessions(Collections.emptyList());
    }

    static PlaybackProvider provider() {
        return INSTANCE;
    }

    @Override
    public Bundle snapshot() {
        refreshSystemSessions(false);
        synchronized (LOCK) {
            activeController = choose(controllers, activeController);
            refreshLocked();
            Snapshot value = snapshot;
            Bundle out = new Bundle();
            out.putBoolean("available", value.available);
            out.putString("package", value.packageName);
            out.putString("title", value.title);
            out.putString("artist", value.artist);
            out.putString("album", value.album);
            out.putString("media_id", value.mediaId);
            out.putBoolean("playing", value.playing);
            out.putLong("position", estimatedPosition(value));
            out.putLong("duration", value.duration);
            out.putFloat("speed", value.speed);
            out.putLong("updated_elapsed", value.updatedElapsed);
            out.putLong("refresh_count", refreshCount);
            out.putLong("last_refresh_elapsed", lastRefreshElapsed);
            out.putBoolean("artwork_available", PlaybackArtworkStore.available());
            out.putLong("artwork_revision", PlaybackArtworkStore.revision());
            return out;
        }
    }

    @Override
    public Bundle execute(String action) {
        refreshSystemSessions(true);
        Bundle result = new Bundle();
        synchronized (LOCK) {
            MediaController controller = activeController;
            if (controller == null) {
                result.putBoolean("ok", false);
                result.putString("message", "没有可控制的媒体会话");
                return result;
            }
            try {
                MediaController.TransportControls controls = controller.getTransportControls();
                if (ActionSpec.MEDIA_PLAY_PAUSE.equals(action)) {
                    PlaybackState state = controller.getPlaybackState();
                    if (state != null && state.getState() == PlaybackState.STATE_PLAYING) controls.pause();
                    else controls.play();
                } else if (ActionSpec.MEDIA_PREVIOUS.equals(action)) {
                    controls.skipToPrevious();
                } else if (ActionSpec.MEDIA_NEXT.equals(action)) {
                    controls.skipToNext();
                } else {
                    result.putBoolean("ok", false);
                    result.putString("message", "不支持的媒体动作");
                    return result;
                }
                result.putBoolean("ok", true);
            } catch (Throwable error) {
                result.putBoolean("ok", false);
                result.putString("message", error.getMessage() == null
                        ? "媒体控制失败" : error.getMessage());
            }
            return result;
        }
    }

    private static void refresh() {
        synchronized (LOCK) {
            activeController = choose(controllers, activeController);
            refreshLocked();
        }
    }

    private static void refreshSystemSessions(boolean force) {
        MediaSessionManager manager;
        ComponentName component;
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (!force && now - lastSystemScanElapsed < SYSTEM_SCAN_INTERVAL_MS) return;
            lastSystemScanElapsed = now;
            manager = sessionManager;
            component = listenerComponent;
        }
        if (manager == null || component == null) return;
        try {
            updateSessions(manager.getActiveSessions(component));
        } catch (Throwable ignored) {
            if (!PlaybackNotificationListener.isConnected()) {
                try { NotificationListenerService.requestRebind(component); }
                catch (Throwable ignoredToo) {}
            }
        }
    }

    private static void refreshLocked() {
        MediaController controller = activeController;
        Snapshot next = new Snapshot();
        if (controller != null) {
            next.available = true;
            next.packageName = safe(controller.getPackageName());
            MediaMetadata metadata = controller.getMetadata();
            PlaybackArtworkStore.update(metadata);
            if (metadata != null) {
                next.mediaId = safe(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
                next.title = first(metadata,
                        MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                next.artist = first(metadata,
                        MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                        MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                next.album = first(metadata, MediaMetadata.METADATA_KEY_ALBUM,
                        MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
                next.duration = Math.max(0, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
                NeteaseLyricFetcher.update(next.packageName, metadata);
            }
            PlaybackState state = controller.getPlaybackState();
            if (state != null) {
                next.playing = state.getState() == PlaybackState.STATE_PLAYING;
                next.position = Math.max(0, state.getPosition());
                next.speed = state.getPlaybackSpeed();
                next.updatedElapsed = state.getLastPositionUpdateTime();
            }
        }
        snapshot = next;
        refreshCount++;
        lastRefreshElapsed = SystemClock.elapsedRealtime();
    }

    private static MediaController choose(List<MediaController> availableControllers,
                                          MediaController current) {
        if (availableControllers == null || availableControllers.isEmpty()) return null;
        MediaController chosen = containsSession(availableControllers, current) ? current : null;
        for (MediaController candidate : availableControllers) {
            if (chosen == null) {
                chosen = candidate;
                continue;
            }
            PlaybackState candidateState = playbackState(candidate);
            PlaybackState chosenState = playbackState(chosen);
            if (PlaybackSessionSelector.preferCandidate(
                    isPlaying(candidateState), updatedAt(candidateState),
                    isPlaying(chosenState), updatedAt(chosenState))) {
                chosen = candidate;
            }
        }
        return chosen;
    }

    private static void registerCallbacksLocked() {
        for (MediaController controller : controllers) {
            try { controller.registerCallback(CALLBACK, CALLBACK_HANDLER); }
            catch (Throwable ignored) {}
        }
    }

    private static void unregisterCallbacksLocked() {
        for (MediaController controller : controllers) {
            try { controller.unregisterCallback(CALLBACK); } catch (Throwable ignored) {}
        }
    }

    private static boolean containsSession(List<MediaController> values, MediaController target) {
        if (target == null) return false;
        for (MediaController value : values) {
            if (sameSession(value, target)) return true;
        }
        return false;
    }

    private static boolean sameSessionsLocked(List<MediaController> availableControllers) {
        int availableCount = availableControllers == null ? 0 : availableControllers.size();
        if (controllers.size() != availableCount) return false;
        if (availableControllers == null) return controllers.isEmpty();
        for (MediaController controller : availableControllers) {
            if (controller != null && !containsSession(controllers, controller)) return false;
        }
        return true;
    }

    private static PlaybackState playbackState(MediaController controller) {
        try {
            return controller == null ? null : controller.getPlaybackState();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isPlaying(PlaybackState state) {
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }

    private static long updatedAt(PlaybackState state) {
        return state == null ? 0 : Math.max(0, state.getLastPositionUpdateTime());
    }

    private static boolean sameSession(MediaController left, MediaController right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.getSessionToken().equals(right.getSessionToken());
    }

    private static long estimatedPosition(Snapshot value) {
        if (!value.playing || value.updatedElapsed <= 0) return value.position;
        long elapsed = Math.max(0, SystemClock.elapsedRealtime() - value.updatedElapsed);
        long estimated = value.position + Math.round(elapsed * value.speed);
        return value.duration > 0 ? Math.min(value.duration, Math.max(0, estimated)) : Math.max(0, estimated);
    }

    private static String first(MediaMetadata metadata, String... keys) {
        for (String key : keys) {
            CharSequence value = metadata.getText(key);
            if (value != null && value.length() > 0) return value.toString();
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class Snapshot {
        boolean available;
        String packageName = "";
        String title = "";
        String artist = "";
        String album = "";
        String mediaId = "";
        boolean playing;
        long position;
        long duration;
        float speed = 1f;
        long updatedElapsed;
    }

    private PlaybackStateStore() {}
}

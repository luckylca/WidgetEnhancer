package com.lucky.mixflipouter;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.Collections;
import java.util.List;

/** In-process MediaSession state shared by the notification listener and guarded provider. */
final class PlaybackStateStore implements PlaybackProvider {
    private static final Object LOCK = new Object();
    private static final PlaybackStateStore INSTANCE = new PlaybackStateStore();
    private static MediaController activeController;
    private static Snapshot snapshot = new Snapshot();

    private static final MediaController.Callback CALLBACK = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { refresh(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { refresh(); }
        @Override public void onSessionDestroyed() { updateSessions(Collections.emptyList()); }
    };

    static void updateSessions(List<MediaController> controllers) {
        synchronized (LOCK) {
            MediaController chosen = choose(controllers);
            if (sameSession(activeController, chosen)) {
                refreshLocked();
                return;
            }
            if (activeController != null) {
                try { activeController.unregisterCallback(CALLBACK); } catch (Throwable ignored) {}
            }
            activeController = chosen;
            if (activeController != null) {
                try { activeController.registerCallback(CALLBACK); } catch (Throwable ignored) {}
            }
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
        synchronized (LOCK) {
            Snapshot value = snapshot;
            Bundle out = new Bundle();
            out.putBoolean("available", value.available);
            out.putString("package", value.packageName);
            out.putString("title", value.title);
            out.putString("artist", value.artist);
            out.putString("album", value.album);
            out.putBoolean("playing", value.playing);
            out.putLong("position", estimatedPosition(value));
            out.putLong("duration", value.duration);
            out.putFloat("speed", value.speed);
            out.putLong("updated_elapsed", value.updatedElapsed);
            return out;
        }
    }

    @Override
    public Bundle execute(String action) {
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
            refreshLocked();
        }
    }

    private static void refreshLocked() {
        MediaController controller = activeController;
        Snapshot next = new Snapshot();
        if (controller != null) {
            next.available = true;
            next.packageName = safe(controller.getPackageName());
            MediaMetadata metadata = controller.getMetadata();
            if (metadata != null) {
                next.title = first(metadata,
                        MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                next.artist = first(metadata,
                        MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                        MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                next.album = first(metadata, MediaMetadata.METADATA_KEY_ALBUM,
                        MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
                next.duration = Math.max(0, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
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
    }

    private static MediaController choose(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) return null;
        for (MediaController controller : controllers) {
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) return controller;
        }
        return controllers.get(0);
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
        boolean playing;
        long position;
        long duration;
        float speed = 1f;
        long updatedElapsed;
    }

    private PlaybackStateStore() {}
}

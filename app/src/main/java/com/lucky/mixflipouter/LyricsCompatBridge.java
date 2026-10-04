package com.lucky.mixflipouter;

import android.content.Context;
import android.os.Bundle;

/**
 * SuperLyric-free "compat mode" (兼容模式) for the music widget.
 *
 * When enabled, ANY player with a MediaSession gets lyrics: the bridge takes
 * the session's title / artist / duration, asks OnlineLyricFetcher for a
 * full-song timeline matched against that metadata, and publishes it as a
 * {@link LyricSourcePolicy#COMPAT_SEARCH} payload. The store then resolves
 * previous/current/next rows purely against the MediaSession position.
 *
 * Compat yields to every real publisher: it only requests while no stored
 * payload matches the playing track, and its payloads carry the lowest
 * priority so SuperLyric or the NetEase hook replace them the moment they
 * deliver lines for the same song.
 */
final class LyricsCompatBridge {
    /** A SuperLyric line received within this window keeps compat idle. */
    private static final long SUPERLYRIC_ACTIVE_MS = 15_000L;

    static boolean isEnabled(Context context) {
        if (context == null) return false;
        return context.getApplicationContext()
                .getSharedPreferences(Contract.PREFS, 0)
                .getBoolean(Contract.PREF_LYRICS_COMPAT_MODE, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(Contract.PREFS, 0)
                .edit().putBoolean(Contract.PREF_LYRICS_COMPAT_MODE, enabled).apply();
    }

    /**
     * Called after every get_lyrics_state snapshot while compat mode is on.
     * Cheap and idempotent: OnlineLyricFetcher dedupes per track key.
     */
    static void maybeRequest(Bundle playback, Bundle lyrics) {
        boolean superlyricActive = lyrics != null
                && lyrics.getBoolean("superlyric_receiver_registered")
                && System.currentTimeMillis()
                - lyrics.getLong("superlyric_last_received_at", 0) < SUPERLYRIC_ACTIVE_MS;
        if (!shouldRequest(
                playback != null && playback.getBoolean("available"),
                playback == null ? "" : playback.getString("title", ""),
                lyrics != null && lyrics.getBoolean("current_match"),
                superlyricActive)) return;
        LyricsData.Track track = new LyricsData.Track();
        track.publisherPackage = playback.getString("package", "");
        track.title = playback.getString("title", "");
        track.artist = playback.getString("artist", "");
        track.album = playback.getString("album", "");
        track.mediaId = playback.getString("media_id", "");
        track.duration = Math.max(0, playback.getLong("duration", 0));
        track.rebuildKey(false);
        OnlineLyricFetcher.requestCompat(track);
    }

    /** Pure trigger rules so JVM tests can pin them without Android. */
    static boolean shouldRequest(boolean playbackAvailable, String title,
                                 boolean currentMatch, boolean superlyricActive) {
        if (!playbackAvailable || title == null || title.trim().isEmpty()) return false;
        if (superlyricActive) return false;
        return !currentMatch;
    }

    private LyricsCompatBridge() {
    }
}

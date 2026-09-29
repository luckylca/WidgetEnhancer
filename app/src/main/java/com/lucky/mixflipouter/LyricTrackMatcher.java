package com.lucky.mixflipouter;

/** Conservative identity checks that prevent lyrics from one session leaking into another. */
final class LyricTrackMatcher {
    private static final long DURATION_TOLERANCE_FLOOR_MS = 5_000L;

    static boolean matches(LyricsData.Track lyric, LyricsData.Track playback) {
        if (lyric == null || playback == null) return false;
        if (!lyric.publisherPackage.isEmpty() && !playback.publisherPackage.isEmpty()
                && !lyric.publisherPackage.equals(playback.publisherPackage)) return false;
        String lyricTitle = LyricsData.normalize(lyric.title);
        String playbackTitle = LyricsData.normalize(playback.title);
        if (lyricTitle.isEmpty() || playbackTitle.isEmpty()
                || !lyricTitle.equals(playbackTitle)) return false;
        String lyricArtist = LyricsData.normalize(lyric.artist);
        String playbackArtist = LyricsData.normalize(playback.artist);
        if (!lyricArtist.isEmpty() && !playbackArtist.isEmpty()
                && !lyricArtist.equals(playbackArtist)) return false;
        if (lyric.duration > 0 && playback.duration > 0) {
            long tolerance = Math.max(DURATION_TOLERANCE_FLOOR_MS,
                    Math.max(lyric.duration, playback.duration) / 33L);
            if (Math.abs(lyric.duration - playback.duration) > tolerance) return false;
        }
        return true;
    }

    private LyricTrackMatcher() {
    }
}

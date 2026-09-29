package com.lucky.mixflipouter;

import java.util.List;

/**
 * Conservative candidate selection for online full-lyrics lookups.
 *
 * Wrong lyrics are worse than no lyrics: a candidate is only usable when the
 * normalized title matches exactly, the artist matches when both sides know
 * it, and the duration is within tolerance of the MediaSession duration.
 * Pure JVM logic — no network, no android classes — so unit tests drive it.
 */
final class OnlineLyricMatch {
    private static final long DURATION_TOLERANCE_FLOOR_MS = 5_000L;
    private static final long DURATION_TOLERANCE_RATIO = 20L; // 5%

    /** One searchable result; {@code syncedLyrics} is empty for two-step sources. */
    static final class Candidate {
        String title = "";
        String artist = "";
        long durationMs;
        long id;            // source-specific song id (NetEase), 0 when none
        String syncedLyrics = "";
        String translation = "";
        String romanization = "";
    }

    static boolean acceptable(String title, String artist, long durationMs,
                              Candidate candidate) {
        if (candidate == null) return false;
        String wantedTitle = LyricsData.normalize(title);
        String candidateTitle = LyricsData.normalize(candidate.title);
        if (wantedTitle.isEmpty() || candidateTitle.isEmpty()
                || !wantedTitle.equals(candidateTitle)) return false;
        String wantedArtist = LyricsData.normalize(artist);
        String candidateArtist = LyricsData.normalize(candidate.artist);
        if (!wantedArtist.isEmpty() && !candidateArtist.isEmpty()
                && !candidateArtist.contains(wantedArtist)
                && !wantedArtist.contains(candidateArtist)) return false;
        if (durationMs > 0 && candidate.durationMs > 0) {
            long tolerance = Math.max(DURATION_TOLERANCE_FLOOR_MS,
                    Math.max(durationMs, candidate.durationMs) / DURATION_TOLERANCE_RATIO);
            if (Math.abs(durationMs - candidate.durationMs) > tolerance) return false;
        }
        return true;
    }

    /**
     * Picks the candidate whose duration is closest to the track's; returns -1
     * when none is acceptable. Candidates failing {@link #acceptable} are
     * never selected.
     */
    static int bestCandidate(List<Candidate> candidates,
                             String title, String artist, long durationMs) {
        int best = -1;
        long bestDiff = Long.MAX_VALUE;
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            if (!acceptable(title, artist, durationMs, candidate)) continue;
            long diff = durationMs > 0 && candidate.durationMs > 0
                    ? Math.abs(durationMs - candidate.durationMs) : 0;
            if (diff < bestDiff) {
                bestDiff = diff;
                best = index;
            }
        }
        return best;
    }

    /** CJK titles are far better covered by NetEase than by LRCLIB. */
    static boolean looksCjk(String value) {
        if (value == null) return false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3040 && c <= 0x30FF)
                    || (c >= 0xAC00 && c <= 0xD7AF)) return true;
        }
        return false;
    }

    private OnlineLyricMatch() {
    }
}

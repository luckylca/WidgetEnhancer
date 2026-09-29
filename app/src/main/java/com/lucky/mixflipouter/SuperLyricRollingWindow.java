package com.lucky.mixflipouter;

import java.util.ArrayList;
import java.util.List;

/**
 * Rolling two-line window for SuperLyric 3.3 / API 3.4, which only delivers the
 * CURRENT line — there is no full-song payload, so previous is reconstructed
 * from history and next is never fabricated.
 *
 * Pure JVM logic, no Binder types, so unit tests can drive it directly.
 */
final class SuperLyricRollingWindow {
    private String title = "";
    private LyricsData.Line previous;
    private LyricsData.Line current;

    /**
     * Feeds the newest current line. Returns the ordered rows to publish
     * ([previous, current] or [current]); the last row is always current.
     */
    synchronized List<LyricsData.Line> accept(LyricsData.Line next, String nextTitle) {
        if (next == null) return snapshot();
        String cleanTitle = nextTitle == null ? "" : nextTitle;
        if (!cleanTitle.isEmpty() && !cleanTitle.equals(title)) {
            // New song: history from the old track must not leak into the new one.
            title = cleanTitle;
            previous = null;
            current = null;
        }
        if (current != null && !sameLine(current, next)) {
            // Timing going backwards means a seek or a new song without title
            // info; either way the old current line is not a reliable previous.
            previous = next.start >= current.start ? current : null;
        }
        current = next;
        return snapshot();
    }

    synchronized void reset() {
        title = "";
        previous = null;
        current = null;
    }

    private List<LyricsData.Line> snapshot() {
        ArrayList<LyricsData.Line> out = new ArrayList<>(2);
        if (previous != null) out.add(previous);
        if (current != null) out.add(current);
        return out;
    }

    private static boolean sameLine(LyricsData.Line a, LyricsData.Line b) {
        return a.start == b.start && a.content.equals(b.content);
    }
}

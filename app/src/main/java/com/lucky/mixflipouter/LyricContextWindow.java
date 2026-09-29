package com.lucky.mixflipouter;

import java.util.List;

/**
 * Aligns an online full-song LRC timeline with the publisher's own timeline
 * and resolves previous/current/next rows from it.
 *
 * SuperLyric (or a hook) tells us which line is current on the publisher's
 * clock; the online LRC has its own timestamps that can drift by a constant
 * delta (different release, intro silence, etc.). {@link #calibrate} finds
 * that delta from the known-current line; {@link #resolve} then resolves the
 * window at {@code position + delta}. Pure JVM logic, unit-tested directly.
 */
final class LyricContextWindow {
    /** Returned by {@link #calibrate} when no context line matches. */
    static final long NO_MATCH = Long.MIN_VALUE;

    /**
     * Returns the delta between the context timeline and the publisher
     * timeline: {@code context.start ≈ publisher.start + delta}. Matches by
     * normalized content; repeated lines (choruses) are disambiguated by
     * proximity to the current playback position. Returns {@link #NO_MATCH}
     * when there is no confident match — keeping the last calibration is
     * better than resetting to zero on a lyric-text quirk.
     */
    static long calibrate(List<LyricsData.Line> context, String currentContent,
                          long currentStart, long position) {
        if (context == null || context.isEmpty() || currentContent == null
                || currentContent.isEmpty()) return NO_MATCH;
        String wanted = LyricsData.normalize(currentContent);
        if (wanted.isEmpty()) return NO_MATCH;
        long bestDelta = NO_MATCH;
        long bestDistance = Long.MAX_VALUE;
        for (LyricsData.Line line : context) {
            if (line == null || !wanted.equals(LyricsData.normalize(line.content))) continue;
            long distance = Math.abs(line.start - position);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestDelta = line.start - currentStart;
            }
        }
        return bestDelta;
    }

    /** Resolves the window on the context timeline using the calibrated delta. */
    static LyricLineResolver.Window resolve(List<LyricsData.Line> context,
                                            long position, long delta) {
        long adjusted = delta == NO_MATCH ? position : position + delta;
        if (adjusted < 0) adjusted = 0;
        return LyricLineResolver.resolve(context, adjusted);
    }

    /**
     * Copies publisher-provided extras (逐字 words, translation, secondary)
     * onto the context row when both rows are the same lyric line and the
     * context row lacks them. Returns true when anything was overlaid.
     */
    static boolean overlay(LyricsData.Line contextLine, LyricsData.Line publisherLine) {
        if (contextLine == null || publisherLine == null) return false;
        String contextContent = LyricsData.normalize(contextLine.content);
        String publisherContent = LyricsData.normalize(publisherLine.content);
        if (contextContent.isEmpty() || !contextContent.equals(publisherContent)) return false;
        boolean changed = false;
        if (contextLine.words.isEmpty() && !publisherLine.words.isEmpty()) {
            contextLine.words.addAll(publisherLine.words);
            changed = true;
        }
        if (contextLine.translation.isEmpty() && !publisherLine.translation.isEmpty()) {
            contextLine.translation = publisherLine.translation;
            changed = true;
        }
        if (contextLine.secondary.isEmpty() && !publisherLine.secondary.isEmpty()) {
            contextLine.secondary = publisherLine.secondary;
            changed = true;
        }
        return changed;
    }

    private LyricContextWindow() {
    }
}

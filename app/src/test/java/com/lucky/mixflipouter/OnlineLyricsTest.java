package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JVM tests for the online full-lyrics feature: candidate selection
 * (OnlineLyricMatch) and timeline merge/alignment (LyricContextWindow).
 */
public final class OnlineLyricsTest {

    // ---------------- Candidate selection ----------------

    @Test
    public void acceptsExactTitleArtistAndCloseDuration() {
        OnlineLyricMatch.Candidate candidate = candidate("Café  Song", "The Artist", 184_000);
        assertTrue(OnlineLyricMatch.acceptable("Café Song", "The Artist", 180_000, candidate));
    }

    @Test
    public void rejectsWrongTitleOrDurationMismatch() {
        assertFalse(OnlineLyricMatch.acceptable("Song", "Artist", 180_000,
                candidate("Other song", "Artist", 180_000)));
        assertFalse(OnlineLyricMatch.acceptable("Song", "Artist", 180_000,
                candidate("Song", "Artist", 260_000)));
        assertFalse(OnlineLyricMatch.acceptable("Song", "Artist", 180_000,
                candidate("", "Artist", 180_000)));
    }

    @Test
    public void artistMustOverlapWhenBothSidesKnowIt() {
        assertFalse(OnlineLyricMatch.acceptable("Song", "Artist A", 180_000,
                candidate("Song", "Artist B", 180_000)));
        // Multiple artists on one side still count as a match.
        assertTrue(OnlineLyricMatch.acceptable("Song", "Artist A", 180_000,
                candidate("Song", "Artist A / Artist B", 180_000)));
        // Unknown candidate artist does not disqualify a title+duration match.
        assertTrue(OnlineLyricMatch.acceptable("Song", "Artist A", 180_000,
                candidate("Song", "", 180_000)));
    }

    @Test
    public void bestCandidatePicksClosestDurationAndSkipsUnacceptable() {
        List<OnlineLyricMatch.Candidate> candidates = Arrays.asList(
                candidate("Wrong title", "Artist", 180_000),
                candidate("Song", "Artist", 182_000),
                candidate("Song", "Artist", 181_000));

        assertEquals(2, OnlineLyricMatch.bestCandidate(candidates, "Song", "Artist", 180_000));
        assertEquals(-1, OnlineLyricMatch.bestCandidate(new ArrayList<>(),
                "Song", "Artist", 180_000));
        assertEquals(-1, OnlineLyricMatch.bestCandidate(candidates, "Unknown", "Artist", 180_000));
    }

    @Test
    public void cjkDetectionPrefersNetEaseOrdering() {
        assertTrue(OnlineLyricMatch.looksCjk("晴天"));
        assertTrue(OnlineLyricMatch.looksCjk("Lemon 米津玄師"));
        assertFalse(OnlineLyricMatch.looksCjk("Shape of You"));
        assertFalse(OnlineLyricMatch.looksCjk(null));
    }

    // ---------------- Approximate title matching (compat mode) ----------------

    @Test
    public void bracketedEditionSuffixesStillMatchTheSameSong() {
        // Player says plain title, site decorates with (烟嗓版) — same song.
        assertTrue(OnlineLyricMatch.acceptable("背着风流泪", "文夫", 240_000,
                candidate("背着风流泪 (烟嗓版)", "文夫子", 239_000)));
        // Player decorates, site is plain.
        assertTrue(OnlineLyricMatch.acceptable("情花已谢 (粤语版)", "司司", 200_000,
                candidate("情花已谢", "司司", 201_000)));
        // Full-width brackets too.
        assertTrue(OnlineLyricMatch.acceptable("Song", "Artist", 180_000,
                candidate("Song（抖音热播）", "Artist", 181_000)));
    }

    @Test
    public void bracketStrippingNeverRescuesADifferentSong() {
        // Different core title stays rejected.
        assertFalse(OnlineLyricMatch.acceptable("情花已谢 (粤语版)", "司司", 200_000,
                candidate("情花已谢 (Remix)", "司司", 200_000)));
        // Same core title but a different artist stays rejected.
        assertFalse(OnlineLyricMatch.acceptable("情花已谢 (粤语版)", "司司", 200_000,
                candidate("情花已谢", "青墨", 200_000)));
        // Duration tolerance still applies after stripping.
        assertFalse(OnlineLyricMatch.acceptable("Song", "Artist", 180_000,
                candidate("Song (Live)", "Artist", 260_000)));
    }

    // ---------------- Timeline calibration & window ----------------

    @Test
    public void calibratesDeltaFromTheKnownCurrentLine() {
        List<LyricsData.Line> context = contextLines(10_000, 20_000, 30_000);

        // Publisher's clock says the "line-2" lyric started at 19_200.
        long delta = LyricContextWindow.calibrate(context, "line-2", 19_200, 19_800);

        assertEquals(800, delta);
    }

    @Test
    public void repeatedChorusCalibratesNearestToPlaybackPosition() {
        LyricsData.Line first = line("chorus", 10_000);
        LyricsData.Line middle = line("verse", 20_000);
        LyricsData.Line repeat = line("chorus", 60_000);
        List<LyricsData.Line> context = Arrays.asList(first, middle, repeat);

        long delta = LyricContextWindow.calibrate(context, "chorus", 59_500, 59_800);

        assertEquals(500, delta);
    }

    @Test
    public void unknownContentKeepsPreviousCalibration() {
        List<LyricsData.Line> context = contextLines(10_000, 20_000, 30_000);

        assertEquals(LyricContextWindow.NO_MATCH,
                LyricContextWindow.calibrate(context, "unrelated lyric", 5_000, 5_000));
        assertEquals(LyricContextWindow.NO_MATCH,
                LyricContextWindow.calibrate(context, "", 5_000, 5_000));
        assertEquals(LyricContextWindow.NO_MATCH,
                LyricContextWindow.calibrate(new ArrayList<>(), "line-1", 5_000, 5_000));
    }

    @Test
    public void resolvesPreviousCurrentAndNextFromFullTimeline() {
        List<LyricsData.Line> context = contextLines(10_000, 20_000, 30_000);

        LyricLineResolver.Window window = LyricContextWindow.resolve(context, 20_500, 0);

        assertEquals("line-1", window.previous.content);
        assertEquals("line-2", window.current.content);
        assertEquals("line-3", window.next.content);
    }

    @Test
    public void shiftedPublisherClockStillLandsOnTheRightLine() {
        List<LyricsData.Line> context = contextLines(10_000, 20_000, 30_000);
        long delta = LyricContextWindow.calibrate(context, "line-2", 19_200, 19_800);

        // Same publisher position, context timeline shifted by +800ms.
        LyricLineResolver.Window window = LyricContextWindow.resolve(context, 19_800, delta);

        assertEquals("line-2", window.current.content);
        assertEquals("line-3", window.next.content);
    }

    // ---------------- Publisher extras overlay ----------------

    @Test
    public void overlaysWordsAndTranslationOnlyWhenContentMatches() {
        LyricsData.Line contextLine = line("Hello world", 20_000);
        LyricsData.Line publisherLine = line("Hello world", 19_200);
        publisherLine.translation = "你好世界";
        LyricsData.Word word = new LyricsData.Word();
        word.text = "Hello";
        word.start = 19_200;
        word.end = 19_500;
        publisherLine.words.add(word);

        assertTrue(LyricContextWindow.overlay(contextLine, publisherLine));
        assertEquals("你好世界", contextLine.translation);
        assertEquals(1, contextLine.words.size());

        // Second overlay must not duplicate the words.
        assertFalse(LyricContextWindow.overlay(contextLine, publisherLine));
        assertEquals(1, contextLine.words.size());

        assertFalse(LyricContextWindow.overlay(contextLine, line("different", 19_200)));
        assertFalse(LyricContextWindow.overlay(null, publisherLine));
    }

    private static OnlineLyricMatch.Candidate candidate(String title, String artist,
                                                        long durationMs) {
        OnlineLyricMatch.Candidate candidate = new OnlineLyricMatch.Candidate();
        candidate.title = title;
        candidate.artist = artist;
        candidate.durationMs = durationMs;
        return candidate;
    }

    private static LyricsData.Line line(String content, long start) {
        LyricsData.Line line = new LyricsData.Line();
        line.content = content;
        line.start = start;
        line.end = start + 5_000;
        return line;
    }

    private static List<LyricsData.Line> contextLines(long... starts) {
        LyricsData.Line[] lines = new LyricsData.Line[starts.length];
        for (int index = 0; index < starts.length; index++) {
            lines[index] = line("line-" + (index + 1), starts[index]);
        }
        return Arrays.asList(lines);
    }
}

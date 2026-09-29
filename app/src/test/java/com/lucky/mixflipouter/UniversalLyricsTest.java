package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * JVM-only tests for the SuperLyric 3.3 / API 3.4 compatibility layer.
 * No Binder, no ServiceManager, no live SuperLyric service — see
 * SuperLyricBridge for anything that touches the real service.
 */
public final class UniversalLyricsTest {

    // ---------------- SuperLyric 3.4 normalization ----------------

    @Test
    public void mapsCurrentLineWithWordsTranslationAndSecondary() {
        SuperLyricWord[] words = {
                new SuperLyricWord("Hello", 100, 350),
                new SuperLyricWord(" world", 350, 800)
        };
        SuperLyricData data = new SuperLyricData()
                .setTitle("Song")
                .setArtist("Artist")
                .setAlbum("Album")
                .setLyric(new SuperLyricLine("Hello world", words, 100, 800))
                .setTranslation(new SuperLyricLine("你好世界", 100, 800))
                .setSecondary(new SuperLyricLine("Ni Hao Shi Jie", 100, 800));

        LyricsData.Line line = SuperLyricNormalizer.resolveCurrentLine(data);

        assertEquals("Hello world", line.content);
        assertEquals(100, line.start);
        assertEquals(800, line.end);
        assertEquals("你好世界", line.translation);
        assertEquals("Ni Hao Shi Jie", line.secondary);
        assertEquals(2, line.words.size());
        assertEquals("Hello", line.words.get(0).text);
        assertEquals(100, line.words.get(0).start);
        assertEquals(350, line.words.get(0).end);
    }

    @Test
    public void payloadCarriesPublisherTrackAndRollingRows() {
        SuperLyricData data = new SuperLyricData()
                .setTitle("Song")
                .setArtist("Artist")
                .setLyric(new SuperLyricLine("current line", 900, 1_500));
        LyricsData.Line previous = new LyricsData.Line();
        previous.content = "previous line";
        previous.start = 0;
        previous.end = 900;

        LyricsData.Payload payload = SuperLyricNormalizer.resolve(
                "cn.toside.music.mobile", data,
                Arrays.asList(previous, SuperLyricNormalizer.resolveCurrentLine(data)));

        assertEquals(LyricSourcePolicy.SUPERLYRIC, payload.source);
        assertEquals("cn.toside.music.mobile", payload.track.publisherPackage);
        assertEquals("Song", payload.track.title);
        assertEquals("Artist", payload.track.artist);
        assertEquals("", payload.track.lyricId);
        assertEquals(2, payload.lines.size());
        assertEquals("previous line", payload.lines.get(0).content);
        assertEquals("current line", payload.lines.get(1).content);
        assertEquals(payload.lines.size() - 1, payload.currentLyricIndex);
    }

    @Test
    public void emptyLyricYieldsNoLineAndNoPayload() {
        assertNull(SuperLyricNormalizer.resolveCurrentLine(new SuperLyricData()));
        assertNull(SuperLyricNormalizer.resolveCurrentLine(null));
        assertNull(SuperLyricNormalizer.resolve(
                "com.spotify.music", new SuperLyricData(), Collections.emptyList()));
        assertNull(SuperLyricNormalizer.resolve("com.spotify.music", null,
                Collections.singletonList(new LyricsData.Line())));
    }

    @Test
    public void nullPublisherNormalizesToEmptyPackage() {
        SuperLyricData data = new SuperLyricData()
                .setLyric(new SuperLyricLine("line", 0, 500));
        LyricsData.Line line = SuperLyricNormalizer.resolveCurrentLine(data);

        LyricsData.Payload payload = SuperLyricNormalizer.resolve(
                null, data, Collections.singletonList(line));

        assertEquals("", payload.track.publisherPackage);
        assertEquals("", payload.track.title);
    }

    // ---------------- Rolling previous/current window ----------------

    @Test
    public void rollingWindowTracksPreviousAndCurrentWithoutNext() {
        SuperLyricRollingWindow window = new SuperLyricRollingWindow();

        List<LyricsData.Line> first = window.accept(line("A", 0), "Song");
        assertEquals(1, first.size());
        assertEquals("A", first.get(0).content);

        List<LyricsData.Line> second = window.accept(line("B", 900), "Song");
        assertEquals(2, second.size());
        assertEquals("A", second.get(0).content);
        assertEquals("B", second.get(1).content);

        List<LyricsData.Line> third = window.accept(line("C", 1_800), "Song");
        assertEquals(2, third.size());
        assertEquals("B", third.get(0).content);
        assertEquals("C", third.get(1).content);
    }

    @Test
    public void rollingWindowDropsHistoryOnTitleChange() {
        SuperLyricRollingWindow window = new SuperLyricRollingWindow();
        window.accept(line("A", 0), "Old song");
        window.accept(line("B", 900), "Old song");

        List<LyricsData.Line> rows = window.accept(line("X", 0), "New song");

        assertEquals(1, rows.size());
        assertEquals("X", rows.get(0).content);
    }

    @Test
    public void rollingWindowDropsPreviousWhenTimeGoesBackwards() {
        SuperLyricRollingWindow window = new SuperLyricRollingWindow();
        window.accept(line("A", 5_000), "");

        List<LyricsData.Line> rows = window.accept(line("B", 1_000), "");

        assertEquals(1, rows.size());
        assertEquals("B", rows.get(0).content);
    }

    @Test
    public void rollingWindowKeepsPreviousOnDuplicateCurrent() {
        SuperLyricRollingWindow window = new SuperLyricRollingWindow();
        window.accept(line("A", 0), "Song");
        window.accept(line("B", 900), "Song");

        List<LyricsData.Line> rows = window.accept(line("B", 900), "Song");

        assertEquals(2, rows.size());
        assertEquals("A", rows.get(0).content);
        assertEquals("B", rows.get(1).content);
    }

    // ---------------- Compatibility gate ----------------

    @Test
    public void bridgeConnectsOnlyWithApi34() {
        assertTrue(SuperLyricCompat.shouldConnect(SuperLyricCompat.SUPPORTED_API_VERSION));
        assertTrue(SuperLyricCompat.shouldConnect(34));
        assertFalse(SuperLyricCompat.shouldConnect(35));
        assertFalse(SuperLyricCompat.shouldConnect(33));
        assertFalse(SuperLyricCompat.shouldConnect(-1));
    }

    @Test
    public void compatibilityLabelsMatchSuperLyric33Pairing() {
        assertEquals("3.4", SuperLyricCompat.SUPPORTED_API_LABEL);
        assertEquals("3.4", SuperLyricCompat.versionLabel(34));
        assertEquals("3.5", SuperLyricCompat.versionLabel(35));
        assertTrue(SuperLyricCompat.compatibilityLabel(34).contains("兼容"));
        assertTrue(SuperLyricCompat.compatibilityLabel(35).contains("不兼容"));
    }

    // ---------------- MediaSession identity & source policy ----------------

    @Test
    public void matchesPackageTitleArtistAndReasonableDuration() {
        LyricsData.Track lyric = track("cn.toside.music.mobile", "Café  Song", "The Artist",
                180_000, "", false);
        LyricsData.Track playback = track("cn.toside.music.mobile", "Café Song", "The Artist",
                184_000, "", false);

        assertTrue(LyricTrackMatcher.matches(lyric, playback));
    }

    @Test
    public void rejectsPackageTitleArtistAndDurationMismatches() {
        LyricsData.Track base = track("com.spotify.music", "Song", "Artist", 180_000, "", false);
        assertFalse(LyricTrackMatcher.matches(base,
                track("cn.toside.music.mobile", "Song", "Artist", 180_000, "", false)));
        assertFalse(LyricTrackMatcher.matches(base,
                track("com.spotify.music", "Other song", "Artist", 180_000, "", false)));
        assertFalse(LyricTrackMatcher.matches(base,
                track("com.spotify.music", "Song", "Other artist", 180_000, "", false)));
        assertFalse(LyricTrackMatcher.matches(base,
                track("com.spotify.music", "Song", "Artist", 220_000, "", false)));
    }

    @Test
    public void switchingPlayerOrSongRejectsTheOldLyricSnapshot() {
        LyricsData.Track oldTrack = track("cn.toside.music.mobile", "Old", "Artist",
                180_000, "", false);
        LyricsData.Track newTrack = track("com.tencent.qqmusic", "New", "Artist",
                210_000, "", false);

        assertFalse(LyricTrackMatcher.matches(oldTrack, newTrack));
    }

    @Test
    public void neteaseNumericApiIdIsNotUsedAsUniversalTrackIdentity() {
        LyricsData.Track first = track("com.netease.cloudmusic", "Song", "Artist",
                180_000, "123456789", false);
        LyricsData.Track second = track("com.netease.cloudmusic", "Song", "Artist",
                180_000, "987654321", false);

        assertEquals(first.trackKey, second.trackKey);
    }

    @Test
    public void neteaseFallbackCanStartWhenSuperLyricHasNoSnapshot() {
        LyricsData.Payload fallback = payload("netease-api", "com.netease.cloudmusic");

        assertTrue(LyricSourcePolicy.fallbackPublisherAllowed(
                fallback.source, fallback.track.publisherPackage));
        assertTrue(LyricSourcePolicy.mayReplace(null, fallback));
        assertFalse(LyricSourcePolicy.fallbackPublisherAllowed(
                "netease-api", "cn.toside.music.mobile"));
    }

    @Test
    public void onStopOnlyAppliesToTheMatchingPublisherAndLyricIdentity() {
        assertTrue(LyricSourcePolicy.stopMatches(
                "cn.toside.music.mobile", "lx-id", "cn.toside.music.mobile", "lx-id"));
        assertTrue(LyricSourcePolicy.stopMatches(
                "cn.toside.music.mobile", "lx-id", "cn.toside.music.mobile", ""));
        assertFalse(LyricSourcePolicy.stopMatches(
                "cn.toside.music.mobile", "new-id", "cn.toside.music.mobile", "old-id"));
        assertFalse(LyricSourcePolicy.stopMatches(
                "com.spotify.music", "id", "cn.toside.music.mobile", "id"));
    }

    @Test
    public void legacyNetEaseHookBeatsOnlineFallbackForSameTrack() {
        assertTrue(LyricSourcePolicy.priority("netease-loader")
                > LyricSourcePolicy.priority("netease-api"));
    }

    @Test
    public void superLyricWinsAndCannotBeOverwrittenByLateNetEaseFallback() {
        LyricsData.Payload superLyric = payload("superlyric", "com.netease.cloudmusic");
        LyricsData.Payload fallback = payload("netease-loader", "com.netease.cloudmusic");

        assertTrue(LyricSourcePolicy.mayReplace(fallback, superLyric));
        assertFalse(LyricSourcePolicy.mayReplace(superLyric, fallback));
    }

    @Test
    public void resolvesPreviousCurrentAndNextRows() {
        List<LyricsData.Line> lines = lines(1_000, 2_000, 3_000);

        LyricLineResolver.Window window = LyricLineResolver.resolve(lines, 2_250);

        assertEquals("line-1", window.previous.content);
        assertEquals("line-2", window.current.content);
        assertEquals("line-3", window.next.content);
    }

    @Test
    public void positionBeforeFirstLineHasNoCurrentAndReturnsFirstAsNext() {
        List<LyricsData.Line> lines = lines(1_000, 2_000, 3_000);

        LyricLineResolver.Window window = LyricLineResolver.resolve(lines, 50);

        assertEquals(-1, window.currentIndex);
        assertNull(window.previous);
        assertNull(window.current);
        assertEquals("line-1", window.next.content);
    }

    @Test
    public void positionAfterLastLineKeepsLastAsCurrent() {
        List<LyricsData.Line> lines = lines(1_000, 2_000, 3_000);

        LyricLineResolver.Window window = LyricLineResolver.resolve(lines, 90_000);

        assertEquals("line-2", window.previous.content);
        assertEquals("line-3", window.current.content);
        assertNull(window.next);
    }

    private static LyricsData.Line line(String content, long start) {
        LyricsData.Line line = new LyricsData.Line();
        line.content = content;
        line.start = start;
        line.end = start + 900;
        return line;
    }

    private static LyricsData.Track track(String publisher, String title, String artist,
                                          long duration, String lyricId, boolean preferId) {
        LyricsData.Track track = new LyricsData.Track();
        track.publisherPackage = publisher;
        track.title = title;
        track.artist = artist;
        track.duration = duration;
        track.lyricId = lyricId;
        track.rebuildKey(preferId);
        return track;
    }

    private static LyricsData.Payload payload(String source, String publisher) {
        LyricsData.Payload payload = new LyricsData.Payload();
        payload.source = source;
        payload.track = track(publisher, "Same song", "Artist", 180_000,
                "opaque-song-id", "superlyric".equals(source));
        payload.publishedAt = 100;
        return payload;
    }

    private static List<LyricsData.Line> lines(long... starts) {
        LyricsData.Line[] lines = new LyricsData.Line[starts.length];
        for (int index = 0; index < starts.length; index++) {
            lines[index] = new LyricsData.Line();
            lines[index].content = "line-" + (index + 1);
            lines[index].start = starts[index];
        }
        return Arrays.asList(lines);
    }
}

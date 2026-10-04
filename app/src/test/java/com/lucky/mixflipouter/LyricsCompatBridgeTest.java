package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM tests for the SuperLyric-free compat mode: the trigger rules in
 * LyricsCompatBridge and the source-policy treatment of compat-search
 * payloads (lowest priority, any publisher, never displaces real sources).
 */
public final class LyricsCompatBridgeTest {

    // ---------------- Trigger rules ----------------

    @Test
    public void requestsOnlyWhilePlayingAKnownTitle() {
        assertFalse(LyricsCompatBridge.shouldRequest(false, "Song", false, false));
        assertFalse(LyricsCompatBridge.shouldRequest(true, "", false, false));
        assertFalse(LyricsCompatBridge.shouldRequest(true, "   ", false, false));
        assertFalse(LyricsCompatBridge.shouldRequest(true, null, false, false));
        assertTrue(LyricsCompatBridge.shouldRequest(true, "Song", false, false));
    }

    @Test
    public void staysIdleWhenStoredLyricsAlreadyMatchTheTrack() {
        assertFalse(LyricsCompatBridge.shouldRequest(true, "Song", true, false));
    }

    @Test
    public void staysIdleWhileSuperLyricIsActivelyDelivering() {
        // Even with nothing matched yet, an active SuperLyric feed wins the race.
        assertFalse(LyricsCompatBridge.shouldRequest(true, "Song", false, true));
    }

    @Test
    public void requestsAgainWhenTheTrackChangesAwayFromTheStoredPayload() {
        // Stored payload matches song A; session moved to song B -> mismatch.
        assertTrue(LyricsCompatBridge.shouldRequest(true, "Song B", false, false));
    }

    // ---------------- Source policy ----------------

    @Test
    public void compatSearchIsAllowedFromAnyPublisher() {
        assertTrue(LyricSourcePolicy.fallbackPublisherAllowed(
                LyricSourcePolicy.COMPAT_SEARCH, "com.tencent.qqmusic"));
        assertTrue(LyricSourcePolicy.fallbackPublisherAllowed(
                LyricSourcePolicy.COMPAT_SEARCH, "com.spotify.music"));
        assertTrue(LyricSourcePolicy.fallbackPublisherAllowed(
                LyricSourcePolicy.COMPAT_SEARCH, ""));
    }

    @Test
    public void compatSearchRanksBelowEveryRealPublisher() {
        int compat = LyricSourcePolicy.priority(LyricSourcePolicy.COMPAT_SEARCH);
        assertTrue(compat < LyricSourcePolicy.priority(LyricSourcePolicy.SUPERLYRIC));
        assertTrue(compat < LyricSourcePolicy.priority("netease-hook"));
        assertEquals(compat, LyricSourcePolicy.priority("netease-api"));
        assertTrue(compat > LyricSourcePolicy.priority("unknown-source"));
    }

    @Test
    public void compatSearchNeverDisplacesSuperLyricForTheSameTrack() {
        LyricsData.Payload superLyric = payload("superlyric", "com.netease.cloudmusic");
        LyricsData.Payload compat = payload(LyricSourcePolicy.COMPAT_SEARCH,
                "com.netease.cloudmusic");
        compat.publishedAt = superLyric.publishedAt + 1_000;

        assertFalse(LyricSourcePolicy.mayReplace(superLyric, compat));
        assertTrue(LyricSourcePolicy.mayReplace(compat, superLyric));
    }

    @Test
    public void compatSearchRefreshesItselfAndStartsFromNothing() {
        LyricsData.Payload older = payload(LyricSourcePolicy.COMPAT_SEARCH,
                "com.tencent.qqmusic");
        LyricsData.Payload newer = payload(LyricSourcePolicy.COMPAT_SEARCH,
                "com.tencent.qqmusic");
        newer.publishedAt = older.publishedAt + 1_000;

        assertTrue(LyricSourcePolicy.mayReplace(null, older));
        assertTrue(LyricSourcePolicy.mayReplace(older, newer));
        assertFalse(LyricSourcePolicy.mayReplace(newer, older));
    }

    @Test
    public void compatPayloadBuildsTheSameTrackKeyAsThePlaybackSession() {
        LyricsData.Track fromPlayback = track("Song", "Artist", 180_000);
        LyricsData.Track fromPayload = track("Song", "Artist", 180_000);

        assertEquals(fromPlayback.trackKey, fromPayload.trackKey);
        assertTrue(LyricTrackMatcher.matches(fromPayload, fromPlayback));
    }

    private static LyricsData.Track track(String title, String artist, long duration) {
        LyricsData.Track track = new LyricsData.Track();
        track.publisherPackage = "com.tencent.qqmusic";
        track.title = title;
        track.artist = artist;
        track.duration = duration;
        track.rebuildKey(false);
        return track;
    }

    private static LyricsData.Payload payload(String source, String publisher) {
        LyricsData.Payload payload = new LyricsData.Payload();
        payload.source = source;
        payload.track.publisherPackage = publisher;
        payload.track.title = "Same song";
        payload.track.artist = "Artist";
        payload.track.duration = 180_000;
        payload.track.rebuildKey("superlyric".equals(source));
        payload.publishedAt = 100;
        return payload;
    }
}

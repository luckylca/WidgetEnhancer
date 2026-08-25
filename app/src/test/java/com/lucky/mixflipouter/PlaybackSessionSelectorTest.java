package com.lucky.mixflipouter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlaybackSessionSelectorTest {
    @Test
    public void newerPlayingBilibiliBeatsOlderPlayingNetease() {
        assertTrue(PlaybackSessionSelector.preferCandidate(true, 200, true, 100));
    }

    @Test
    public void newerPlayingNeteaseBeatsStalePlayingBilibili() {
        assertTrue(PlaybackSessionSelector.preferCandidate(true, 300, true, 200));
    }

    @Test
    public void playingAlwaysBeatsPaused() {
        assertTrue(PlaybackSessionSelector.preferCandidate(true, 100, false, 300));
        assertFalse(PlaybackSessionSelector.preferCandidate(false, 400, true, 100));
    }

    @Test
    public void newestPausedSessionWinsWhenNothingIsPlaying() {
        assertTrue(PlaybackSessionSelector.preferCandidate(false, 300, false, 200));
    }

    @Test
    public void exactTieKeepsCurrentSession() {
        assertFalse(PlaybackSessionSelector.preferCandidate(true, 300, true, 300));
        assertFalse(PlaybackSessionSelector.preferCandidate(false, 300, false, 300));
    }
}

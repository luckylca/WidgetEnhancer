package com.lucky.mixflipouter;

/** Pure ordering rule shared by MediaSession selection and local tests. */
final class PlaybackSessionSelector {
    static boolean preferCandidate(boolean candidatePlaying, long candidateUpdated,
                                   boolean selectedPlaying, long selectedUpdated) {
        if (candidatePlaying != selectedPlaying) return candidatePlaying;
        return candidateUpdated > selectedUpdated;
    }

    private PlaybackSessionSelector() {}
}

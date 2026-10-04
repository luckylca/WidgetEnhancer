package com.lucky.mixflipouter;

/** Source ordering and stop-event rules for SuperLyric and the retained NetEase fallbacks. */
final class LyricSourcePolicy {
    static final String SUPERLYRIC = "superlyric";
    /** Compat mode: full-song lyrics found online from MediaSession metadata alone. */
    static final String COMPAT_SEARCH = "compat-search";
    static final String NETEASE_PACKAGE = "com.netease.cloudmusic";

    static int priority(String source) {
        if (SUPERLYRIC.equals(source)) return 3;
        if (source != null && source.startsWith("netease-")
                && !"netease-api".equals(source)) return 2;
        if ("netease-api".equals(source)) return 1;
        if (COMPAT_SEARCH.equals(source)) return 1;
        return 0;
    }

    static boolean fallbackPublisherAllowed(String source, String publisherPackage) {
        return SUPERLYRIC.equals(source)
                || COMPAT_SEARCH.equals(source)
                || (source != null && source.startsWith("netease-")
                && NETEASE_PACKAGE.equals(publisherPackage));
    }

    static boolean mayReplace(LyricsData.Payload current, LyricsData.Payload incoming) {
        if (current == null) return true;
        if (!LyricTrackMatcher.matches(current.track, incoming.track)) return true;
        int currentPriority = priority(current.source);
        int incomingPriority = priority(incoming.source);
        return incomingPriority > currentPriority
                || (incomingPriority == currentPriority
                && incoming.publishedAt >= current.publishedAt);
    }

    static boolean stopMatches(String currentPublisher, String currentLyricId,
                               String stopPublisher, String stopLyricId) {
        if (currentPublisher == null || !currentPublisher.equals(stopPublisher)) return false;
        return stopLyricId == null || stopLyricId.isEmpty()
                || currentLyricId == null || currentLyricId.isEmpty()
                || currentLyricId.equals(stopLyricId);
    }

    private LyricSourcePolicy() {
    }
}

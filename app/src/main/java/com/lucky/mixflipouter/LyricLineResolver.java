package com.lucky.mixflipouter;

import java.util.List;

/** Resolves adjacent lyric rows from the authoritative MediaSession position. */
final class LyricLineResolver {
    static Window resolve(List<LyricsData.Line> lines, long position) {
        int low = 0;
        int high = lines.size() - 1;
        int index = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).start <= position) {
                index = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        Window result = new Window();
        if (index < 0) {
            result.currentIndex = -1;
            result.next = lines.isEmpty() ? null : lines.get(0);
            return result;
        }
        result.currentIndex = index;
        result.previous = index > 0 ? lines.get(index - 1) : null;
        result.current = lines.get(index);
        result.next = index + 1 < lines.size() ? lines.get(index + 1) : null;
        return result;
    }

    static final class Window {
        int currentIndex = -1;
        LyricsData.Line previous;
        LyricsData.Line current;
        LyricsData.Line next;
    }

    private LyricLineResolver() {
    }
}

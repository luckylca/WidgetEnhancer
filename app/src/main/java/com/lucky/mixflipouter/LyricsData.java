package com.lucky.mixflipouter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Player-independent lyric and track records shared by adapters and the persistent store. */
final class LyricsData {
    static final int MAX_LINES = 320;
    static final int MAX_WORDS_PER_LINE = 256;
    static final int MAX_TEXT_LENGTH = 240;

    static final class Track {
        String publisherPackage = "";
        String lyricId = "";
        String title = "";
        String artist = "";
        String album = "";
        String mediaId = "";
        long duration;
        String trackKey = "";

        void rebuildKey(boolean preferLyricId) {
            publisherPackage = clean(publisherPackage, 180);
            lyricId = clean(lyricId, 300);
            title = clean(title, MAX_TEXT_LENGTH);
            artist = clean(artist, MAX_TEXT_LENGTH);
            album = clean(album, MAX_TEXT_LENGTH);
            mediaId = clean(mediaId, 300);
            duration = Math.max(0, duration);
            String identity = preferLyricId && !lyricId.isEmpty()
                    ? "id\n" + publisherPackage + "\n" + lyricId
                    : "meta\n" + publisherPackage + "\n" + normalize(title) + "\n"
                    + normalize(artist) + "\n" + ((duration + 500) / 1_000);
            trackKey = sha256(identity);
        }
    }

    static final class Word {
        String text = "";
        long start;
        long end;
    }

    static final class Line {
        String content = "";
        String translation = "";
        String secondary = "";
        long start;
        long end;
        final ArrayList<Word> words = new ArrayList<>();
    }

    static final class Payload {
        String source = "";
        String state = "";
        long position;
        long duration;
        long lyricOffset;
        long publishedAt;
        int currentLyricIndex = -1;
        boolean publisherActive = true;
        Track track = new Track();
        final ArrayList<Line> lines = new ArrayList<>();
    }

    static String clean(String value, int limit) {
        if (value == null) return "";
        String text = value.replace('\u0000', ' ').trim();
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    static String normalize(String value) {
        String normalized = Normalizer.normalize(clean(value, 2_000), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (!Character.isWhitespace(codePoint)
                    && type != Character.SPACE_SEPARATOR
                    && type != Character.LINE_SEPARATOR
                    && type != Character.PARAGRAPH_SEPARATOR
                    && type != Character.CONNECTOR_PUNCTUATION
                    && type != Character.DASH_PUNCTUATION
                    && type != Character.START_PUNCTUATION
                    && type != Character.END_PUNCTUATION
                    && type != Character.INITIAL_QUOTE_PUNCTUATION
                    && type != Character.FINAL_QUOTE_PUNCTUATION
                    && type != Character.OTHER_PUNCTUATION) {
                out.appendCodePoint(codePoint);
            }
        });
        return out.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) out.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return out.toString();
        } catch (Exception impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private LyricsData() {
    }
}

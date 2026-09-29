package com.lucky.mixflipouter;

import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import java.util.List;

/**
 * Converts SuperLyric 3.3 / API 3.4 Binder records into the module's
 * player-neutral model.
 *
 * API 3.4 only delivers the CURRENT line plus its translation/secondary rows
 * (title/artist/album when the publisher provides them). There is no
 * full-song payload, no lyric id, and no position/duration — those stay with
 * the MediaSession pipeline. Never fabricate data the API does not carry.
 */
final class SuperLyricNormalizer {

    /**
     * Builds a payload from one broadcast. {@code rollingRows} is the ordered
     * [previous?, current] window maintained by the caller; {@code data}
     * supplies the current line, its auxiliary rows and track metadata.
     * Returns null when there is nothing worth publishing.
     */
    static LyricsData.Payload resolve(String publisher, SuperLyricData data,
                                      List<LyricsData.Line> rollingRows) {
        if (data == null || rollingRows == null || rollingRows.isEmpty()) return null;

        LyricsData.Payload payload = new LyricsData.Payload();
        payload.source = LyricSourcePolicy.SUPERLYRIC;
        payload.state = "active";
        payload.publishedAt = System.currentTimeMillis();
        payload.currentLyricIndex = rollingRows.size() - 1;
        payload.track.publisherPackage = LyricsData.clean(publisher, 180);
        payload.track.title = data.hasTitle() ? text(data.getTitle()) : "";
        payload.track.artist = data.hasArtist() ? text(data.getArtist()) : "";
        payload.track.album = data.hasAlbum() ? text(data.getAlbum()) : "";
        for (LyricsData.Line row : rollingRows) {
            if (payload.lines.size() >= LyricsData.MAX_LINES) break;
            if (row != null && hasText(row)) payload.lines.add(row);
        }
        if (payload.lines.isEmpty()) return null;
        payload.currentLyricIndex = payload.lines.size() - 1;
        // Position/duration/track key are filled from MediaSession by the
        // bridge/store; API 3.4 does not carry them.
        payload.track.rebuildKey(false);
        return payload;
    }

    /** Normalizes the current lyric line and merges translation/secondary rows. */
    static LyricsData.Line resolveCurrentLine(SuperLyricData data) {
        if (data == null || !data.hasLyric() || data.getLyric() == null) return null;
        LyricsData.Line line = normalizeLine(data.getLyric());
        if (data.hasTranslation() && data.getTranslation() != null) {
            line.translation = text(data.getTranslation().getText());
        }
        if (data.hasSecondary() && data.getSecondary() != null) {
            line.secondary = text(data.getSecondary().getText());
        }
        return hasText(line) ? line : null;
    }

    static LyricsData.Line normalizeLine(SuperLyricLine source) {
        LyricsData.Line line = new LyricsData.Line();
        line.content = text(source.getText());
        line.start = Math.max(0, source.getStartTime());
        line.end = Math.max(line.start, source.getEndTime());
        SuperLyricWord[] words = source.getWords();
        if (words != null) {
            for (SuperLyricWord sourceWord : words) {
                if (sourceWord == null || line.words.size() >= LyricsData.MAX_WORDS_PER_LINE) break;
                LyricsData.Word word = new LyricsData.Word();
                word.text = text(sourceWord.getWord());
                word.start = Math.max(0, sourceWord.getStartTime());
                word.end = Math.max(word.start, sourceWord.getEndTime());
                if (!word.text.isEmpty()) line.words.add(word);
            }
        }
        return line;
    }

    private static boolean hasText(LyricsData.Line line) {
        return !line.content.isEmpty() || !line.translation.isEmpty() || !line.secondary.isEmpty();
    }

    private static String text(String value) {
        return LyricsData.clean(value, LyricsData.MAX_TEXT_LENGTH);
    }

    private SuperLyricNormalizer() {
    }
}

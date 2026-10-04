package com.lucky.mixflipouter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;

/** Persists player-neutral synchronized lyrics and resolves rows against MediaSession position. */
final class LyricsStateStore implements LyricsProvider {
    private static final String PREF_KEY = "lyrics_snapshot_v2";
    private static final String LEGACY_PREF_KEY = "lyrics_snapshot_v1";
    private static final String SNAPSHOT_FILE = "lyrics-snapshot-v2.json";
    private static final String LEGACY_SNAPSHOT_FILE = "lyrics-snapshot-v1.json";
    /** Rolling broadcasts carry ~2 rows; anything this small asks for full lyrics. */
    private static final int CONTEXT_MIN_LINES = 6;

    private final SharedPreferences preferences;
    private final AtomicFile snapshotFile;
    private final AtomicFile legacySnapshotFile;
    private LyricsData.Payload cached;
    /** Full-song timeline fetched online; keyed to the current track only. */
    private final ArrayList<LyricsData.Line> contextLines = new ArrayList<>();
    private String contextTrackKey = "";
    private long contextDelta = LyricContextWindow.NO_MATCH;

    LyricsStateStore(Context context) {
        preferences = context.getSharedPreferences(Contract.PREFS, 0);
        snapshotFile = new AtomicFile(new File(context.getFilesDir(), SNAPSHOT_FILE));
        legacySnapshotFile = new AtomicFile(new File(context.getFilesDir(), LEGACY_SNAPSHOT_FILE));
    }

    @Override
    @SuppressWarnings("deprecation")
    public synchronized Bundle publish(Bundle raw, Bundle playback) {
        if (raw == null) return failure("歌词数据为空");
        LyricsData.Payload incoming = fromBundle(raw, playback);
        if (incoming.lines.isEmpty()) return failure("没有可用歌词行");
        if (!LyricSourcePolicy.fallbackPublisherAllowed(
                incoming.source, incoming.track.publisherPackage)) {
            return failure("歌词来源与发布者不匹配");
        }
        if (playback != null && playback.getBoolean("available")
                && !LyricTrackMatcher.matches(incoming.track, trackFromPlayback(playback))) {
            return failure("歌词与当前 MediaSession 歌曲不匹配");
        }

        LyricsData.Payload current = load();
        if (!LyricSourcePolicy.mayReplace(current, incoming)) {
            Bundle result = new Bundle();
            result.putBoolean("ok", true);
            result.putBoolean("ignored_lower_priority", true);
            result.putInt("line_count", current.lines.size());
            return result;
        }
        incoming.lines.sort(Comparator.comparingLong(line -> line.start));
        cached = incoming;
        if (!writeSnapshot(incoming)) {
            cached = null;
            return failure("歌词缓存写入失败");
        }
        recalibrate(incoming, playback);
        maybeFetchContext(incoming);
        Bundle result = new Bundle();
        result.putBoolean("ok", true);
        result.putInt("line_count", incoming.lines.size());
        result.putString("source", incoming.source);
        result.putString("publisher", incoming.track.publisherPackage);
        result.putString("lyric_id", incoming.track.lyricId);
        return result;
    }

    @Override
    public synchronized Bundle publishContext(Bundle raw) {
        if (raw == null) return failure("整首歌词数据为空");
        LyricsData.Payload current = load();
        if (current == null) return failure("没有当前歌词");
        String trackKey = LyricsData.clean(raw.getString("track_key", ""), 80);
        if (!trackKey.isEmpty() && !trackKey.equals(current.track.trackKey)) {
            return failure("整首歌词与当前歌曲不匹配");
        }
        LyricsData.Track meta = new LyricsData.Track();
        meta.title = LyricsData.clean(raw.getString("title", ""), LyricsData.MAX_TEXT_LENGTH);
        meta.artist = LyricsData.clean(raw.getString("artist", ""), LyricsData.MAX_TEXT_LENGTH);
        meta.duration = Math.max(0, raw.getLong("duration", 0));
        if (!LyricTrackMatcher.matches(current.track, meta)) {
            return failure("整首歌词与当前歌曲不匹配");
        }
        ArrayList<LyricsData.Line> lines = parseLines(raw);
        if (lines.size() < 3) return failure("整首歌词行数过少");
        lines.sort(Comparator.comparingLong(line -> line.start));
        contextLines.clear();
        contextLines.addAll(lines);
        contextTrackKey = current.track.trackKey;
        contextDelta = LyricContextWindow.NO_MATCH;
        LyricsData.Line currentLine = currentLineOf(current);
        if (currentLine != null) {
            long reference = current.position > 0 ? current.position : currentLine.start;
            contextDelta = LyricContextWindow.calibrate(
                    contextLines, currentLine.content, currentLine.start, reference);
        }
        if (!writeSnapshot(current)) return failure("歌词缓存写入失败");
        Bundle result = new Bundle();
        result.putBoolean("ok", true);
        result.putInt("line_count", lines.size());
        return result;
    }

    @Override
    public synchronized Bundle stop(Bundle raw) {
        String publisher = raw == null ? "" : raw.getString("publisher_package", "");
        String lyricId = raw == null ? "" : raw.getString("lyric_id", "");
        LyricsData.Payload current = load();
        if (current != null && LyricSourcePolicy.SUPERLYRIC.equals(current.source)
                && LyricSourcePolicy.stopMatches(current.track.publisherPackage,
                current.track.lyricId, publisher, lyricId)) {
            current.publisherActive = false;
            current.state = "stopped";
            if (!writeSnapshot(current)) return failure("歌词停止状态保存失败");
            cached = current;
        }
        Bundle result = new Bundle();
        result.putBoolean("ok", true);
        return result;
    }

    @Override
    public synchronized Bundle snapshot(Bundle playback) {
        Bundle out = new Bundle();
        LyricsData.Payload value = load();
        if (value == null) return unavailable(out, "歌词尚未载入");

        out.putString("source", value.source);
        out.putString("publisher", value.track.publisherPackage);
        out.putString("lyric_id", value.track.lyricId);
        out.putString("track_key", value.track.trackKey);
        out.putString("title", value.track.title);
        out.putString("artist", value.track.artist);
        out.putString("album", value.track.album);
        out.putString("state", value.state);
        out.putBoolean("publisher_active", value.publisherActive);
        out.putBoolean("legacy_fallback", isLegacy(value.source));
        out.putLong("published_at", value.publishedAt);
        out.putInt("line_count", value.lines.size());
        boolean contextUsable = !contextLines.isEmpty()
                && contextTrackKey.equals(value.track.trackKey);
        out.putInt("context_line_count", contextUsable ? contextLines.size() : 0);
        out.putInt("current_lyric_index", -1);
        out.putLong("lyric_offset", value.lyricOffset);

        boolean playbackAvailable = playback != null && playback.getBoolean("available");
        if (!playbackAvailable) {
            out.putLong("position", 0);
            out.putLong("duration", 0);
            out.putBoolean("current_match", false);
            out.putString("match_status", "waiting_for_playback");
            return unavailable(out, "没有活动媒体会话");
        }

        LyricsData.Track playbackTrack = trackFromPlayback(playback);
        boolean matches = LyricTrackMatcher.matches(value.track, playbackTrack);
        long position = Math.max(0, playback.getLong("position", 0));
        long duration = Math.max(0, playback.getLong("duration", 0));
        out.putLong("position", position);
        out.putLong("duration", duration);
        out.putBoolean("current_match", matches);
        out.putString("match_status", matches ? "matched" : "mismatch");
        if (!matches) return unavailable(out, "当前歌曲歌词正在载入");

        LyricLineResolver.Window window;
        if (contextUsable) {
            // Full-song timeline fetched online; the publisher's broadcast
            // keeps authority over which line is current via the calibrated
            // delta, and its extras (逐字 words, translation) overlay the row.
            window = LyricContextWindow.resolve(contextLines, position, contextDelta);
            LyricContextWindow.overlay(window.current, currentLineOf(value));
        } else {
            window = LyricLineResolver.resolve(value.lines, position);
        }
        out.putBoolean("available", contextUsable || !value.lines.isEmpty());
        out.putInt("current_lyric_index", window.currentIndex);
        putLine(out, "previous", window.previous);
        putLine(out, "current", window.current);
        putLine(out, "next", window.next);
        out.putLong("current_start", window.current == null ? -1 : window.current.start);
        out.putLong("current_end", window.current == null ? -1 : window.current.end);
        out.putParcelableArrayList("current_words", wordsBundle(window.current));
        return out;
    }

    private LyricsData.Payload fromBundle(Bundle raw, Bundle playback) {
        LyricsData.Payload payload = new LyricsData.Payload();
        payload.source = LyricsData.clean(raw.getString("source", ""), 48);
        if (payload.source.isEmpty()) payload.source = "netease-hook";
        payload.state = LyricsData.clean(raw.getString("state", ""), 80);
        payload.position = Math.max(0, raw.getLong("position", 0));
        payload.duration = Math.max(0, raw.getLong("duration", 0));
        payload.lyricOffset = raw.getLong("lyric_offset", 0);
        payload.currentLyricIndex = raw.getInt("current_lyric_index", -1);
        payload.publishedAt = raw.getLong("published_at", System.currentTimeMillis());
        payload.track.publisherPackage = LyricsData.clean(
                raw.getString("publisher_package", ""), 180);
        if (payload.track.publisherPackage.isEmpty()
                && payload.source.startsWith("netease-")) {
            payload.track.publisherPackage = LyricSourcePolicy.NETEASE_PACKAGE;
        }
        payload.track.lyricId = LyricsData.clean(raw.getString("lyric_id", ""), 300);
        payload.track.title = LyricsData.clean(raw.getString("title", ""),
                LyricsData.MAX_TEXT_LENGTH);
        payload.track.artist = LyricsData.clean(raw.getString("artist", ""),
                LyricsData.MAX_TEXT_LENGTH);
        payload.track.album = LyricsData.clean(raw.getString("album", ""),
                LyricsData.MAX_TEXT_LENGTH);
        payload.track.mediaId = LyricsData.clean(raw.getString("media_id", ""), 300);
        ArrayList<LyricsData.Line> parsedLines = parseLines(raw);
        payload.lines.addAll(parsedLines);

        if (playback != null && playback.getBoolean("available")
                && payload.track.publisherPackage.equals(playback.getString("package", ""))) {
            if (payload.track.title.isEmpty()) {
                payload.track.title = playback.getString("title", "");
            }
            if (payload.track.artist.isEmpty()) {
                payload.track.artist = playback.getString("artist", "");
            }
            if (payload.track.album.isEmpty()) {
                payload.track.album = playback.getString("album", "");
            }
            if (payload.duration <= 0) payload.duration = playback.getLong("duration", 0);
            payload.track.mediaId = playback.getString("media_id", "");
        }
        payload.track.duration = payload.duration;
        payload.track.rebuildKey(LyricSourcePolicy.SUPERLYRIC.equals(payload.source));
        return payload;
    }

    @SuppressWarnings("deprecation")
    private static ArrayList<LyricsData.Line> parseLines(Bundle raw) {
        ArrayList<LyricsData.Line> out = new ArrayList<>();
        ArrayList<Bundle> rawLines = raw.getParcelableArrayList("lines");
        if (rawLines == null) return out;
        for (Bundle rawLine : rawLines) {
            if (rawLine == null || out.size() >= LyricsData.MAX_LINES) break;
            LyricsData.Line line = new LyricsData.Line();
            line.start = Math.max(0, bundleLong(rawLine, "start", 0));
            line.end = Math.max(line.start, bundleLong(rawLine, "end", line.start));
            line.content = LyricsData.clean(rawLine.getString("content", ""),
                    LyricsData.MAX_TEXT_LENGTH);
            line.translation = LyricsData.clean(
                    rawLine.getString("translation", ""), LyricsData.MAX_TEXT_LENGTH);
            line.secondary = LyricsData.clean(rawLine.getString("secondary",
                    rawLine.getString("romanization", "")), LyricsData.MAX_TEXT_LENGTH);
            ArrayList<Bundle> rawWords = rawLine.getParcelableArrayList("words");
            if (rawWords != null) {
                for (Bundle rawWord : rawWords) {
                    if (rawWord == null
                            || line.words.size() >= LyricsData.MAX_WORDS_PER_LINE) break;
                    LyricsData.Word word = new LyricsData.Word();
                    word.text = LyricsData.clean(rawWord.getString("text", ""),
                            LyricsData.MAX_TEXT_LENGTH);
                    word.start = Math.max(0, rawWord.getLong("start", 0));
                    word.end = Math.max(word.start, rawWord.getLong("end", word.start));
                    if (!word.text.isEmpty()) line.words.add(word);
                }
            }
            if (!line.content.isEmpty() || !line.translation.isEmpty()
                    || !line.secondary.isEmpty()) out.add(line);
        }
        return out;
    }

    private static LyricsData.Line currentLineOf(LyricsData.Payload payload) {
        if (payload == null || payload.lines.isEmpty()) return null;
        int index = payload.currentLyricIndex;
        if (index < 0 || index >= payload.lines.size()) index = payload.lines.size() - 1;
        return payload.lines.get(index);
    }

    /** Re-aligns the online timeline whenever the publisher reports a current line. */
    private void recalibrate(LyricsData.Payload incoming, Bundle playback) {
        if (contextLines.isEmpty() || !contextTrackKey.equals(incoming.track.trackKey)) return;
        LyricsData.Line currentLine = currentLineOf(incoming);
        if (currentLine == null) return;
        long position = playback != null && playback.getBoolean("available")
                ? Math.max(0, playback.getLong("position", 0)) : currentLine.start;
        long delta = LyricContextWindow.calibrate(
                contextLines, currentLine.content, currentLine.start, position);
        if (delta != LyricContextWindow.NO_MATCH) contextDelta = delta;
    }

    /** Sparse rolling payloads (SuperLyric 3.4, NetEase hook) ask for full lyrics. */
    private void maybeFetchContext(LyricsData.Payload incoming) {
        if (incoming.track.title.isEmpty()) return;
        if (incoming.lines.size() >= CONTEXT_MIN_LINES) return;
        if (contextTrackKey.equals(incoming.track.trackKey) && !contextLines.isEmpty()) return;
        OnlineLyricFetcher.request(incoming.track);
    }

    private LyricsData.Payload load() {
        if (cached != null) return cached;
        String raw = readFile(snapshotFile);
        if (raw.isEmpty()) raw = readFile(legacySnapshotFile);
        if (raw.isEmpty()) raw = preferences.getString(PREF_KEY, "");
        if (raw == null || raw.isEmpty()) raw = preferences.getString(LEGACY_PREF_KEY, "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            LyricsData.Payload payload = new LyricsData.Payload();
            fromJson(new JSONObject(raw), payload);
            cached = payload;
        } catch (Throwable ignored) {
            cached = null;
        }
        return cached;
    }

    private static String readFile(AtomicFile file) {
        try {
            return file.getBaseFile().isFile()
                    ? new String(file.readFully(), StandardCharsets.UTF_8) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean writeSnapshot(LyricsData.Payload payload) {
        FileOutputStream output = null;
        try {
            JSONObject json = toJson(payload);
            if (!contextLines.isEmpty() && contextTrackKey.equals(payload.track.trackKey)) {
                json.put("context", contextToJson());
            }
            output = snapshotFile.startWrite();
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
            snapshotFile.finishWrite(output);
            return true;
        } catch (Throwable error) {
            if (output != null) snapshotFile.failWrite(output);
            return false;
        }
    }

    private JSONObject contextToJson() throws Exception {
        JSONArray rows = new JSONArray();
        for (LyricsData.Line line : contextLines) rows.put(lineToJson(line));
        return new JSONObject().put("trackKey", contextTrackKey)
                .put("delta", contextDelta == LyricContextWindow.NO_MATCH ? 0 : contextDelta)
                .put("lines", rows);
    }

    private static JSONObject lineToJson(LyricsData.Line line) throws Exception {
        JSONArray words = new JSONArray();
        for (LyricsData.Word word : line.words) {
            words.put(new JSONObject().put("text", word.text)
                    .put("start", word.start).put("end", word.end));
        }
        return new JSONObject().put("content", line.content)
                .put("translation", line.translation).put("secondary", line.secondary)
                .put("start", line.start).put("end", line.end).put("words", words);
    }

    private static LyricsData.Line lineFromJson(JSONObject rawLine) {
        LyricsData.Line line = new LyricsData.Line();
        line.content = LyricsData.clean(rawLine.optString("content", ""),
                LyricsData.MAX_TEXT_LENGTH);
        line.translation = LyricsData.clean(rawLine.optString("translation", ""),
                LyricsData.MAX_TEXT_LENGTH);
        line.secondary = LyricsData.clean(rawLine.optString("secondary",
                rawLine.optString("romanization", "")), LyricsData.MAX_TEXT_LENGTH);
        line.start = Math.max(0, rawLine.optLong("start", 0));
        line.end = Math.max(line.start, rawLine.optLong("end", line.start));
        JSONArray words = rawLine.optJSONArray("words");
        if (words != null) {
            for (int wordIndex = 0; wordIndex < Math.min(
                    words.length(), LyricsData.MAX_WORDS_PER_LINE); wordIndex++) {
                JSONObject rawWord = words.optJSONObject(wordIndex);
                if (rawWord == null) continue;
                LyricsData.Word word = new LyricsData.Word();
                word.text = LyricsData.clean(rawWord.optString("text", ""),
                        LyricsData.MAX_TEXT_LENGTH);
                word.start = Math.max(0, rawWord.optLong("start", 0));
                word.end = Math.max(word.start, rawWord.optLong("end", word.start));
                if (!word.text.isEmpty()) line.words.add(word);
            }
        }
        return line;
    }

    private static JSONObject toJson(LyricsData.Payload payload) throws Exception {
        JSONObject track = new JSONObject()
                .put("publisherPackage", payload.track.publisherPackage)
                .put("lyricId", payload.track.lyricId)
                .put("title", payload.track.title)
                .put("artist", payload.track.artist)
                .put("album", payload.track.album)
                .put("mediaId", payload.track.mediaId)
                .put("duration", payload.track.duration)
                .put("trackKey", payload.track.trackKey);
        JSONArray rows = new JSONArray();
        for (LyricsData.Line line : payload.lines) {
            rows.put(lineToJson(line));
        }
        return new JSONObject().put("schema", 2).put("source", payload.source)
                .put("state", payload.state).put("track", track)
                .put("position", payload.position).put("duration", payload.duration)
                .put("lyricOffset", payload.lyricOffset)
                .put("publishedAt", payload.publishedAt)
                .put("currentLyricIndex", payload.currentLyricIndex)
                .put("publisherActive", payload.publisherActive).put("lines", rows);
    }

    private void fromJson(JSONObject json, LyricsData.Payload payload) {
        payload.source = json.optString("source", "netease-hook");
        payload.state = json.optString("state", "");
        payload.position = Math.max(0, json.optLong("position", 0));
        payload.duration = Math.max(0, json.optLong("duration", 0));
        payload.lyricOffset = json.optLong("lyricOffset", 0);
        payload.publishedAt = Math.max(0, json.optLong("publishedAt", 0));
        payload.currentLyricIndex = json.optInt("currentLyricIndex", -1);
        payload.publisherActive = json.optBoolean("publisherActive", true);
        JSONObject track = json.optJSONObject("track");
        if (track != null) {
            payload.track.publisherPackage = track.optString("publisherPackage", "");
            payload.track.lyricId = track.optString("lyricId", "");
            payload.track.title = track.optString("title", "");
            payload.track.artist = track.optString("artist", "");
            payload.track.album = track.optString("album", "");
            payload.track.mediaId = track.optString("mediaId", "");
            payload.track.duration = Math.max(0, track.optLong("duration", payload.duration));
            payload.track.trackKey = track.optString("trackKey", "");
        } else {
            // Read the v1 NetEase-only snapshot without using its numeric ID as identity.
            payload.track.publisherPackage = LyricSourcePolicy.NETEASE_PACKAGE;
        }
        payload.track.rebuildKey(LyricSourcePolicy.SUPERLYRIC.equals(payload.source)
                && !payload.track.lyricId.isEmpty());
        JSONArray rows = json.optJSONArray("lines");
        if (rows != null) {
            for (int i = 0; i < Math.min(rows.length(), LyricsData.MAX_LINES); i++) {
                JSONObject rawLine = rows.optJSONObject(i);
                if (rawLine == null) continue;
                payload.lines.add(lineFromJson(rawLine));
            }
        }
        JSONObject context = json.optJSONObject("context");
        if (context != null) {
            String key = context.optString("trackKey", "");
            JSONArray contextRows = context.optJSONArray("lines");
            if (!key.isEmpty() && key.equals(payload.track.trackKey) && contextRows != null) {
                contextTrackKey = key;
                long delta = context.optLong("delta", 0);
                contextDelta = delta == 0 ? LyricContextWindow.NO_MATCH : delta;
                contextLines.clear();
                for (int i = 0; i < Math.min(contextRows.length(), LyricsData.MAX_LINES); i++) {
                    JSONObject rawLine = contextRows.optJSONObject(i);
                    if (rawLine == null) continue;
                    contextLines.add(lineFromJson(rawLine));
                }
            }
        }
    }

    private static LyricsData.Track trackFromPlayback(Bundle playback) {
        LyricsData.Track track = new LyricsData.Track();
        track.publisherPackage = playback.getString("package", "");
        track.title = playback.getString("title", "");
        track.artist = playback.getString("artist", "");
        track.album = playback.getString("album", "");
        track.mediaId = playback.getString("media_id", "");
        track.duration = Math.max(0, playback.getLong("duration", 0));
        track.rebuildKey(false);
        return track;
    }

    private static void putLine(Bundle out, String prefix, LyricsData.Line line) {
        out.putString(prefix, line == null ? "" : line.content);
        out.putString(prefix + "_translation", line == null ? "" : line.translation);
        out.putString(prefix + "_romanization", line == null ? "" : line.secondary);
        out.putString(prefix + "_secondary", line == null ? "" : line.secondary);
        out.putLong(prefix + "_start", line == null ? -1 : line.start);
        out.putLong(prefix + "_end", line == null ? -1 : line.end);
    }

    private static ArrayList<Bundle> wordsBundle(LyricsData.Line line) {
        ArrayList<Bundle> result = new ArrayList<>();
        if (line == null) return result;
        for (LyricsData.Word word : line.words) {
            Bundle item = new Bundle();
            item.putString("text", word.text);
            item.putLong("start", word.start);
            item.putLong("end", word.end);
            result.add(item);
        }
        return result;
    }

    private static long bundleLong(Bundle bundle, String key, long fallback) {
        Object value = bundle.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static boolean isLegacy(String source) {
        return source != null && source.startsWith("netease-");
    }

    private static Bundle unavailable(Bundle out, String message) {
        out.putBoolean("available", false);
        out.putString("message", message);
        putLine(out, "previous", null);
        putLine(out, "current", null);
        putLine(out, "next", null);
        out.putLong("current_start", -1);
        out.putLong("current_end", -1);
        out.putParcelableArrayList("current_words", new ArrayList<>());
        return out;
    }

    private static Bundle failure(String message) {
        Bundle result = new Bundle();
        result.putBoolean("ok", false);
        result.putString("message", message);
        return result;
    }
}

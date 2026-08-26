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
import java.util.List;

/** Persists sanitized lyric timing data and resolves current/next lines from playback position. */
final class LyricsStateStore implements LyricsProvider {
    private static final String PREF_KEY = "lyrics_snapshot_v1";
    private static final String SNAPSHOT_FILE = "lyrics-snapshot-v1.json";
    private static final int MAX_LINES = 320;
    private static final int MAX_TEXT_LENGTH = 240;

    private final SharedPreferences preferences;
    private final AtomicFile snapshotFile;
    private Snapshot cached;

    LyricsStateStore(Context context) {
        preferences = context.getSharedPreferences(Contract.PREFS, 0);
        snapshotFile = new AtomicFile(new File(context.getFilesDir(), SNAPSHOT_FILE));
    }

    @Override
    @SuppressWarnings("deprecation")
    public synchronized Bundle publish(Bundle payload) {
        Bundle result = new Bundle();
        if (payload == null) return failure(result, "歌词数据为空");
        long musicId = payload.getLong("music_id", 0);
        ArrayList<Bundle> rawLines = payload.getParcelableArrayList("lines");
        if (musicId <= 0) return failure(result, "歌曲 ID 无效");
        String source = clean(payload.getString("source", "netease"), 40);
        Snapshot existing = load();
        if ("netease-api".equals(source)
                && existing != null
                && existing.musicId == musicId
                && !"netease-api".equals(existing.source)
                && !existing.lines.isEmpty()) {
            result.putBoolean("ok", true);
            result.putInt("line_count", existing.lines.size());
            return result;
        }

        Snapshot next = new Snapshot();
        next.musicId = musicId;
        next.source = source;
        next.state = clean(payload.getString("state", ""), 80);
        next.lyricOffset = payload.getLong("lyric_offset", 0);
        next.publishedAt = System.currentTimeMillis();
        if (rawLines != null) {
            for (Bundle raw : rawLines) {
                if (raw == null || next.lines.size() >= MAX_LINES) break;
                int start = Math.max(0, raw.getInt("start", 0));
                int end = raw.getInt("end", start);
                Line line = new Line();
                line.start = start;
                line.end = Math.max(start, end);
                line.content = clean(raw.getString("content", ""), MAX_TEXT_LENGTH);
                line.translation = clean(raw.getString("translation", ""), MAX_TEXT_LENGTH);
                line.romanization = clean(raw.getString("romanization", ""), MAX_TEXT_LENGTH);
                if (!line.content.isEmpty() || !line.translation.isEmpty()) next.lines.add(line);
            }
        }
        next.lines.sort(Comparator.comparingInt(line -> line.start));
        cached = next;
        if (writeSnapshot(next)) {
            result.putBoolean("ok", true);
            result.putInt("line_count", next.lines.size());
        } else {
            cached = null;
            return failure(result, "歌词缓存写入失败");
        }
        return result;
    }

    @Override
    public synchronized Bundle snapshot(Bundle playback) {
        Bundle out = new Bundle();
        if (playback == null || !playback.getBoolean("available")) {
            return unavailable(out, "没有活动媒体会话");
        }
        if (!Contract.NETEASE_PACKAGE.equals(playback.getString("package", ""))) {
            return unavailable(out, "当前播放器暂不提供同步歌词");
        }
        Snapshot value = load();
        if (value == null || value.lines.isEmpty()) return unavailable(out, "网易云歌词尚未载入");
        long playbackMusicId = parseMusicId(playback.getString("media_id", ""));
        if (playbackMusicId > 0 && playbackMusicId != value.musicId) {
            return unavailable(out, "当前歌曲歌词正在载入");
        }

        long position = Math.max(0, playback.getLong("position", 0));
        int index = findLine(value.lines, position);
        Line previous = index > 0 ? value.lines.get(index - 1) : null;
        Line current = index >= 0 ? value.lines.get(index) : null;
        Line next = index + 1 < value.lines.size() ? value.lines.get(index + 1) : null;
        if (index < 0 && !value.lines.isEmpty()) next = value.lines.get(0);

        out.putBoolean("available", true);
        out.putLong("music_id", value.musicId);
        out.putString("source", value.source);
        out.putString("state", value.state);
        out.putLong("published_at", value.publishedAt);
        out.putLong("position", position);
        out.putLong("lyric_offset", value.lyricOffset);
        out.putInt("line_count", value.lines.size());
        putLine(out, "previous", previous);
        putLine(out, "current", current);
        putLine(out, "next", next);
        return out;
    }

    private Snapshot load() {
        if (cached != null) return cached;
        String raw = "";
        try {
            if (snapshotFile.getBaseFile().isFile()) {
                raw = new String(snapshotFile.readFully(), StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
            raw = "";
        }
        if (raw.isEmpty()) raw = preferences.getString(PREF_KEY, "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            cached = Snapshot.fromJson(new JSONObject(raw));
        } catch (Throwable ignored) {
            cached = null;
        }
        return cached;
    }

    private boolean writeSnapshot(Snapshot value) {
        FileOutputStream output = null;
        try {
            output = snapshotFile.startWrite();
            output.write(value.toJson().toString().getBytes(StandardCharsets.UTF_8));
            snapshotFile.finishWrite(output);
            return true;
        } catch (Throwable error) {
            if (output != null) snapshotFile.failWrite(output);
            return false;
        }
    }

    static int findLine(List<Line> lines, long position) {
        int low = 0;
        int high = lines.size() - 1;
        int found = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).start <= position) {
                found = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return found;
    }

    private static void putLine(Bundle out, String prefix, Line line) {
        out.putString(prefix, line == null ? "" : line.content);
        out.putString(prefix + "_translation", line == null ? "" : line.translation);
        out.putString(prefix + "_romanization", line == null ? "" : line.romanization);
        out.putInt(prefix + "_start", line == null ? -1 : line.start);
        out.putInt(prefix + "_end", line == null ? -1 : line.end);
    }

    private static Bundle unavailable(Bundle out, String message) {
        out.putBoolean("available", false);
        out.putString("message", message);
        return out;
    }

    private static Bundle failure(Bundle out, String message) {
        out.putBoolean("ok", false);
        out.putString("message", message);
        return out;
    }

    private static String clean(String value, int limit) {
        if (value == null) return "";
        String clean = value.replace('\u0000', ' ').trim();
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }

    private static long parseMusicId(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static final class Line {
        int start;
        int end;
        String content = "";
        String translation = "";
        String romanization = "";

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("start", start)
                    .put("end", end)
                    .put("content", content)
                    .put("translation", translation)
                    .put("romanization", romanization);
        }

        static Line fromJson(JSONObject json) {
            Line line = new Line();
            line.start = Math.max(0, json.optInt("start", 0));
            line.end = Math.max(line.start, json.optInt("end", line.start));
            line.content = clean(json.optString("content", ""), MAX_TEXT_LENGTH);
            line.translation = clean(json.optString("translation", ""), MAX_TEXT_LENGTH);
            line.romanization = clean(json.optString("romanization", ""), MAX_TEXT_LENGTH);
            return line;
        }
    }

    private static final class Snapshot {
        long musicId;
        String source = "netease";
        String state = "";
        long lyricOffset;
        long publishedAt;
        final ArrayList<Line> lines = new ArrayList<>();

        JSONObject toJson() throws Exception {
            JSONArray lineArray = new JSONArray();
            for (Line line : lines) lineArray.put(line.toJson());
            return new JSONObject()
                    .put("schema", 1)
                    .put("musicId", musicId)
                    .put("source", source)
                    .put("state", state)
                    .put("lyricOffset", lyricOffset)
                    .put("publishedAt", publishedAt)
                    .put("lines", lineArray);
        }

        static Snapshot fromJson(JSONObject json) {
            Snapshot snapshot = new Snapshot();
            snapshot.musicId = json.optLong("musicId", 0);
            snapshot.source = clean(json.optString("source", "netease"), 40);
            snapshot.state = clean(json.optString("state", ""), 80);
            snapshot.lyricOffset = json.optLong("lyricOffset", 0);
            snapshot.publishedAt = json.optLong("publishedAt", 0);
            JSONArray lines = json.optJSONArray("lines");
            if (lines != null) {
                for (int i = 0; i < lines.length() && i < MAX_LINES; i++) {
                    JSONObject line = lines.optJSONObject(i);
                    if (line != null) snapshot.lines.add(Line.fromJson(line));
                }
            }
            snapshot.lines.sort(Comparator.comparingInt(line -> line.start));
            return snapshot.musicId > 0 ? snapshot : null;
        }
    }
}

package com.lucky.mixflipouter;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches full-song synchronized lyrics online so EVERY player gets
 * previous/current/next rows, not just the rolling two-line window that
 * SuperLyric 3.3 (API 3.4) or the NetEase status-bar hook can deliver.
 *
 * Sources, in order (CJK titles try NetEase first):
 * - LRCLIB (https://lrclib.net) exact-match then search
 * - NetEase cloud search + lyric endpoint
 *
 * Results are validated by OnlineLyricMatch (normalized title, artist,
 * duration tolerance) and published through the provider as context lines;
 * the publisher's own broadcasts stay the authority for which line is
 * current. Any failure degrades silently to the rolling window.
 */
final class OnlineLyricFetcher {
    private static final String LRCLIB_GET = "https://lrclib.net/api/get";
    private static final String LRCLIB_SEARCH = "https://lrclib.net/api/search";
    private static final String NETEASE_SEARCH =
            "https://music.163.com/api/search/get?type=1&limit=10&offset=0&s=";
    private static final String NETEASE_LYRIC =
            "https://music.163.com/api/song/lyric?lv=-1&tv=-1&rv=-1&id=";
    private static final int MAX_RESPONSE_CHARS = 2_000_000;
    /** After a failed lookup, wait this long before retrying the same track. */
    private static final long FAILURE_RETRY_MS = 60_000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mixflip-online-lyrics");
        thread.setDaemon(true);
        return thread;
    });

    private static Context context;
    private static String requestedTrackKey = "";
    private static String lastFailedTrackKey = "";
    private static long lastFailedAt;

    static synchronized void initialize(Context value) {
        context = value == null ? null : value.getApplicationContext();
    }

    /** Requests full lyrics for the track once per track key. */
    static void request(LyricsData.Track track) {
        if (track == null || track.title.isEmpty() || track.trackKey.isEmpty()) return;
        Context app;
        synchronized (OnlineLyricFetcher.class) {
            if (context == null || track.trackKey.equals(requestedTrackKey)) return;
            if (track.trackKey.equals(lastFailedTrackKey)
                    && System.currentTimeMillis() - lastFailedAt < FAILURE_RETRY_MS) return;
            requestedTrackKey = track.trackKey;
            app = context;
        }
        LyricsData.Track snapshot = copy(track);
        EXECUTOR.execute(() -> fetchAndPublish(app, snapshot));
    }

    private static void fetchAndPublish(Context app, LyricsData.Track track) {
        try {
            OnlineLyricMatch.Candidate candidate = firstUsable(track, looksCjk(track));
            if (candidate == null || candidate.syncedLyrics.isEmpty()) {
                allowRetry(track.trackKey);
                return;
            }
            List<LrcParser.Line> parsed = LrcParser.parse(
                    candidate.syncedLyrics, candidate.translation, candidate.romanization);
            if (parsed.isEmpty() || !isCurrentRequest(track.trackKey)) {
                allowRetry(track.trackKey);
                return;
            }
            Bundle payload = new Bundle();
            payload.putString("track_key", track.trackKey);
            payload.putString("title", track.title);
            payload.putString("artist", track.artist);
            payload.putString("album", track.album);
            payload.putLong("duration", track.duration);
            ArrayList<Bundle> lines = new ArrayList<>();
            for (LrcParser.Line value : parsed) {
                if (lines.size() >= LyricsData.MAX_LINES) break;
                Bundle line = new Bundle();
                line.putLong("start", value.start);
                line.putLong("end", value.end);
                line.putString("content", value.content);
                line.putString("translation", value.translation);
                line.putString("secondary", value.romanization);
                lines.add(line);
            }
            payload.putParcelableArrayList("lines", lines);
            Bundle result = app.getContentResolver().call(
                    Contract.PROVIDER_URI, "publish_lyric_context_internal", null, payload);
            if (result == null || !result.getBoolean("ok")) allowRetry(track.trackKey);
        } catch (Throwable ignored) {
            allowRetry(track.trackKey);
        }
    }

    /** Tries the CJK-preferred source order or the LRCLIB-first order. */
    private static OnlineLyricMatch.Candidate firstUsable(LyricsData.Track track,
                                                          boolean neteaseFirst) {
        OnlineLyricMatch.Candidate candidate = neteaseFirst
                ? fromNetease(track) : fromLrclib(track);
        if (candidate != null) return candidate;
        return neteaseFirst ? fromLrclib(track) : fromNetease(track);
    }

    // ---------------- LRCLIB ----------------

    private static OnlineLyricMatch.Candidate fromLrclib(LyricsData.Track track) {
        try {
            String query = "track_name=" + encode(track.title)
                    + "&artist_name=" + encode(track.artist)
                    + (track.album.isEmpty() ? "" : "&album_name=" + encode(track.album))
                    + (track.duration > 0
                    ? "&duration=" + (track.duration / 1_000L) : "");
            JSONObject exact = getJson(LRCLIB_GET + "?" + query);
            if (exact != null) {
                OnlineLyricMatch.Candidate candidate = lrclibCandidate(exact);
                if (OnlineLyricMatch.acceptable(track.title, track.artist,
                        track.duration, candidate)
                        && !candidate.syncedLyrics.isEmpty()) {
                    return candidate;
                }
            }
            JSONArray results = getJsonArray(LRCLIB_SEARCH
                    + "?track_name=" + encode(track.title)
                    + "&artist_name=" + encode(track.artist));
            if (results == null) return null;
            ArrayList<OnlineLyricMatch.Candidate> candidates = new ArrayList<>();
            for (int index = 0; index < Math.min(results.length(), 10); index++) {
                JSONObject item = results.optJSONObject(index);
                if (item == null) continue;
                OnlineLyricMatch.Candidate candidate = lrclibCandidate(item);
                if (!candidate.syncedLyrics.isEmpty()) candidates.add(candidate);
            }
            int best = OnlineLyricMatch.bestCandidate(candidates,
                    track.title, track.artist, track.duration);
            return best < 0 ? null : candidates.get(best);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static OnlineLyricMatch.Candidate lrclibCandidate(JSONObject item) {
        OnlineLyricMatch.Candidate candidate = new OnlineLyricMatch.Candidate();
        candidate.title = item.optString("trackName", "");
        candidate.artist = item.optString("artistName", "");
        candidate.durationMs = (long) (item.optDouble("duration", 0) * 1_000L);
        candidate.syncedLyrics = item.optString("syncedLyrics", "");
        return candidate;
    }

    // ---------------- NetEase ----------------

    private static OnlineLyricMatch.Candidate fromNetease(LyricsData.Track track) {
        try {
            JSONObject root = getJson(NETEASE_SEARCH + encode(track.title + " " + track.artist));
            JSONObject result = root == null ? null : root.optJSONObject("result");
            JSONArray songs = result == null ? null : result.optJSONArray("songs");
            if (songs == null) return null;
            ArrayList<OnlineLyricMatch.Candidate> candidates = new ArrayList<>();
            for (int index = 0; index < songs.length(); index++) {
                JSONObject song = songs.optJSONObject(index);
                if (song == null) continue;
                OnlineLyricMatch.Candidate candidate = new OnlineLyricMatch.Candidate();
                candidate.id = song.optLong("id", 0);
                candidate.title = song.optString("name", "");
                candidate.durationMs = song.optLong("duration", 0);
                JSONArray artists = song.optJSONArray("artists");
                StringBuilder names = new StringBuilder();
                if (artists != null) {
                    for (int a = 0; a < artists.length(); a++) {
                        JSONObject artist = artists.optJSONObject(a);
                        if (artist == null) continue;
                        if (names.length() > 0) names.append(' ');
                        names.append(artist.optString("name", ""));
                    }
                }
                candidate.artist = names.toString();
                candidates.add(candidate);
            }
            int best = OnlineLyricMatch.bestCandidate(candidates,
                    track.title, track.artist, track.duration);
            if (best < 0) return null;
            OnlineLyricMatch.Candidate chosen = candidates.get(best);
            JSONObject lyricRoot = getJson(NETEASE_LYRIC + chosen.id);
            if (lyricRoot == null || lyricRoot.optInt("code", 0) != 200) return null;
            chosen.syncedLyrics = lyric(lyricRoot, "lrc");
            chosen.translation = lyric(lyricRoot, "tlyric");
            chosen.romanization = lyric(lyricRoot, "romalrc");
            return chosen.syncedLyrics.isEmpty() ? null : chosen;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String lyric(JSONObject root, String key) {
        JSONObject value = root.optJSONObject(key);
        return value == null ? "" : value.optString("lyric", "");
    }

    // ---------------- HTTP helpers ----------------

    private static JSONObject getJson(String url) throws Exception {
        String body = get(url);
        if (body.isEmpty()) return null;
        try {
            return new JSONObject(body);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JSONArray getJsonArray(String url) throws Exception {
        String body = get(url);
        if (body.isEmpty()) return null;
        try {
            return new JSONArray(body);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Android) MIXFlipOuter/1.0");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return "";
            return read(connection.getInputStream());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String read(InputStream input) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8_192];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (out.length() + count > MAX_RESPONSE_CHARS) {
                    throw new IllegalStateException("Lyric response is too large");
                }
                out.append(buffer, 0, count);
            }
        }
        return out.toString();
    }

    private static String encode(String value) {
        return Uri.encode(value == null ? "" : value);
    }

    private static boolean looksCjk(LyricsData.Track track) {
        return OnlineLyricMatch.looksCjk(track.title)
                || OnlineLyricMatch.looksCjk(track.artist);
    }

    private static LyricsData.Track copy(LyricsData.Track track) {
        LyricsData.Track copy = new LyricsData.Track();
        copy.publisherPackage = track.publisherPackage;
        copy.lyricId = track.lyricId;
        copy.title = track.title;
        copy.artist = track.artist;
        copy.album = track.album;
        copy.mediaId = track.mediaId;
        copy.duration = track.duration;
        copy.trackKey = track.trackKey;
        return copy;
    }

    private static synchronized boolean isCurrentRequest(String trackKey) {
        return requestedTrackKey.equals(trackKey);
    }

    private static synchronized void allowRetry(String trackKey) {
        if (requestedTrackKey.equals(trackKey)) {
            requestedTrackKey = "";
            lastFailedTrackKey = trackKey;
            lastFailedAt = System.currentTimeMillis();
        }
    }

    private OnlineLyricFetcher() {
    }
}

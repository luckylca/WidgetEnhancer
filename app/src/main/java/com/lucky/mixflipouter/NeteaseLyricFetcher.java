package com.lucky.mixflipouter;

import android.content.Context;
import android.media.MediaMetadata;
import android.os.Bundle;

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

/** Fetches synchronized lyrics when NetEase's version-specific in-process hook is unavailable. */
final class NeteaseLyricFetcher {
    private static final String ENDPOINT =
            "https://music.163.com/api/song/lyric?lv=-1&tv=-1&rv=-1&id=";
    private static final int MAX_RESPONSE_CHARS = 2_000_000;
    private static final int MAX_LINES = 320;
    private static final int MAX_TEXT_LENGTH = 240;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mixflip-netease-lyrics");
        thread.setDaemon(true);
        return thread;
    });

    private static Context context;
    private static long requestedMusicId;

    static synchronized void initialize(Context value) {
        context = value == null ? null : value.getApplicationContext();
    }

    static void update(String packageName, MediaMetadata metadata) {
        if (!Contract.NETEASE_PACKAGE.equals(packageName) || metadata == null) return;
        long musicId = parseMusicId(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
        Context app;
        synchronized (NeteaseLyricFetcher.class) {
            if (musicId <= 0 || musicId == requestedMusicId || context == null) return;
            requestedMusicId = musicId;
            app = context;
        }
        EXECUTOR.execute(() -> fetchAndPublish(app, musicId));
    }

    private static void fetchAndPublish(Context app, long musicId) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ENDPOINT + musicId).openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Referer", "https://music.163.com/");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MIXFlipOuter/1.0");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                allowRetry(musicId);
                return;
            }
            JSONObject root = new JSONObject(read(connection.getInputStream()));
            if (root.optInt("code", 0) != 200) {
                allowRetry(musicId);
                return;
            }
            List<LrcParser.Line> lines = LrcParser.parse(
                    lyric(root, "lrc"), lyric(root, "tlyric"), lyric(root, "romalrc"));
            if (lines.isEmpty() || !isCurrentRequest(musicId)) {
                allowRetry(musicId);
                return;
            }
            Bundle payload = new Bundle();
            payload.putLong("music_id", musicId);
            payload.putString("source", "netease-api");
            payload.putString("state", "Lyric_Loaded_Or_Update");
            ArrayList<Bundle> bundledLines = new ArrayList<>();
            for (LrcParser.Line value : lines) {
                if (bundledLines.size() >= MAX_LINES) break;
                Bundle line = new Bundle();
                line.putInt("start", value.start);
                line.putInt("end", value.end);
                line.putString("content", bounded(value.content));
                line.putString("translation", bounded(value.translation));
                line.putString("romanization", bounded(value.romanization));
                bundledLines.add(line);
            }
            payload.putParcelableArrayList("lines", bundledLines);
            Bundle result = app.getContentResolver().call(
                    Contract.PROVIDER_URI, "publish_lyrics_internal", null, payload);
            if (result == null || !result.getBoolean("ok")) allowRetry(musicId);
        } catch (Throwable ignored) {
            allowRetry(musicId);
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

    private static String lyric(JSONObject root, String key) {
        JSONObject value = root.optJSONObject(key);
        return value == null ? "" : value.optString("lyric", "");
    }

    private static long parseMusicId(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String bounded(String value) {
        if (value == null) return "";
        return value.length() <= MAX_TEXT_LENGTH
                ? value : value.substring(0, MAX_TEXT_LENGTH);
    }

    private static synchronized boolean isCurrentRequest(long musicId) {
        return requestedMusicId == musicId;
    }

    private static synchronized void allowRetry(long musicId) {
        if (requestedMusicId == musicId) requestedMusicId = 0;
    }

    private NeteaseLyricFetcher() {
    }
}

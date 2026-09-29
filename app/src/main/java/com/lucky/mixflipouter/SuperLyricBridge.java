package com.lucky.mixflipouter;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.hchen.superlyricapi.ISuperLyricReceiver;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SuperLyric 3.3 / SuperLyricApi 3.4 compatibility bridge.
 *
 * Hard rules that keep system_server alive:
 * - The receiver is registered LAZILY (ensureStarted), only when the lyric
 *   widget actually polls state. Application startup never registers it.
 * - Registration only happens when the local API version is exactly
 *   SUPPORTED_API_VERSION (3.4), the SuperLyric package is installed and the
 *   service answers. Any other combination is reported but never connected.
 * - Only API 3.4 methods are used: onLyric/onStop carry the current line,
 *   translation and secondary rows. There is no getLatestLyric/cache/allLyrics
 *   here — those are 3.5 and must not be reintroduced without a version gate.
 */
final class SuperLyricBridge {
    private static final String TAG = "MixFlipSuperLyric";
    private static final String SUPERLYRIC_PACKAGE = "com.hchen.superlyric";
    private static final long RETRY_SECONDS = 8;

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final ScheduledExecutorService EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mixflip-superlyric-bridge");
                thread.setDaemon(true);
                return thread;
            });
    private static final Map<String, SuperLyricRollingWindow> WINDOWS = new HashMap<>();

    private static volatile Context appContext;
    private static volatile int localApiVersion;
    private static volatile boolean installed;
    private static volatile boolean serviceAvailable;
    private static volatile boolean receiverRegistered;
    private static volatile String connectionMessage = "未连接（等待歌词请求）";
    private static volatile String lastPublisher = "";
    private static volatile long lastReceivedAt;
    private static volatile long lastStopAt;
    private static volatile ScheduledFuture<?> connectionTask;

    private static final ISuperLyricReceiver RECEIVER = new ISuperLyricReceiver.Stub() {
        @Override
        public void onLyric(String publisher, SuperLyricData data) throws RemoteException {
            if (data == null) return;
            try {
                EXECUTOR.execute(() -> accept(publisher, data));
            } catch (Throwable error) {
                Log.w(TAG, "Unable to queue lyric update", error);
            }
        }

        @Override
        public void onStop(String publisher, SuperLyricData data) throws RemoteException {
            try {
                EXECUTOR.execute(() -> stop(publisher));
            } catch (Throwable error) {
                Log.w(TAG, "Unable to queue lyric stop", error);
            }
        }
    };

    /**
     * Lazy entry point — called when FlipHome's lyric widget polls state.
     * Safe to call repeatedly; the first call decides whether connecting is
     * allowed at all (local API version gate) before any Binder traffic.
     */
    static void ensureStarted(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        localApiVersion = safeLocalApiVersion();
        installed = packageInstalled(appContext);
        if (!SuperLyricCompat.shouldConnect(localApiVersion)) {
            connectionMessage = "API 版本不兼容，已停用 SuperLyric 接收";
            return;
        }
        if (!STARTED.compareAndSet(false, true)) return;
        connectionMessage = "正在检查 SuperLyric 服务";
        connectionTask = EXECUTOR.scheduleWithFixedDelay(SuperLyricBridge::connectOnce,
                0, RETRY_SECONDS, TimeUnit.SECONDS);
    }

    static Bundle status(Context context) {
        Bundle out = new Bundle();
        Context app = context == null ? appContext : context.getApplicationContext();
        boolean packagePresent = app != null ? packageInstalled(app) : installed;
        int api = localApiVersion > 0 ? localApiVersion : safeLocalApiVersion();
        out.putBoolean("superlyric_installed", packagePresent);
        out.putInt("superlyric_api_version", api);
        out.putBoolean("superlyric_compatible", SuperLyricCompat.shouldConnect(api));
        out.putString("superlyric_compatibility_label",
                SuperLyricCompat.compatibilityLabel(api));
        out.putBoolean("superlyric_api_available", serviceAvailable);
        out.putBoolean("superlyric_receiver_registered", receiverRegistered);
        out.putString("superlyric_connection_message", connectionMessage);
        out.putString("superlyric_publisher", lastPublisher);
        out.putLong("superlyric_last_received_at", lastReceivedAt);
        out.putLong("superlyric_last_stop_at", lastStopAt);
        return out;
    }

    private static void connectOnce() {
        try {
            Context context = appContext;
            if (context == null) return;
            if (!SuperLyricCompat.shouldConnect(localApiVersion)) {
                // The gate can only flip if the APK was replaced under us; stop
                // polling immediately and never touch the service again.
                ScheduledFuture<?> task = connectionTask;
                if (task != null) task.cancel(false);
                receiverRegistered = false;
                serviceAvailable = false;
                connectionMessage = "API 版本不兼容，已停用 SuperLyric 接收";
                return;
            }
            installed = packageInstalled(context);
            serviceAvailable = safeIsAvailable();
            if (!serviceAvailable) {
                receiverRegistered = false;
                connectionMessage = installed
                        ? "SuperLyric 已安装，但系统服务不可用"
                        : "未安装 SuperLyric";
                return;
            }
            if (!safeIsRegistered()) {
                try {
                    SuperLyricHelper.registerReceiver(RECEIVER);
                } catch (Throwable error) {
                    Log.w(TAG, "Receiver registration failed", error);
                }
            }
            receiverRegistered = safeIsRegistered();
            connectionMessage = receiverRegistered
                    ? "接收器已注册（API " + SuperLyricCompat.SUPPORTED_API_LABEL + "）"
                    : "SuperLyric 服务可用，正在注册歌词接收器";
        } catch (Throwable error) {
            serviceAvailable = false;
            receiverRegistered = false;
            connectionMessage = "SuperLyric 服务暂不可用";
            Log.w(TAG, "SuperLyric connection check failed", error);
        }
    }

    private static void accept(String publisher, SuperLyricData data) {
        try {
            Context context = appContext;
            if (context == null) return;
            LyricsData.Line currentLine = SuperLyricNormalizer.resolveCurrentLine(data);
            if (currentLine == null) return;

            Bundle playback = PlaybackStateStore.provider().snapshot();
            String publisherPackage = publisher == null ? "" : publisher.trim();
            if (publisherPackage.isEmpty() && playback.getBoolean("available")) {
                publisherPackage = playback.getString("package", "");
            }
            if (publisherPackage.isEmpty()) return;

            String title = data.hasTitle() ? data.getTitle() : "";
            List<LyricsData.Line> rows;
            synchronized (WINDOWS) {
                SuperLyricRollingWindow window = WINDOWS.get(publisherPackage);
                if (window == null) {
                    window = new SuperLyricRollingWindow();
                    WINDOWS.put(publisherPackage, window);
                }
                rows = window.accept(currentLine, title);
            }

            LyricsData.Payload normalized = SuperLyricNormalizer.resolve(
                    publisherPackage, data, rows);
            if (normalized == null) return;
            enrichFromPlayback(normalized, playback);
            if (normalized.track.title.isEmpty()) return;

            Bundle result = context.getContentResolver().call(
                    Contract.PROVIDER_URI, "publish_superlyric_internal", null,
                    toBundle(normalized));
            if (result == null || !result.getBoolean("ok")) {
                String reason = result == null
                        ? "provider unavailable" : result.getString("message", "rejected");
                Log.i(TAG, "Lyric update held: " + reason);
                return;
            }
            lastPublisher = normalized.track.publisherPackage;
            lastReceivedAt = System.currentTimeMillis();
        } catch (Throwable error) {
            Log.w(TAG, "Lyric update failed", error);
        }
    }

    private static void stop(String publisher) {
        try {
            Context context = appContext;
            if (context == null) return;
            Bundle payload = new Bundle();
            payload.putString("publisher_package", publisher == null ? "" : publisher);
            // API 3.4 has no lyric id; the store matches on publisher alone.
            context.getContentResolver().call(
                    Contract.PROVIDER_URI, "stop_superlyric_internal", null, payload);
            synchronized (WINDOWS) {
                if (publisher != null) {
                    SuperLyricRollingWindow window = WINDOWS.get(publisher);
                    if (window != null) window.reset();
                }
            }
            lastStopAt = System.currentTimeMillis();
        } catch (Throwable error) {
            Log.w(TAG, "Lyric stop event failed", error);
        }
    }

    private static void enrichFromPlayback(LyricsData.Payload payload, Bundle playback) {
        if (playback == null || !playback.getBoolean("available")) return;
        String playingPackage = playback.getString("package", "");
        if (!playingPackage.equals(payload.track.publisherPackage)) return;
        if (payload.track.title.isEmpty()) payload.track.title = playback.getString("title", "");
        if (payload.track.artist.isEmpty()) payload.track.artist = playback.getString("artist", "");
        if (payload.track.album.isEmpty()) payload.track.album = playback.getString("album", "");
        if (payload.duration <= 0) payload.duration = playback.getLong("duration", 0);
        if (payload.track.duration <= 0) payload.track.duration = payload.duration;
        payload.position = Math.max(0, playback.getLong("position", 0));
        payload.track.mediaId = playback.getString("media_id", "");
        payload.track.rebuildKey(false);
    }

    private static Bundle toBundle(LyricsData.Payload payload) {
        Bundle out = new Bundle();
        out.putString("source", payload.source);
        out.putString("state", payload.state);
        out.putString("publisher_package", payload.track.publisherPackage);
        out.putString("lyric_id", "");
        out.putString("title", payload.track.title);
        out.putString("artist", payload.track.artist);
        out.putString("album", payload.track.album);
        out.putString("media_id", payload.track.mediaId);
        out.putLong("duration", payload.duration);
        out.putLong("position", payload.position);
        out.putInt("current_lyric_index", payload.currentLyricIndex);
        out.putLong("published_at", payload.publishedAt);

        ArrayList<Bundle> lines = new ArrayList<>(payload.lines.size());
        for (LyricsData.Line lyric : payload.lines) {
            Bundle line = new Bundle();
            line.putLong("start", lyric.start);
            line.putLong("end", lyric.end);
            line.putString("content", lyric.content);
            line.putString("translation", lyric.translation);
            line.putString("secondary", lyric.secondary);
            ArrayList<Bundle> words = new ArrayList<>(lyric.words.size());
            for (LyricsData.Word word : lyric.words) {
                Bundle item = new Bundle();
                item.putString("text", word.text);
                item.putLong("start", word.start);
                item.putLong("end", word.end);
                words.add(item);
            }
            line.putParcelableArrayList("words", words);
            lines.add(line);
        }
        out.putParcelableArrayList("lines", lines);
        return out;
    }

    private static int safeLocalApiVersion() {
        try {
            return SuperLyricHelper.getApiVersion();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean safeIsAvailable() {
        try {
            return SuperLyricHelper.isAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean safeIsRegistered() {
        try {
            return SuperLyricHelper.isReceiverRegistered(RECEIVER);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean packageInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SUPERLYRIC_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private SuperLyricBridge() {
    }
}

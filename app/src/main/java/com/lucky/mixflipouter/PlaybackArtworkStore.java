package com.lucky.mixflipouter;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Copies MediaSession artwork into module-private storage for guarded FlipHome reads. */
final class PlaybackArtworkStore {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mixflip-artwork-cache");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicLong GENERATION = new AtomicLong();
    private static volatile Context context;
    private static volatile String signature = "";
    private static volatile long revision;

    static synchronized void initialize(Context candidate) {
        if (candidate == null) return;
        context = candidate.getApplicationContext();
        File existing = file(context);
        if (existing.isFile()) {
            revision = Math.max(revision, existing.lastModified());
            if (signature.isEmpty()) signature = "__cached__";
        }
    }

    static void update(MediaMetadata metadata) {
        Context activeContext = context;
        if (activeContext == null) return;
        Bitmap artwork = firstArtwork(metadata);
        String nextSignature = artwork == null ? "" : signature(metadata, artwork);
        if (nextSignature.equals(signature)) return;
        signature = nextSignature;
        long generation = GENERATION.incrementAndGet();
        IO.execute(() -> write(activeContext, artwork, generation));
    }

    static File file(Context owner) {
        return new File(owner.getFilesDir(), "playback-artwork.jpg");
    }

    static boolean available() {
        Context activeContext = context;
        return activeContext != null && file(activeContext).isFile();
    }

    static long revision() {
        Context activeContext = context;
        if (activeContext == null) return 0;
        File file = file(activeContext);
        return file.isFile() ? Math.max(1, Math.max(revision, file.lastModified())) : 0;
    }

    private static void write(Context owner, Bitmap artwork, long generation) {
        File destination = file(owner);
        if (artwork == null) {
            if (generation == GENERATION.get() && destination.exists()) destination.delete();
            if (generation == GENERATION.get()) revision = 0;
            return;
        }
        File temporary = new File(owner.getFilesDir(), "playback-artwork.tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            if (!artwork.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                throw new IllegalStateException("Artwork compression failed");
            }
            output.getFD().sync();
            if (generation != GENERATION.get()) {
                temporary.delete();
                return;
            }
            if (destination.exists() && !destination.delete()) {
                throw new IllegalStateException("Old artwork cannot be replaced");
            }
            if (!temporary.renameTo(destination)) {
                throw new IllegalStateException("Artwork cache rename failed");
            }
            revision = Math.max(System.currentTimeMillis(), revision + 1);
            destination.setLastModified(revision);
        } catch (Throwable ignored) {
            temporary.delete();
        }
    }

    private static Bitmap firstArtwork(MediaMetadata metadata) {
        if (metadata == null) return null;
        Bitmap bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bitmap == null) bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bitmap == null) bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return bitmap;
    }

    private static String signature(MediaMetadata metadata, Bitmap bitmap) {
        CharSequence title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence album = metadata.getText(MediaMetadata.METADATA_KEY_ALBUM);
        return String.valueOf(title) + '\n' + album + '\n'
                + bitmap.getWidth() + 'x' + bitmap.getHeight() + ':' + bitmap.getGenerationId();
    }

    private PlaybackArtworkStore() {}
}

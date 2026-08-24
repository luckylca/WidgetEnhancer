package com.lucky.mixflipouter;

import android.annotation.SuppressLint;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SuppressLint("ViewConstructor")
final class MediaWidgetView extends FrameLayout {
    private final WidgetConfig config;
    private final List<LayerBinding> layerBindings = new ArrayList<>();
    private final List<TimeBinding> timeBindings = new ArrayList<>();
    private final List<PlaybackBinding> playbackBindings = new ArrayList<>();
    private final List<ProgressBinding> progressBindings = new ArrayList<>();
    private final List<AlbumBinding> albumBindings = new ArrayList<>();
    private final List<PlaybackBinding> lyricBindings = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextureView videoTexture;
    private MediaPlayer mediaPlayer;
    private boolean playerPrepared;
    private int videoWidth;
    private int videoHeight;
    private final Runnable timeTicker = new Runnable() {
        @Override
        public void run() {
            for (TimeBinding binding : timeBindings) updateTime(binding);
            long now = System.currentTimeMillis();
            mainHandler.postDelayed(this, 60_050L - now % 60_000L);
        }
    };
    private final Runnable playbackTicker = new Runnable() {
        @Override
        public void run() {
            if (!runtimeVisible()) return;
            updatePlaybackBindings();
            mainHandler.postDelayed(this, progressBindings.isEmpty() ? 2_000L : 500L);
        }
    };
    private final Runnable lyricTicker = new Runnable() {
        @Override
        public void run() {
            if (!runtimeVisible()) return;
            updateLyricBindings();
            mainHandler.postDelayed(this, 500L);
        }
    };

    MediaWidgetView(Context context, WidgetConfig config) {
        super(context);
        this.config = config;
        setBackgroundColor(Color.BLACK);
        applyOfficialWidgetOutline();
        createComponentLayers();
    }

    private void createComponentLayers() {
        ArrayList<WidgetComponent> components = new ArrayList<>(config.components);
        components.sort(Comparator.comparingInt(component -> component.zIndex));
        for (WidgetComponent component : components) {
            if (!component.visible) continue;
            View layer = createLayer(component);
            if (layer == null) continue;
            addView(layer, new LayoutParams(1, 1));
            layerBindings.add(new LayerBinding(layer, component));
            applyComponentOutline(layer, component);
        }
        if (layerBindings.isEmpty()) {
            showPlaceholder("MIX Flip 外屏扩展\n请在模块 App 中添加组件");
        }
    }

    private View createLayer(WidgetComponent component) {
        if (WidgetComponent.TYPE_ALBUM_ART.equals(component.type)) {
            ImageView artwork = new ImageView(getContext());
            artwork.setScaleType(imageScaleType(component.fillMode));
            artwork.setBackgroundColor(0xFF303030);
            albumBindings.add(new AlbumBinding(artwork));
            return artwork;
        }
        if (WidgetComponent.TYPE_IMAGE.equals(component.type)) {
            ImageView image = new ImageView(getContext());
            image.setScaleType(imageScaleType(component.fillMode));
            image.setClickable(true);
            image.setFocusable(true);
            image.setOnClickListener(view -> openImageInGallery());
            image.post(() -> image.setImageBitmap(loadScaledBitmap(Math.max(getWidth(), 1208), Math.max(getHeight(), 1392))));
            return image;
        }
        if (WidgetComponent.TYPE_VIDEO.equals(component.type)) {
            if (videoTexture != null) return null;
            videoTexture = new TextureView(getContext());
            videoTexture.setOpaque(true);
            videoTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                    createPlayer(texture);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                    updateVideoTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    releasePlayer();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                }
            });
            return videoTexture;
        }
        if (WidgetComponent.TYPE_PLAYBACK_PROGRESS.equals(component.type)) {
            PlaybackProgressView progress = new PlaybackProgressView(getContext(), component);
            progressBindings.add(new ProgressBinding(progress));
            return progress;
        }
        if (WidgetComponent.TYPE_TEXT.equals(component.type)
                || WidgetComponent.TYPE_TIME.equals(component.type)
                || WidgetComponent.TYPE_SONG_TITLE.equals(component.type)
                || WidgetComponent.TYPE_ARTIST.equals(component.type)
                || WidgetComponent.TYPE_LYRIC_CURRENT.equals(component.type)
                || WidgetComponent.TYPE_LYRIC_NEXT.equals(component.type)) {
            TextView text = new TextView(getContext());
            styleText(text, component);
            if (WidgetComponent.TYPE_TIME.equals(component.type)) {
                TimeBinding binding = new TimeBinding(text, component);
                timeBindings.add(binding);
                updateTime(binding);
            } else if (WidgetComponent.TYPE_SONG_TITLE.equals(component.type)
                    || WidgetComponent.TYPE_ARTIST.equals(component.type)) {
                playbackBindings.add(new PlaybackBinding(text, component));
                text.setText(component.content);
            } else if (WidgetComponent.TYPE_LYRIC_CURRENT.equals(component.type)
                    || WidgetComponent.TYPE_LYRIC_NEXT.equals(component.type)) {
                lyricBindings.add(new PlaybackBinding(text, component));
                text.setText(component.content);
            } else {
                text.setText(component.content);
            }
            return text;
        }
        if (WidgetComponent.TYPE_BUTTON.equals(component.type)) {
            Button button = new Button(getContext());
            button.setAllCaps(false);
            button.setText(component.content);
            button.setTextColor(parseColor(component.color, Color.WHITE));
            button.setGravity(Gravity.CENTER);
            button.setPadding(0, 0, 0, 0);
            button.setOnClickListener(view -> performAction(component.actionType, component.actionValue));
            return button;
        }
        return null;
    }

    private ImageView.ScaleType imageScaleType(String fillMode) {
        if ("contain".equals(fillMode)) return ImageView.ScaleType.CENTER_INSIDE;
        if ("stretch".equals(fillMode)) return ImageView.ScaleType.FIT_XY;
        return ImageView.ScaleType.CENTER_CROP;
    }

    private void styleText(TextView text, WidgetComponent component) {
        text.setTextColor(parseColor(component.color, Color.WHITE));
        text.setGravity(textGravity(component.textAlign));
        text.setIncludeFontPadding(false);
    }

    private int textGravity(String alignment) {
        if ("left".equals(alignment) || "start".equals(alignment)) {
            return Gravity.START | Gravity.CENTER_VERTICAL;
        }
        if ("right".equals(alignment) || "end".equals(alignment)) {
            return Gravity.END | Gravity.CENTER_VERTICAL;
        }
        return Gravity.CENTER;
    }

    private void applyComponentOutline(View layer, WidgetComponent component) {
        if (component.cornerRadius <= 0) return;
        layer.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                float scale = Math.min(
                        getWidth() / WidgetConfig.CANVAS_WIDTH,
                        getHeight() / WidgetConfig.CANVAS_HEIGHT);
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        component.cornerRadius * scale);
            }
        });
        layer.setClipToOutline(true);
    }

    private void applyOfficialWidgetOutline() {
        int radiusId = getResources().getIdentifier(
                "launcher_widget_radius", "dimen", Contract.TARGET_PACKAGE);
        final float radius = radiusId == 0
                ? dp(20) : getResources().getDimension(radiusId);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    private void createPlayer(SurfaceTexture texture) {
        releasePlayer();
        MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;
        Surface surface = new Surface(texture);
        try {
            player.setDataSource(getContext(), Contract.mediaUri(config.id));
            player.setSurface(surface);
            player.setLooping(config.loop);
            float volume = config.mute ? 0f : 1f;
            player.setVolume(volume, volume);
            player.setOnVideoSizeChangedListener((media, width, height) -> {
                videoWidth = width;
                videoHeight = height;
                updateVideoTransform(videoTexture.getWidth(), videoTexture.getHeight());
            });
            player.setOnPreparedListener(media -> {
                if (media != mediaPlayer) return;
                playerPrepared = true;
                maybePlay();
            });
            player.setOnErrorListener((media, what, extra) -> {
                if (media == mediaPlayer) showPlaceholder("视频无法播放\n请尝试 H.264 / MP4");
                return true;
            });
            player.prepareAsync();
        } catch (Throwable error) {
            releasePlayer();
            showPlaceholder("视频无法播放\n请尝试 H.264 / MP4");
        } finally {
            surface.release();
        }
    }

    private void updateVideoTransform(int viewWidth, int viewHeight) {
        if (videoTexture == null || viewWidth <= 0 || viewHeight <= 0
                || videoWidth <= 0 || videoHeight <= 0) return;
        float viewAspect = viewWidth / (float) viewHeight;
        float videoAspect = videoWidth / (float) videoHeight;
        float scaleX = videoAspect > viewAspect ? videoAspect / viewAspect : 1f;
        float scaleY = videoAspect < viewAspect ? viewAspect / videoAspect : 1f;
        Matrix transform = new Matrix();
        transform.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        videoTexture.setTransform(transform);
    }

    private void maybePlay() {
        MediaPlayer player = mediaPlayer;
        if (player == null || !playerPrepared) return;
        boolean visible = runtimeVisible();
        try {
            if (visible) player.start(); else player.pause();
        } catch (IllegalStateException ignored) {
        }
    }

    private void releasePlayer() {
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        playerPrepared = false;
        videoWidth = 0;
        videoHeight = 0;
        if (player == null) return;
        try {
            player.reset();
        } catch (Throwable ignored) {
        }
        player.release();
    }

    private void showPlaceholder(String message) {
        TextView text = new TextView(getContext());
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(18);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(32), dp(32), dp(32), dp(32));
        addView(text, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private void performAction(String type, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        try {
            if (ActionSpec.VOLUME_UP.equals(type)) {
                adjustMusicVolume(AudioManager.ADJUST_RAISE);
            } else if (ActionSpec.VOLUME_DOWN.equals(type)) {
                adjustMusicVolume(AudioManager.ADJUST_LOWER);
            } else if (ActionSpec.MUTE_TOGGLE.equals(type)) {
                adjustMusicVolume(AudioManager.ADJUST_TOGGLE_MUTE);
            } else if (ActionSpec.isFlashlight(type) || ActionSpec.isMediaControl(type)) {
                performProviderAction(type);
            } else if (ActionSpec.LOCK_SCREEN.equals(type)) {
                lockScreen();
            } else if (ActionSpec.OPEN_URI.equals(type)) {
                if (value.isEmpty()) throw new IllegalArgumentException("尚未设置 URI");
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(value));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            } else if (ActionSpec.SEND_BROADCAST.equals(type)) {
                if (value.isEmpty()) throw new IllegalArgumentException("尚未设置广播 action");
                getContext().sendBroadcast(new Intent(value));
            } else {
                if (value.isEmpty()) throw new IllegalArgumentException("尚未选择应用");
                Intent intent;
                if (value.contains("/")) {
                    ComponentName component = ComponentName.unflattenFromString(value);
                    if (component == null) throw new IllegalArgumentException("组件名格式错误");
                    intent = new Intent().setComponent(component);
                } else {
                    intent = getContext().getPackageManager().getLaunchIntentForPackage(value);
                }
                if (intent == null) throw new IllegalArgumentException("找不到应用");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            }
        } catch (Throwable error) {
            Toast.makeText(getContext(), "按键执行失败：" + error.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void adjustMusicVolume(int direction) {
        AudioManager audio = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) throw new IllegalStateException("音量服务不可用");
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
    }

    private void performProviderAction(String type) {
        Bundle result = getContext().getContentResolver().call(
                Contract.PROVIDER_URI, "execute_action", type, null);
        if (result == null || !result.getBoolean("ok")) {
            throw new IllegalStateException(result == null
                    ? "动作服务无响应" : result.getString("message", "动作执行失败"));
        }
    }

    private void lockScreen() {
        new Thread(() -> {
            try {
                // FlipHome is a privileged system package with INJECT_EVENTS on this firmware.
                new Instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_POWER);
            } catch (Throwable error) {
                post(() -> Toast.makeText(getContext(),
                        "锁屏执行失败：" + error.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }, "mixflip-lock-screen").start();
    }

    private void openImageInGallery() {
        Uri media = Contract.mediaUri(config.id);
        Bundle grant = new Bundle();
        grant.putString("package", Contract.GALLERY_PACKAGE);
        try {
            getContext().getContentResolver().call(
                    Contract.PROVIDER_URI, "grant_media", config.id, grant);
        } catch (Throwable error) {
            Toast.makeText(getContext(), "无法授权系统相册读取图片", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(media, config.mimeType)
                .setPackage(Contract.GALLERY_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("MIX Flip Widget image", media));
        try {
            getContext().startActivity(intent);
        } catch (Throwable error) {
            Toast.makeText(getContext(), "无法打开系统相册", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateTime(TimeBinding binding) {
        String pattern = binding.component.content.trim();
        if (pattern.isEmpty()) pattern = "HH:mm";
        try {
            binding.view.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date()));
        } catch (IllegalArgumentException invalidPattern) {
            binding.view.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        }
    }

    private void updatePlaybackBindings() {
        if (playbackBindings.isEmpty() && progressBindings.isEmpty() && albumBindings.isEmpty()) return;
        try {
            Bundle state = getContext().getContentResolver().call(
                    Contract.PROVIDER_URI, "get_playback_state", null, null);
            boolean available = state != null && state.getBoolean("available");
            for (PlaybackBinding binding : playbackBindings) {
                String value;
                if (!available) {
                    value = binding.component.content.isEmpty()
                            ? "暂无播放" : binding.component.content;
                } else if (WidgetComponent.TYPE_ARTIST.equals(binding.component.type)) {
                    value = state.getString("artist", "");
                } else {
                    value = state.getString("title", "");
                }
                binding.view.setText(value == null || value.isEmpty() ? "暂无信息" : value);
            }
            long duration = available ? state.getLong("duration", 0) : 0;
            long position = available ? state.getLong("position", 0) : 0;
            float fraction = duration > 0
                    ? Math.max(0f, Math.min(1f, position / (float) duration)) : 0f;
            for (ProgressBinding binding : progressBindings) binding.view.setProgress(fraction);
            boolean artworkAvailable = available && state.getBoolean("artwork_available");
            long artworkRevision = artworkAvailable ? state.getLong("artwork_revision", 0) : 0;
            for (AlbumBinding binding : albumBindings) {
                if (!artworkAvailable) {
                    binding.requestedRevision = 0;
                    binding.view.setImageDrawable(null);
                } else if (artworkRevision > 0 && binding.requestedRevision != artworkRevision) {
                    loadArtwork(binding, artworkRevision);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void loadArtwork(AlbumBinding binding, long revision) {
        binding.requestedRevision = revision;
        int width = Math.max(256, binding.view.getWidth());
        int height = Math.max(256, binding.view.getHeight());
        new Thread(() -> {
            Bitmap bitmap = loadBitmap(Contract.PLAYBACK_ARTWORK_URI, width, height);
            binding.view.post(() -> {
                if (binding.requestedRevision == revision) binding.view.setImageBitmap(bitmap);
            });
        }, "mixflip-artwork-load").start();
    }

    private void updateLyricBindings() {
        if (lyricBindings.isEmpty()) return;
        try {
            Bundle state = getContext().getContentResolver().call(
                    Contract.PROVIDER_URI, "get_lyrics_state", null, null);
            boolean available = state != null && state.getBoolean("available");
            for (PlaybackBinding binding : lyricBindings) {
                String value;
                if (!available) {
                    value = binding.component.content.isEmpty()
                            ? (WidgetComponent.TYPE_LYRIC_NEXT.equals(binding.component.type)
                            ? "下一句歌词" : "当前歌词")
                            : binding.component.content;
                } else {
                    String key = WidgetComponent.TYPE_LYRIC_NEXT.equals(binding.component.type)
                            ? "next" : "current";
                    value = state.getString(key, "");
                    if (value == null || value.isEmpty()) {
                        value = state.getString(key + "_translation", "");
                    }
                }
                binding.view.setText(value == null || value.isEmpty() ? "…" : value);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean runtimeVisible() {
        return isAttachedToWindow() && getVisibility() == VISIBLE
                && getWindowVisibility() == VISIBLE && isShown();
    }

    private void updatePlaybackSchedule() {
        mainHandler.removeCallbacks(playbackTicker);
        if ((!playbackBindings.isEmpty() || !progressBindings.isEmpty() || !albumBindings.isEmpty())
                && runtimeVisible()) {
            playbackTicker.run();
        }
    }

    private void updateLyricSchedule() {
        mainHandler.removeCallbacks(lyricTicker);
        if (!lyricBindings.isEmpty() && runtimeVisible()) lyricTicker.run();
    }

    private static int parseColor(String value, int fallback) {
        try {
            return Color.parseColor(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void updateLayerLayouts(int width, int height) {
        if (width <= 0 || height <= 0) return;
        float scaleX = width / WidgetConfig.CANVAS_WIDTH;
        float scaleY = height / WidgetConfig.CANVAS_HEIGHT;
        float textScale = Math.min(scaleX, scaleY);
        for (LayerBinding binding : layerBindings) {
            WidgetComponent component = binding.component;
            LayoutParams params = (LayoutParams) binding.view.getLayoutParams();
            params.leftMargin = Math.round(component.x * scaleX);
            params.topMargin = Math.round(component.y * scaleY);
            params.width = Math.max(1, Math.round(component.width * scaleX));
            params.height = Math.max(1, Math.round(component.height * scaleY));
            binding.view.setLayoutParams(params);
            binding.view.setAlpha(component.opacity);
            binding.view.invalidateOutline();

            if (binding.view instanceof TextView) {
                ((TextView) binding.view).setTextSize(
                        TypedValue.COMPLEX_UNIT_PX, component.textSize * textScale);
            }
            if (binding.view instanceof Button) {
                Button button = (Button) binding.view;
                button.setMinWidth(0);
                button.setMinHeight(0);
                GradientDrawable background = new GradientDrawable();
                background.setColor(0xB3202020);
                background.setCornerRadius(component.cornerRadius * textScale);
                background.setStroke(Math.max(1, Math.round(textScale)), 0x55FFFFFF);
                button.setBackground(background);
            }
        }
        updateVideoTransform(videoTexture == null ? 0 : videoTexture.getWidth(),
                videoTexture == null ? 0 : videoTexture.getHeight());
    }

    private Bitmap loadScaledBitmap(int targetWidth, int targetHeight) {
        return loadBitmap(Contract.mediaUri(config.id), targetWidth, targetHeight);
    }

    private Bitmap loadBitmap(Uri uri, int targetWidth, int targetHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / sample > targetWidth * 2 || bounds.outHeight / sample > targetHeight * 2) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Throwable error) {
            return null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!timeBindings.isEmpty()) {
            mainHandler.removeCallbacks(timeTicker);
            timeTicker.run();
        }
        updatePlaybackSchedule();
        updateLyricSchedule();
        if (videoTexture != null && videoTexture.isAvailable() && mediaPlayer == null) {
            createPlayer(videoTexture.getSurfaceTexture());
        } else {
            maybePlay();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mainHandler.removeCallbacks(timeTicker);
        mainHandler.removeCallbacks(playbackTicker);
        mainHandler.removeCallbacks(lyricTicker);
        releasePlayer();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateLayerLayouts(width, height);
        invalidateOutline();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        maybePlay();
        updatePlaybackSchedule();
        updateLyricSchedule();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        maybePlay();
        updatePlaybackSchedule();
        updateLyricSchedule();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class LayerBinding {
        final View view;
        final WidgetComponent component;

        LayerBinding(View view, WidgetComponent component) {
            this.view = view;
            this.component = component;
        }
    }

    private static final class TimeBinding {
        final TextView view;
        final WidgetComponent component;

        TimeBinding(TextView view, WidgetComponent component) {
            this.view = view;
            this.component = component;
        }
    }

    private static final class PlaybackBinding {
        final TextView view;
        final WidgetComponent component;

        PlaybackBinding(TextView view, WidgetComponent component) {
            this.view = view;
            this.component = component;
        }
    }

    private static final class ProgressBinding {
        final PlaybackProgressView view;

        ProgressBinding(PlaybackProgressView view) {
            this.view = view;
        }
    }

    private static final class AlbumBinding {
        final ImageView view;
        volatile long requestedRevision = -1;

        AlbumBinding(ImageView view) {
            this.view = view;
        }
    }

    private static final class PlaybackProgressView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int progressColor;
        private float progress;

        PlaybackProgressView(Context context, WidgetComponent component) {
            super(context);
            progressColor = parseColor(component.color, Color.WHITE);
        }

        void setProgress(float value) {
            progress = Math.max(0f, Math.min(1f, value));
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float barHeight = Math.min(getHeight(),
                    5f * getResources().getDisplayMetrics().density);
            float top = (getHeight() - barHeight) / 2f;
            RectF track = new RectF(0, top, getWidth(), top + barHeight);
            paint.setColor(0x55FFFFFF);
            canvas.drawRoundRect(track, barHeight / 2f, barHeight / 2f, paint);
            paint.setColor(progressColor);
            RectF fill = new RectF(0, top, getWidth() * progress, top + barHeight);
            canvas.drawRoundRect(fill, barHeight / 2f, barHeight / 2f, paint);
        }
    }
}

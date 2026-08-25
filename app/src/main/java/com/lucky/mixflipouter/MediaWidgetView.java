package com.lucky.mixflipouter;

import android.annotation.SuppressLint;
import android.app.Instrumentation;
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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SuppressLint("ViewConstructor")
final class MediaWidgetView extends FrameLayout {
    private static final long VOLUME_REPEAT_INTERVAL_MS = 180L;

    private final WidgetConfig config;
    private final List<LayerBinding> layerBindings = new ArrayList<>();
    private final List<TimeBinding> timeBindings = new ArrayList<>();
    private final List<PlaybackBinding> playbackBindings = new ArrayList<>();
    private final List<ProgressBinding> progressBindings = new ArrayList<>();
    private final List<AlbumBinding> albumBindings = new ArrayList<>();
    private final List<PlaybackBinding> lyricBindings = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final GestureDetector musicGestures;
    private final ButtonLayoutEngine.Layout shortcutLayout;
    private final boolean interactive;
    private final int touchSlop;
    private float touchDownX;
    private float touchDownY;
    private boolean yieldingToHost;
    private boolean volumeRepeatActive;
    private String repeatingVolumeAction;
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
    private final Runnable volumeRepeat = new Runnable() {
        @Override
        public void run() {
            if (!volumeRepeatActive || repeatingVolumeAction == null) return;
            performAction(repeatingVolumeAction, "");
            mainHandler.postDelayed(this, VOLUME_REPEAT_INTERVAL_MS);
        }
    };

    MediaWidgetView(Context context, WidgetConfig config) {
        this(context, config, true);
    }

    MediaWidgetView(Context context, WidgetConfig config, boolean interactive) {
        super(context);
        this.config = config;
        this.interactive = interactive;
        String widgetType = WidgetTypeRegistry.resolve(config);
        shortcutLayout = WidgetTypeRegistry.SHORTCUTS.equals(widgetType)
                ? shortcutLayout(config) : null;
        setBackgroundColor(WidgetTypeRegistry.SHORTCUTS.equals(widgetType)
                ? Color.TRANSPARENT : Color.BLACK);
        if (interactive && WidgetTypeRegistry.MUSIC.equals(widgetType)) {
            musicGestures = new GestureDetector(context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(MotionEvent event) {
                            return true;
                        }

                        @Override
                        public boolean onSingleTapConfirmed(MotionEvent event) {
                            performAction(WidgetTypeRegistry.musicGestureAction(
                                    false, event.getY(), getHeight()), "");
                            return true;
                        }

                        @Override
                        public boolean onDoubleTap(MotionEvent event) {
                            performAction(WidgetTypeRegistry.musicGestureAction(
                                    true, event.getY(), getHeight()), "");
                            return true;
                        }

                        @Override
                        public void onLongPress(MotionEvent event) {
                            startVolumeRepeat(WidgetTypeRegistry.musicLongPressAction(
                                    event.getY(), getHeight()));
                        }
                    });
            setClickable(true);
            setFocusable(true);
        } else {
            musicGestures = null;
        }
        setClickable(interactive);
        setFocusable(interactive);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
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
        if (layerBindings.isEmpty()
                && !WidgetTypeRegistry.SHORTCUTS.equals(WidgetTypeRegistry.resolve(config))) {
            showPlaceholder("MIX Flip 外屏扩展\n请在模块 App 中添加组件");
        }
    }

    private View createLayer(WidgetComponent component) {
        if (WidgetComponent.TYPE_ALBUM_ART.equals(component.type)) {
            ImageView artwork = new ImageView(getContext());
            artwork.setScaleType(imageScaleType(component.fillMode));
            artwork.setBackgroundColor(0xFF303030);
            if (WidgetTypeRegistry.MUSIC.equals(WidgetTypeRegistry.resolve(config))
                    && component.width >= WidgetConfig.CANVAS_WIDTH
                    && component.height >= WidgetConfig.CANVAS_HEIGHT) {
                artwork.setScaleX(1.14f);
                artwork.setScaleY(1.14f);
                artwork.setForeground(new ColorDrawable(0x70000000));
                albumBindings.add(new AlbumBinding(artwork, true));
            } else {
                albumBindings.add(new AlbumBinding(artwork, false));
            }
            return artwork;
        }
        if (WidgetComponent.TYPE_IMAGE.equals(component.type)) {
            ImageView image = new ImageView(getContext());
            image.setScaleType(imageScaleType(component.fillMode));
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
                || WidgetComponent.TYPE_LYRIC_PREVIOUS.equals(component.type)
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
            } else if (WidgetComponent.TYPE_LYRIC_PREVIOUS.equals(component.type)
                    || WidgetComponent.TYPE_LYRIC_CURRENT.equals(component.type)
                    || WidgetComponent.TYPE_LYRIC_NEXT.equals(component.type)) {
                text.setMaxLines(WidgetComponent.TYPE_LYRIC_CURRENT.equals(component.type) ? 3 : 2);
                text.setEllipsize(TextUtils.TruncateAt.END);
                lyricBindings.add(new PlaybackBinding(text, component));
                text.setText(component.content);
            } else {
                text.setText(component.content);
            }
            return text;
        }
        if (WidgetComponent.TYPE_BUTTON.equals(component.type)) {
            float iconSize = shortcutLayout == null ? Math.min(component.width, component.height)
                    : shortcutLayout.iconSize;
            ShortcutTileView button = new ShortcutTileView(getContext(), component, iconSize);
            button.setClickable(interactive);
            button.setFocusable(interactive);
            button.applyBackground(shortcutBackground(component.cornerRadius, 1f));
            if (interactive) {
                button.setOnClickListener(
                        view -> performAction(component.actionType, component.actionValue));
            }
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
            } else if (ActionSpec.isFlashlight(type) || ActionSpec.isMediaControl(type)
                    || ActionSpec.isDirectSystemControl(type) || ActionSpec.QS_TILE.equals(type)) {
                performProviderAction(type, value);
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

    private void performProviderAction(String type, String value) {
        Bundle extras = new Bundle();
        extras.putString("value", value);
        Bundle result = getContext().getContentResolver().call(
                Contract.PROVIDER_URI, "execute_action", type, extras);
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
            if (binding.blur && bitmap != null) bitmap = blurArtwork(bitmap);
            Bitmap loaded = bitmap;
            binding.view.post(() -> {
                if (binding.requestedRevision == revision) binding.view.setImageBitmap(loaded);
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
                            ? lyricPlaceholder(binding.component.type) : binding.component.content;
                } else {
                    String key = lyricStateKey(binding.component.type);
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

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (interactive && action == MotionEvent.ACTION_DOWN) {
            stopVolumeRepeat();
            touchDownX = event.getX();
            touchDownY = event.getY();
            yieldingToHost = false;
            disallowHostIntercept(true);
        } else if (interactive && action == MotionEvent.ACTION_MOVE && !yieldingToHost
                && shouldYieldToHost(touchDownX, touchDownY,
                event.getX(), event.getY(), touchSlop)) {
            yieldingToHost = true;
            stopVolumeRepeat();
            cancelOwnGesture(event);
            disallowHostIntercept(false);
        }
        if (yieldingToHost) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                stopVolumeRepeat();
                disallowHostIntercept(false);
            }
            return true;
        }
        if (musicGestures != null) {
            musicGestures.onTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                stopVolumeRepeat();
                disallowHostIntercept(false);
            }
            return true;
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (interactive && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            disallowHostIntercept(false);
        }
        return handled;
    }

    private void cancelOwnGesture(MotionEvent source) {
        stopVolumeRepeat();
        MotionEvent cancel = MotionEvent.obtain(source);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        if (musicGestures != null) musicGestures.onTouchEvent(cancel);
        else super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    static boolean shouldYieldToHost(float downX, float downY,
                                     float currentX, float currentY, int touchSlop) {
        float deltaX = currentX - downX;
        float deltaY = currentY - downY;
        return deltaX * deltaX + deltaY * deltaY > touchSlop * (float) touchSlop;
    }

    private void disallowHostIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }

    private void startVolumeRepeat(String action) {
        stopVolumeRepeat();
        repeatingVolumeAction = action;
        volumeRepeatActive = true;
        performAction(action, "");
        mainHandler.postDelayed(volumeRepeat, VOLUME_REPEAT_INTERVAL_MS);
    }

    private void stopVolumeRepeat() {
        volumeRepeatActive = false;
        repeatingVolumeAction = null;
        mainHandler.removeCallbacks(volumeRepeat);
    }

    private static String lyricStateKey(String type) {
        if (WidgetComponent.TYPE_LYRIC_PREVIOUS.equals(type)) return "previous";
        if (WidgetComponent.TYPE_LYRIC_NEXT.equals(type)) return "next";
        return "current";
    }

    private static boolean isLyricComponent(WidgetComponent component) {
        return WidgetComponent.TYPE_LYRIC_PREVIOUS.equals(component.type)
                || WidgetComponent.TYPE_LYRIC_CURRENT.equals(component.type)
                || WidgetComponent.TYPE_LYRIC_NEXT.equals(component.type);
    }

    private static String lyricPlaceholder(String type) {
        if (WidgetComponent.TYPE_LYRIC_PREVIOUS.equals(type)) return "上一句歌词";
        if (WidgetComponent.TYPE_LYRIC_NEXT.equals(type)) return "下一句歌词";
        return "当前歌词";
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
            int left = Math.round(component.x * scaleX);
            int top = Math.round(component.y * scaleY);
            int layerWidth = Math.max(1, Math.round(component.width * scaleX));
            int layerHeight = Math.max(1, Math.round(component.height * scaleY));
            if (WidgetComponent.TYPE_BUTTON.equals(component.type)
                    && WidgetTypeRegistry.SHORTCUTS.equals(WidgetTypeRegistry.resolve(config))) {
                int canvasLeft = Math.round(
                        (width - WidgetConfig.CANVAS_WIDTH * textScale) / 2f);
                int canvasTop = Math.round(
                        (height - WidgetConfig.CANVAS_HEIGHT * textScale) / 2f);
                left = canvasLeft + Math.round(component.x * textScale);
                top = canvasTop + Math.round(component.y * textScale);
                layerWidth = Math.max(1, Math.round(component.width * textScale));
                layerHeight = Math.max(1, Math.round(component.height * textScale));
            }
            if (params.leftMargin != left || params.topMargin != top
                    || params.width != layerWidth || params.height != layerHeight) {
                params.leftMargin = left;
                params.topMargin = top;
                params.width = layerWidth;
                params.height = layerHeight;
                binding.view.setLayoutParams(params);
            }
            binding.view.setAlpha(component.opacity);
            binding.view.invalidateOutline();

            if (binding.view instanceof TextView) {
                TextView text = (TextView) binding.view;
                float maximumSize = component.textSize * textScale;
                if (isLyricComponent(component)) {
                    float minimumCanvasSize = WidgetComponent.TYPE_LYRIC_CURRENT.equals(component.type)
                            ? 20f : 16f;
                    text.setAutoSizeTextTypeUniformWithConfiguration(
                            Math.max(1, Math.round(minimumCanvasSize * textScale)),
                            Math.max(1, Math.round(maximumSize)), 1,
                            TypedValue.COMPLEX_UNIT_PX);
                } else {
                    text.setTextSize(TypedValue.COMPLEX_UNIT_PX, maximumSize);
                }
            }
            if (WidgetComponent.TYPE_BUTTON.equals(component.type)) {
                ((ShortcutTileView) binding.view).applyBackground(
                        shortcutBackground(component.cornerRadius, textScale));
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

    private StateListDrawable shortcutBackground(float cornerRadius, float scale) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                shortcutShape(0x7AFFFFFF, cornerRadius, scale));
        states.addState(new int[0], shortcutShape(0x42FFFFFF, cornerRadius, scale));
        return states;
    }

    private GradientDrawable shortcutShape(int color, float cornerRadius, float scale) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(cornerRadius * scale);
        return shape;
    }

    static Bitmap blurArtwork(Bitmap source) {
        int width = Math.max(1, source.getWidth() / 8);
        int height = Math.max(1, source.getHeight() / 8);
        Bitmap small = Bitmap.createScaledBitmap(source, width, height, true);
        int[] pixels = new int[width * height];
        int[] scratch = new int[pixels.length];
        small.getPixels(pixels, 0, width, 0, 0, width, height);
        int radius = Math.max(2, Math.min(10, Math.min(width, height) / 14));
        for (int pass = 0; pass < 3; pass++) {
            blurHorizontal(pixels, scratch, width, height, radius);
            blurVertical(scratch, pixels, width, height, radius);
        }
        Bitmap blurred = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        blurred.setPixels(pixels, 0, width, 0, 0, width, height);
        if (small != source && small != blurred) small.recycle();
        return blurred;
    }

    private static void blurHorizontal(int[] input, int[] output,
                                       int width, int height, int radius) {
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                int start = Math.max(0, x - radius);
                int end = Math.min(width - 1, x + radius);
                for (int sample = start; sample <= end; sample++) {
                    int color = input[row + sample];
                    a += Color.alpha(color);
                    r += Color.red(color);
                    g += Color.green(color);
                    b += Color.blue(color);
                }
                int count = end - start + 1;
                output[row + x] = Color.argb((int) (a / count), (int) (r / count),
                        (int) (g / count), (int) (b / count));
            }
        }
    }

    private static void blurVertical(int[] input, int[] output,
                                     int width, int height, int radius) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                int start = Math.max(0, y - radius);
                int end = Math.min(height - 1, y + radius);
                for (int sample = start; sample <= end; sample++) {
                    int color = input[sample * width + x];
                    a += Color.alpha(color);
                    r += Color.red(color);
                    g += Color.green(color);
                    b += Color.blue(color);
                }
                int count = end - start + 1;
                output[y * width + x] = Color.argb((int) (a / count), (int) (r / count),
                        (int) (g / count), (int) (b / count));
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        updateLayerLayouts(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateLayerLayouts(right - left, bottom - top);
        for (LayerBinding binding : layerBindings) {
            LayoutParams params = (LayoutParams) binding.view.getLayoutParams();
            if (binding.view.getMeasuredWidth() != params.width
                    || binding.view.getMeasuredHeight() != params.height) {
                binding.view.measure(
                        MeasureSpec.makeMeasureSpec(params.width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(params.height, MeasureSpec.EXACTLY));
            }
            binding.view.layout(params.leftMargin, params.topMargin,
                    params.leftMargin + params.width, params.topMargin + params.height);
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
        stopVolumeRepeat();
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
        if (visibility != VISIBLE) stopVolumeRepeat();
        maybePlay();
        updatePlaybackSchedule();
        updateLyricSchedule();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) stopVolumeRepeat();
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
        final boolean blur;
        volatile long requestedRevision = -1;

        AlbumBinding(ImageView view, boolean blur) {
            this.view = view;
            this.blur = blur;
        }
    }

    private static final class ShortcutTileView extends FrameLayout {
        private final ImageView icon;
        private final View iconView;
        private final float baseWidth;
        private final float baseHeight;
        private final float baseIconSize;

        ShortcutTileView(Context context, WidgetComponent component, float iconSize) {
            super(context);
            baseWidth = component.width;
            baseHeight = component.height;
            baseIconSize = iconSize;
            setClipChildren(false);
            setElevation(0);
            setStateListAnimator(null);
            Drawable drawable = ShortcutIconRenderer.loadAppIcon(context, component);
            if (drawable != null) {
                icon = new ImageView(context);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setElevation(0);
                icon.setImageDrawable(drawable);
                iconView = icon;
            } else {
                icon = null;
                iconView = new SystemActionIconView(context, component.actionType);
            }
            addView(iconView);
            setContentDescription(component.content);
        }

        void applyBackground(Drawable systemActionBackground) {
            setBackground(null);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(width, height);
            int iconSize = iconSize(width, height);
            iconView.measure(exactly(iconSize), exactly(iconSize));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            int height = bottom - top;
            int iconSize = iconSize(width, height);
            int iconLeft = (width - iconSize) / 2;
            int iconTop = (height - iconSize) / 2;
            iconView.layout(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
        }

        private int iconSize(int width, int height) {
            float scale = Math.min(width / Math.max(1f, baseWidth),
                    height / Math.max(1f, baseHeight));
            return Math.max(1, Math.round(baseIconSize * scale));
        }

        private static int exactly(int size) {
            return MeasureSpec.makeMeasureSpec(Math.max(1, size), MeasureSpec.EXACTLY);
        }

    }

    private static ButtonLayoutEngine.Layout shortcutLayout(WidgetConfig config) {
        int count = 0;
        for (WidgetComponent component : config.components) {
            if (WidgetComponent.TYPE_BUTTON.equals(component.type) && component.visible) count++;
        }
        count = Math.min(count, ButtonLayoutEngine.MAX_BUTTONS);
        return count == 0 ? null : ButtonLayoutEngine.layout(
                count, WidgetConfig.CANVAS_WIDTH, WidgetConfig.CANVAS_HEIGHT);
    }

    private static final class SystemActionIconView extends View {
        private final String actionType;

        SystemActionIconView(Context context, String actionType) {
            super(context);
            this.actionType = actionType;
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            ShortcutIconRenderer.drawSystemIcon(
                    canvas, actionType, 0, 0, Math.min(getWidth(), getHeight()));
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

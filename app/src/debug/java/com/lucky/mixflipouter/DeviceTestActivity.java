package com.lucky.mixflipouter;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Debug-only ADB bridge for device tests that cannot rely on vendor input injection. */
public final class DeviceTestActivity extends Activity {
    private static final String DEBUG_MEDIA_ROTATION = "debug_media_rotation";
    public static final String ACTION_DUMP_DIAGNOSTICS =
            "com.lucky.mixflipouter.debug.DUMP_DIAGNOSTICS";
    public static final String ACTION_MEDIA_PLAY_PAUSE =
            "com.lucky.mixflipouter.debug.MEDIA_PLAY_PAUSE";
    public static final String ACTION_DUMP_PLAYBACK =
            "com.lucky.mixflipouter.debug.DUMP_PLAYBACK";
    public static final String ACTION_OPEN_FIRST_EDITOR =
            "com.lucky.mixflipouter.debug.OPEN_FIRST_EDITOR";
    public static final String ACTION_OPEN_TYPE_EDITOR =
            "com.lucky.mixflipouter.debug.OPEN_TYPE_EDITOR";
    public static final String ACTION_OPEN_RUNTIME_WIDGET =
            "com.lucky.mixflipouter.debug.OPEN_RUNTIME_WIDGET";
    public static final String ACTION_TEST_MUSIC_GESTURE =
            "com.lucky.mixflipouter.debug.TEST_MUSIC_GESTURE";
    public static final String ACTION_OPEN_SHORTCUT_DEMO =
            "com.lucky.mixflipouter.debug.OPEN_SHORTCUT_DEMO";
    public static final String ACTION_TEST_MEDIA_SWIPE =
            "com.lucky.mixflipouter.debug.TEST_MEDIA_SWIPE";
    public static final String ACTION_SAVE_WIDGET =
            "com.lucky.mixflipouter.debug.SAVE_WIDGET";
    public static final String ACTION_DUMP_NOTIFICATIONS =
            "com.lucky.mixflipouter.debug.DUMP_NOTIFICATIONS";
    public static final String ACTION_DISMISS_NOTIFICATION =
            "com.lucky.mixflipouter.debug.DISMISS_NOTIFICATION";
    public static final String ACTION_OPEN_NOTIFICATION =
            "com.lucky.mixflipouter.debug.OPEN_NOTIFICATION";
    public static final String ACTION_ENSURE_TYPE_WIDGET =
            "com.lucky.mixflipouter.debug.ENSURE_TYPE_WIDGET";
    public static final String ACTION_CREATE_TYPE_WIDGET =
            "com.lucky.mixflipouter.debug.CREATE_TYPE_WIDGET";
    public static final String ACTION_TEST_NOTIFICATION_SWIPE =
            "com.lucky.mixflipouter.debug.TEST_NOTIFICATION_SWIPE";
    public static final String ACTION_TEST_APPWIDGET_BIND =
            "com.lucky.mixflipouter.debug.TEST_APPWIDGET_BIND";
    public static final String ACTION_CHECK_APPWIDGET_BOUND =
            "com.lucky.mixflipouter.debug.CHECK_APPWIDGET_BOUND";
    public static final String ACTION_LIST_APPWIDGET_PROVIDERS =
            "com.lucky.mixflipouter.debug.LIST_APPWIDGET_PROVIDERS";
    public static final String ACTION_ADD_APPWIDGET_SLOT =
            "com.lucky.mixflipouter.debug.ADD_APPWIDGET_SLOT";
    public static final String ACTION_IMPORT_MAML =
            "com.lucky.mixflipouter.debug.IMPORT_MAML";
    public static final String ACTION_IMPORT_MAML_SLOT =
            "com.lucky.mixflipouter.debug.IMPORT_MAML_SLOT";
    private static final String RESULT_FILE = "device-test-result.json";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        if (ACTION_OPEN_RUNTIME_WIDGET.equals(getIntent().getAction())) {
            String widgetId = getIntent().getStringExtra(Contract.EXTRA_WIDGET_ID);
            WidgetConfig config = new WidgetRepository(this).get(
                    widgetId);
            if (config == null && "debug-video".equals(widgetId)) {
                config = new WidgetConfig();
                config.id = widgetId;
                config.typeId = WidgetTypeRegistry.MEDIA;
                config.mediaType = WidgetComponent.TYPE_VIDEO;
                config.mimeType = "video/mp4";
                WidgetTypeRegistry.buildMediaLayout(config);
            }
            if (config != null) {
                for (WidgetComponent component : config.components) {
                    if (!WidgetComponent.TYPE_IMAGE.equals(component.type)
                            && !WidgetComponent.TYPE_VIDEO.equals(component.type)) continue;
                    int rotation = getIntent().getIntExtra(DEBUG_MEDIA_ROTATION, -1);
                    if (rotation >= 0) component.mediaRotation = rotation == 90 ? 90 : 0;
                    if (getIntent().hasExtra("debug_media_scale")) {
                        component.mediaScale = getIntent().getFloatExtra("debug_media_scale", 1f);
                    }
                    if (getIntent().hasExtra("debug_media_offset_x")) {
                        component.mediaOffsetX = getIntent().getFloatExtra(
                                "debug_media_offset_x", 0f);
                    }
                    if (getIntent().hasExtra("debug_media_offset_y")) {
                        component.mediaOffsetY = getIntent().getFloatExtra(
                                "debug_media_offset_y", 0f);
                    }
                    break;
                }
                getWindow().getDecorView().setSystemUiVisibility(
                        android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                AppWidgetSlotView.debugForceHosted = true;
                MediaWidgetView widget = new MediaWidgetView(this, config, true);
                long captureDelay = getIntent().getLongExtra("capture_delay_ms", 2_500L);
                if (getIntent().getBooleanExtra("fixed_frame", false)) {
                    FrameLayout frame = new FrameLayout(this);
                    frame.addView(widget, new FrameLayout.LayoutParams(1208, 1392,
                            Gravity.TOP | Gravity.CENTER_HORIZONTAL));
                    setContentView(frame);
                } else {
                    setContentView(widget);
                }
                String captureId = config.id;
                widget.postDelayed(() -> captureRuntimeWidget(widget, captureId), captureDelay);
                return;
            }
            finish();
            return;
        }
        if (ACTION_OPEN_SHORTCUT_DEMO.equals(getIntent().getAction())) {
            int count = Math.max(1, Math.min(
                    ButtonLayoutEngine.MAX_BUTTONS, getIntent().getIntExtra("count", 1)));
            WidgetConfig config = shortcutDemo(count);
            MediaWidgetView widget = new MediaWidgetView(this, config);
            widget.setBackgroundColor(0xFF59645C);
            FrameLayout frame = new FrameLayout(this);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1208, 1392,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            frame.addView(widget, params);
            setContentView(frame);
            widget.postDelayed(() -> captureRuntimeWidget(widget, config.id), 1_500L);
            return;
        }
        if (ACTION_TEST_MEDIA_SWIPE.equals(getIntent().getAction())) {
            WidgetConfig config = new WidgetConfig();
            config.typeId = WidgetTypeRegistry.MEDIA;
            MediaWidgetView widget = new MediaWidgetView(this, config);
            RecordingFrame host = new RecordingFrame();
            host.addView(widget, new FrameLayout.LayoutParams(440, 720));
            setContentView(host);
            widget.post(() -> testMediaSwipe(widget, host));
            return;
        }
        if (ACTION_TEST_NOTIFICATION_SWIPE.equals(getIntent().getAction())) {
            WidgetConfig config = WidgetTypeRegistry.create(WidgetTypeRegistry.NOTIFICATIONS);
            MediaWidgetView widget = new MediaWidgetView(this, config);
            RecordingFrame host = new RecordingFrame();
            host.addView(widget, new FrameLayout.LayoutParams(440, 720));
            setContentView(host);
            widget.postDelayed(() -> testNotificationSwipe(widget, host), 2_500L);
            return;
        }
        if (ACTION_TEST_MUSIC_GESTURE.equals(getIntent().getAction())) {
            WidgetConfig music = null;
            for (WidgetConfig candidate : new WidgetRepository(this).list()) {
                if (WidgetTypeRegistry.MUSIC.equals(WidgetTypeRegistry.resolve(candidate))) {
                    music = candidate;
                    break;
                }
            }
            if (music == null) {
                finish();
                return;
            }
            MediaWidgetView widget = new MediaWidgetView(this, music);
            FrameLayout host = new FrameLayout(this);
            host.addView(widget, new FrameLayout.LayoutParams(440, 720));
            setContentView(host);
            String gesture = getIntent().getStringExtra("gesture");
            widget.post(() -> testMusicGesture(widget, gesture));
            return;
        }
        if (ACTION_OPEN_FIRST_EDITOR.equals(getIntent().getAction())) {
            WidgetRepository repository = new WidgetRepository(this);
            if (!repository.list().isEmpty()) {
                startActivity(new Intent(this, WidgetEditorActivity.class)
                        .putExtra(Contract.EXTRA_WIDGET_ID, repository.list().get(0).id)
                        .putExtra(WidgetEditorActivity.EXTRA_DEBUG_SCROLL_Y,
                                getIntent().getIntExtra(
                                        WidgetEditorActivity.EXTRA_DEBUG_SCROLL_Y, 0)));
            }
            finish();
            return;
        }
        if (ACTION_OPEN_TYPE_EDITOR.equals(getIntent().getAction())) {
            startActivity(new Intent(this, WidgetEditorActivity.class)
                    .putExtra(WidgetEditorActivity.EXTRA_DEBUG_TYPE_ID,
                            getIntent().getStringExtra(WidgetEditorActivity.EXTRA_DEBUG_TYPE_ID))
                    .putExtra(WidgetEditorActivity.EXTRA_DEBUG_SCROLL_Y,
                            getIntent().getIntExtra(
                                    WidgetEditorActivity.EXTRA_DEBUG_SCROLL_Y, 0)));
            finish();
            return;
        }
        new Thread(this::runAction, "device-test").start();
    }

    private void testMusicGesture(MediaWidgetView widget, String gesture) {
        Bundle before = PlaybackStateStore.provider().snapshot();
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        int beforeVolume = audio == null ? -1
                : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean longPress = "volume_up".equals(gesture) || "volume_down".equals(gesture);
        boolean doubleTap = "previous".equals(gesture) || "next".equals(gesture);
        float y = "next".equals(gesture) || "volume_down".equals(gesture)
                ? widget.getHeight() * 0.75f : widget.getHeight() * 0.25f;
        long now = SystemClock.uptimeMillis();
        if (longPress) {
            MotionEvent down = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_DOWN, 220, y, 0);
            widget.dispatchTouchEvent(down);
            down.recycle();
            widget.postDelayed(() -> {
                long upTime = SystemClock.uptimeMillis();
                MotionEvent up = MotionEvent.obtain(
                        now, upTime, MotionEvent.ACTION_UP, 220, y, 0);
                widget.dispatchTouchEvent(up);
                up.recycle();
                int releasedVolume = audio == null ? -1
                        : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                widget.postDelayed(() -> finishMusicGestureTest(
                        before, beforeVolume, audio, gesture, releasedVolume), 300L);
            }, ViewConfiguration.getLongPressTimeout() + 900L);
            return;
        }
        dispatchTap(widget, now, y);
        if (doubleTap) dispatchTap(widget, now + 120, y);
        widget.postDelayed(() -> finishMusicGestureTest(
                before, beforeVolume, audio, gesture, -1), doubleTap ? 1_500L : 600L);
    }

    private void finishMusicGestureTest(Bundle before, int beforeVolume,
                                        AudioManager audio, String gesture, int releasedVolume) {
        Bundle after = PlaybackStateStore.provider().snapshot();
        int afterVolume = audio == null ? -1
                : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean volumeGesture = "volume_up".equals(gesture) || "volume_down".equals(gesture);
        boolean expectedVolumeChange = "volume_up".equals(gesture)
                ? afterVolume > beforeVolume : afterVolume < beforeVolume;
        int volumeSteps = Math.abs(afterVolume - beforeVolume);
        boolean stoppedAfterRelease = !volumeGesture || releasedVolume == afterVolume;
        JSONObject result = new JSONObject();
        try {
            result.put("ok", !volumeGesture
                    || expectedVolumeChange && volumeSteps >= 2 && stoppedAfterRelease);
            result.put("gesture", gesture);
            result.put("before_playing", before.getBoolean("playing"));
            result.put("after_playing", after.getBoolean("playing"));
            result.put("before_title", before.getString("title", ""));
            result.put("after_title", after.getString("title", ""));
            result.put("before_volume", beforeVolume);
            result.put("released_volume", releasedVolume);
            result.put("after_volume", afterVolume);
            result.put("volume_steps", volumeSteps);
            result.put("stopped_after_release", stoppedAfterRelease);
        } catch (Throwable ignored) {
        }
        writeResult(result);
        finish();
    }

    private void testNotificationSwipe(MediaWidgetView widget, RecordingFrame host) {
        Bundle before = NotificationStateStore.snapshot();
        String targetKey = before.getInt("count", 0) > 0 ? before.getString("key_0", "") : "";
        boolean clearable = before.getBoolean("clearable_0", false);
        long now = SystemClock.uptimeMillis();
        float rowY = 720f / 6f;
        NotificationListView list = findNotificationList(widget);
        int boundRows = list == null ? -1 : list.debugBoundRowCount();
        MotionEvent down = MotionEvent.obtain(now, now,
                MotionEvent.ACTION_DOWN, 400, rowY, 0);
        widget.dispatchTouchEvent(down);
        down.recycle();
        for (int step = 1; step <= 6; step++) {
            MotionEvent move = MotionEvent.obtain(now, now + step * 30,
                    MotionEvent.ACTION_MOVE, 400 - step * 60, rowY, 0);
            widget.dispatchTouchEvent(move);
            move.recycle();
        }
        boolean swipingSeen = list != null && list.debugSwiping();
        float offsetSeen = list == null ? Float.NaN : list.debugActiveOffset();
        MotionEvent up = MotionEvent.obtain(now, now + 240,
                MotionEvent.ACTION_UP, 40, rowY, 0);
        widget.dispatchTouchEvent(up);
        up.recycle();
        pollSwipeResult(widget, host, before, targetKey, clearable,
                swipingSeen, offsetSeen, boundRows, 0);
    }

    private void pollSwipeResult(MediaWidgetView widget, RecordingFrame host, Bundle before,
                                 String targetKey, boolean clearable, boolean swipingSeen,
                                 float offsetSeen, int boundRows, int attempt) {
        Bundle after = NotificationStateStore.snapshot();
        boolean removed = !targetKey.isEmpty();
        for (int i = 0; i < after.getInt("count", 0); i++) {
            if (targetKey.equals(after.getString("key_" + i, ""))) removed = false;
        }
        if (!removed && attempt < 20) {
            widget.postDelayed(() -> pollSwipeResult(widget, host, before, targetKey,
                    clearable, swipingSeen, offsetSeen, boundRows, attempt + 1), 200L);
            return;
        }
        JSONObject result = new JSONObject();
        try {
            result.put("ok", !targetKey.isEmpty() && clearable && removed);
            result.put("target_key", targetKey);
            result.put("clearable", clearable);
            result.put("before_count", before.getInt("count", 0));
            result.put("after_count", after.getInt("count", 0));
            result.put("removed", removed);
            result.put("attempts", attempt);
            result.put("disallow_seen", host.disallowSeen);
            result.put("release_seen", host.releaseSeen);
            result.put("bound_rows", boundRows);
            result.put("swiping_seen", swipingSeen);
            result.put("offset_seen", Float.isNaN(offsetSeen) ? "none" : offsetSeen);
        } catch (Throwable ignored) {
        }
        writeResult(result);
        finish();
    }

    private NotificationListView findNotificationList(MediaWidgetView widget) {
        for (int i = 0; i < widget.getChildCount(); i++) {
            if (widget.getChildAt(i) instanceof NotificationListView) {
                return (NotificationListView) widget.getChildAt(i);
            }
        }
        return null;
    }

    private void dispatchTap(MediaWidgetView widget, long downTime, float y) {
        MotionEvent down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, 220, y, 0);
        MotionEvent up = MotionEvent.obtain(
                downTime, downTime + 40, MotionEvent.ACTION_UP, 220, y, 0);
        widget.dispatchTouchEvent(down);
        widget.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private WidgetConfig shortcutDemo(int count) {
        String[] labels = {"哔哩哔哩", "网易云音乐", "相册", "笔记",
                "百度地图", "酷安", "YouTube", "天气"};
        String[] packages = {"tv.danmaku.bili", "com.netease.cloudmusic", "com.miui.gallery",
                "com.miui.notes", "com.baidu.BaiduMap", "com.coolapk.market",
                "com.google.android.youtube", "com.miui.weather2"};
        WidgetConfig config = new WidgetConfig();
        config.id = "shortcut-demo-" + count;
        config.typeId = WidgetTypeRegistry.SHORTCUTS;
        for (int index = 0; index < count; index++) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(packages[index]);
            ComponentName component = launch == null ? null : launch.getComponent();
            String value = component == null ? packages[index] : component.flattenToString();
            config.components.add(WidgetComponent.button(labels[index], ActionSpec.LAUNCH_APP,
                    value, 0, 0, 1, 1, index));
        }
        WidgetTypeRegistry.buildShortcutLayout(config);
        return config;
    }

    private void testMediaSwipe(MediaWidgetView widget, RecordingFrame host) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now,
                MotionEvent.ACTION_DOWN, 220, 360, 0);
        MotionEvent move = MotionEvent.obtain(now, now + 60,
                MotionEvent.ACTION_MOVE, 220, 430, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 100,
                MotionEvent.ACTION_UP, 220, 430, 0);
        boolean downHandled = widget.dispatchTouchEvent(down);
        boolean moveHandled = widget.dispatchTouchEvent(move);
        widget.dispatchTouchEvent(up);
        down.recycle();
        move.recycle();
        up.recycle();
        JSONObject result = new JSONObject();
        try {
            result.put("ok", downHandled && moveHandled
                    && host.disallowSeen && host.releaseSeen);
            result.put("down_handled", downHandled);
            result.put("move_handled", moveHandled);
            result.put("disallow_seen", host.disallowSeen);
            result.put("release_seen", host.releaseSeen);
        } catch (Throwable ignored) {
        }
        writeResult(result);
        finish();
    }

    private final class RecordingFrame extends FrameLayout {
        boolean disallowSeen;
        boolean releaseSeen;

        RecordingFrame() {
            super(DeviceTestActivity.this);
        }

        @Override
        public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            if (disallowIntercept) disallowSeen = true;
            else if (disallowSeen) releaseSeen = true;
            super.requestDisallowInterceptTouchEvent(disallowIntercept);
        }
    }

    private void captureRuntimeWidget(MediaWidgetView widget, String widgetId) {
        try {
            File outputDirectory = getExternalFilesDir(null);
            if (outputDirectory == null) outputDirectory = getFilesDir();
            int width = Math.max(1, widget.getWidth());
            int height = Math.max(1, widget.getHeight());
            widget.forceLayout();
            widget.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(
                            width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(
                            height, android.view.View.MeasureSpec.EXACTLY));
            widget.layout(0, 0, width, height);
            StringBuilder layout = new StringBuilder()
                    .append("root=").append(width).append('x').append(height)
                    .append(" children=").append(widget.getChildCount()).append('\n');
            dumpViewTree(widget, layout, 0, 6);
            try (FileOutputStream stream = new FileOutputStream(
                    new File(outputDirectory, "runtime-" + widgetId + ".txt"), false)) {
                stream.write(layout.toString().getBytes(StandardCharsets.UTF_8));
            }
            Bitmap bitmap = Bitmap.createBitmap(
                    width, height,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            widget.draw(canvas);
            for (int index = 0; index < widget.getChildCount(); index++) {
                android.view.View child = widget.getChildAt(index);
                if (!(child instanceof android.view.TextureView)) continue;
                Bitmap texture = ((android.view.TextureView) child).getBitmap(
                        Math.max(1, child.getWidth()), Math.max(1, child.getHeight()));
                if (texture != null) {
                    int save = canvas.save();
                    canvas.translate(child.getLeft(), child.getTop());
                    canvas.concat(((android.view.TextureView) child).getTransform(null));
                    canvas.drawBitmap(texture, 0, 0, null);
                    canvas.restoreToCount(save);
                    texture.recycle();
                }
            }
            File output = new File(outputDirectory, "runtime-" + widgetId + ".png");
            try (FileOutputStream stream = new FileOutputStream(output, false)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            bitmap.recycle();
        } catch (Throwable ignored) {
        }
        finish();
    }

    private void dumpViewTree(android.view.View view, StringBuilder out, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        for (int index = 0; index < (view instanceof android.view.ViewGroup
                ? ((android.view.ViewGroup) view).getChildCount() : 0); index++) {
            android.view.View child = ((android.view.ViewGroup) view).getChildAt(index);
            for (int pad = 0; pad < depth; pad++) out.append("  ");
            out.append(index).append(' ').append(child.getClass().getSimpleName())
                    .append(" frame=").append(child.getLeft()).append(',')
                    .append(child.getTop()).append('-').append(child.getRight()).append(',')
                    .append(child.getBottom()).append(" alpha=").append(child.getAlpha())
                    .append(" visibility=").append(child.getVisibility());
            if (child instanceof android.widget.TextView) {
                android.widget.TextView text = (android.widget.TextView) child;
                out.append(" text=").append(text.getText())
                        .append(" textSizePx=").append(text.getTextSize());
            }
            out.append('\n');
            dumpViewTree(child, out, depth + 1, maxDepth);
        }
    }

    private void runAction() {
        JSONObject result = new JSONObject();
        try {
            String action = getIntent().getAction();
            if (ACTION_DUMP_DIAGNOSTICS.equals(action)) {
                result = DiagnosticReport.collect(this, new WidgetRepository(this));
            } else if (ACTION_DUMP_PLAYBACK.equals(action)) {
                Bundle value = PlaybackStateStore.provider().snapshot();
                result.put("available", value.getBoolean("available"));
                result.put("package", value.getString("package", ""));
                result.put("title", value.getString("title", ""));
                result.put("media_id", value.getString("media_id", ""));
                result.put("playing", value.getBoolean("playing"));
                result.put("refresh_count", value.getLong("refresh_count"));
                result.put("last_refresh_elapsed", value.getLong("last_refresh_elapsed"));
                result.put("watchdog_refresh_count",
                        PlaybackNotificationListener.watchdogRefreshCount());
                result.put("watchdog_last_refresh_elapsed",
                        PlaybackNotificationListener.watchdogLastRefreshElapsed());
            } else if (ACTION_MEDIA_PLAY_PAUSE.equals(action)) {
                Bundle value = PlaybackStateStore.provider().execute(ActionSpec.MEDIA_PLAY_PAUSE);
                result.put("ok", value.getBoolean("ok"));
                result.put("message", value.getString("message", ""));
            } else if (ACTION_DUMP_NOTIFICATIONS.equals(action)) {
                Bundle value = NotificationStateStore.snapshot();
                result.put("ok", true);
                result.put("count", value.getInt("count", 0));
                result.put("revision", value.getLong("revision", 0));
                result.put("listener_connected", PlaybackNotificationListener.isConnected());
                for (int i = 0; i < value.getInt("count", 0); i++) {
                    JSONObject entry = new JSONObject();
                    entry.put("key", value.getString("key_" + i, ""));
                    entry.put("pkg", value.getString("pkg_" + i, ""));
                    entry.put("title", value.getString("title_" + i, ""));
                    entry.put("text", value.getString("text_" + i, ""));
                    entry.put("clearable", value.getBoolean("clearable_" + i, false));
                    try {
                        android.graphics.drawable.Drawable icon = getPackageManager()
                                .getApplicationIcon(value.getString("pkg_" + i, ""));
                        entry.put("icon", icon == null ? "null"
                                : icon.getIntrinsicWidth() + "x" + icon.getIntrinsicHeight());
                    } catch (Throwable iconError) {
                        entry.put("icon", "error:" + iconError.getClass().getSimpleName());
                    }
                    result.put("entry_" + i, entry);
                }
            } else if (ACTION_DISMISS_NOTIFICATION.equals(action)) {
                Bundle value = NotificationStateStore.dismiss(
                        getIntent().getStringExtra("key"));
                result.put("ok", value.getBoolean("ok"));
                result.put("message", value.getString("message", ""));
            } else if (ACTION_OPEN_NOTIFICATION.equals(action)) {
                Bundle value = NotificationStateStore.open(
                        this, getIntent().getStringExtra("key"));
                result.put("ok", value.getBoolean("ok"));
                result.put("message", value.getString("message", ""));
            } else if (ACTION_CREATE_TYPE_WIDGET.equals(action)) {
                String typeId = getIntent().getStringExtra(
                        WidgetEditorActivity.EXTRA_DEBUG_TYPE_ID);
                WidgetRepository repository = new WidgetRepository(this);
                WidgetConfig created = repository.createFromTemplate(
                        WidgetTypeRegistry.create(typeId));
                result.put("ok", true);
                result.put("widget_id", created.id);
                result.put("type_id", typeId == null ? "" : typeId);
            } else if (ACTION_ENSURE_TYPE_WIDGET.equals(action)) {
                String typeId = getIntent().getStringExtra(
                        WidgetEditorActivity.EXTRA_DEBUG_TYPE_ID);
                WidgetRepository repository = new WidgetRepository(this);
                WidgetConfig existing = null;
                for (WidgetConfig candidate : repository.list()) {
                    if (typeId != null && typeId.equals(
                            WidgetTypeRegistry.resolve(candidate))) {
                        existing = candidate;
                        break;
                    }
                }
                if (existing == null) {
                    existing = repository.createFromTemplate(
                            WidgetTypeRegistry.create(typeId));
                }
                result.put("ok", existing != null);
                result.put("widget_id", existing == null ? "" : existing.id);
                result.put("type_id", typeId == null ? "" : typeId);
            } else if (ACTION_TEST_APPWIDGET_BIND.equals(action)) {
                android.appwidget.AppWidgetManager manager =
                        android.appwidget.AppWidgetManager.getInstance(this);
                ComponentName provider = null;
                String requested = getIntent().getStringExtra("provider");
                if (requested != null) provider = ComponentName.unflattenFromString(requested);
                if (provider == null) {
                    java.util.List<android.appwidget.AppWidgetProviderInfo> installed =
                            manager.getInstalledProviders();
                    if (!installed.isEmpty()) provider = installed.get(0).provider;
                }
                if (provider == null) {
                    result.put("ok", false);
                    result.put("message", "no installed providers");
                } else {
                    android.appwidget.AppWidgetHost host =
                            new android.appwidget.AppWidgetHost(this, 0x4d495443);
                    host.startListening();
                    int id = host.allocateAppWidgetId();
                    boolean allowed = manager.bindAppWidgetIdIfAllowed(id, provider);
                    result.put("ok", true);
                    result.put("provider", provider.flattenToString());
                    result.put("app_widget_id", id);
                    result.put("bind_allowed", allowed);
                    if (!allowed) {
                        ComponentName consentProvider = provider;
                        int consentId = id;
                        new Handler(getMainLooper()).post(() -> {
                            Intent consent = new Intent(
                                    android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND);
                            consent.putExtra(android.appwidget.AppWidgetManager
                                    .EXTRA_APPWIDGET_ID, consentId);
                            consent.putExtra(android.appwidget.AppWidgetManager
                                    .EXTRA_APPWIDGET_PROVIDER, consentProvider);
                            startActivityForResult(consent, 9901);
                        });
                        result.put("consent_launched", true);
                        writeResult(result);
                        return;
                    }
                }
            } else if (ACTION_IMPORT_MAML_SLOT.equals(action)) {
                String path = getIntent().getStringExtra("path");
                String widgetId = getIntent().getStringExtra(Contract.EXTRA_WIDGET_ID);
                WidgetRepository repository = new WidgetRepository(this);
                WidgetConfig target = repository.get(widgetId);
                if (path == null || target == null) {
                    result.put("ok", false);
                    result.put("message", "missing path or widget");
                } else {
                    File source = new File(path);
                    WidgetComponent slot = WidgetComponent.mamlSlot(
                            source.getName().replaceAll("\\.(zip|mtz)$", ""), 2, 2);
                    target.components.add(slot);
                    MamlImporter.importSlot(this, repository, widgetId, slot.id,
                            android.net.Uri.fromFile(source));
                    WidgetTypeRegistry.normalize(target);
                    repository.save(target);
                    result.put("ok", true);
                    result.put("component_id", slot.id);
                    result.put("slots", target.components.size());
                }
            } else if (ACTION_IMPORT_MAML.equals(action)) {
                String path = getIntent().getStringExtra("path");
                if (path == null) {
                    result.put("ok", false);
                    result.put("message", "missing path");
                } else {
                    File source = new File(path);
                    WidgetConfig imported = MamlImporter.importZip(this,
                            new WidgetRepository(this),
                            android.net.Uri.fromFile(source), source.getName());
                    result.put("ok", true);
                    result.put("widget_id", imported.id);
                    result.put("name", imported.name);
                    result.put("type_id", imported.typeId);
                }
            } else if (ACTION_LIST_APPWIDGET_PROVIDERS.equals(action)) {
                android.appwidget.AppWidgetManager manager =
                        android.appwidget.AppWidgetManager.getInstance(this);
                java.util.List<android.appwidget.AppWidgetProviderInfo> installed =
                        manager.getInstalledProviders();
                result.put("ok", true);
                result.put("count", installed.size());
                for (int i = 0; i < installed.size(); i++) {
                    android.appwidget.AppWidgetProviderInfo info = installed.get(i);
                    String cells = android.os.Build.VERSION.SDK_INT >= 31
                            ? info.targetCellWidth + "x" + info.targetCellHeight : "?";
                    result.put("provider_" + i, info.provider.flattenToString()
                            + " | " + info.loadLabel(getPackageManager())
                            + " | min=" + info.minWidth + "x" + info.minHeight
                            + " | cells=" + cells);
                }
            } else if (ACTION_ADD_APPWIDGET_SLOT.equals(action)) {
                WidgetRepository repository = new WidgetRepository(this);
                WidgetConfig target = repository.get(
                        getIntent().getStringExtra(Contract.EXTRA_WIDGET_ID));
                String slotProvider = getIntent().getStringExtra("provider");
                if (target == null || slotProvider == null) {
                    result.put("ok", false);
                    result.put("message", "widget not found or provider missing");
                } else {
                    target.components.add(WidgetComponent.appWidget(slotProvider,
                            getIntent().getIntExtra("cols", 2),
                            getIntent().getIntExtra("rows", 2)));
                    WidgetTypeRegistry.normalize(target);
                    repository.save(target);
                    result.put("ok", true);
                    result.put("widget_id", target.id);
                    result.put("slots", target.components.size());
                }
            } else if (ACTION_CHECK_APPWIDGET_BOUND.equals(action)) {
                android.appwidget.AppWidgetManager manager =
                        android.appwidget.AppWidgetManager.getInstance(this);
                int id = getIntent().getIntExtra("app_widget_id", -1);
                android.appwidget.AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
                result.put("ok", info != null);
                result.put("app_widget_id", id);
                if (info != null) {
                    result.put("provider", info.provider.flattenToString());
                    android.appwidget.AppWidgetHost host =
                            new android.appwidget.AppWidgetHost(this, 0x4d495443);
                    android.appwidget.AppWidgetHostView view = host.createView(this, id, info);
                    result.put("view_created", view != null);
                    host.deleteAppWidgetId(id);
                    result.put("cleaned_up", true);
                }
            } else if (ACTION_SAVE_WIDGET.equals(action)) {
                WidgetRepository repository = new WidgetRepository(this);
                WidgetConfig config = repository.get(
                        getIntent().getStringExtra(Contract.EXTRA_WIDGET_ID));
                if (config == null) {
                    result.put("ok", false);
                    result.put("message", "widget not found");
                } else {
                    repository.save(config);
                    result.put("ok", true);
                    result.put("revision", repository.revision());
                }
            } else {
                result.put("ok", false);
                result.put("message", "Unknown debug action");
            }
        } catch (Throwable error) {
            try {
                result.put("ok", false);
                result.put("message", error.getClass().getSimpleName());
            } catch (Throwable ignored) {
            }
        }
        writeResult(result);
        finish();
    }

    private void writeResult(JSONObject result) {
        try {
            File output = new File(getFilesDir(), RESULT_FILE);
            try (FileOutputStream stream = new FileOutputStream(output, false)) {
                stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }
}

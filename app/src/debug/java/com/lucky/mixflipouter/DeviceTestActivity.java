package com.lucky.mixflipouter;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.os.Bundle;
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
    public static final String ACTION_DUMP_DIAGNOSTICS =
            "com.lucky.mixflipouter.debug.DUMP_DIAGNOSTICS";
    public static final String ACTION_MEDIA_PLAY_PAUSE =
            "com.lucky.mixflipouter.debug.MEDIA_PLAY_PAUSE";
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
    private static final String RESULT_FILE = "device-test-result.json";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        if (ACTION_OPEN_RUNTIME_WIDGET.equals(getIntent().getAction())) {
            WidgetConfig config = new WidgetRepository(this).get(
                    getIntent().getStringExtra(Contract.EXTRA_WIDGET_ID));
            if (config != null) {
                getWindow().getDecorView().setSystemUiVisibility(
                        android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                MediaWidgetView widget = new MediaWidgetView(this, config);
                setContentView(widget);
                widget.postDelayed(() -> captureRuntimeWidget(widget, config.id), 2_500L);
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
            for (int index = 0; index < widget.getChildCount(); index++) {
                android.view.View child = widget.getChildAt(index);
                layout.append(index).append(' ').append(child.getClass().getSimpleName())
                        .append(" frame=").append(child.getLeft()).append(',')
                        .append(child.getTop()).append('-').append(child.getRight()).append(',')
                        .append(child.getBottom()).append(" alpha=").append(child.getAlpha())
                        .append(" visibility=").append(child.getVisibility());
                if (child instanceof android.widget.TextView) {
                    android.widget.TextView text = (android.widget.TextView) child;
                    layout.append(" text=").append(text.getText())
                            .append(" textSizePx=").append(text.getTextSize());
                }
                layout.append('\n');
            }
            try (FileOutputStream stream = new FileOutputStream(
                    new File(outputDirectory, "runtime-" + widgetId + ".txt"), false)) {
                stream.write(layout.toString().getBytes(StandardCharsets.UTF_8));
            }
            Bitmap bitmap = Bitmap.createBitmap(
                    width, height,
                    Bitmap.Config.ARGB_8888);
            widget.draw(new Canvas(bitmap));
            File output = new File(outputDirectory, "runtime-" + widgetId + ".png");
            try (FileOutputStream stream = new FileOutputStream(output, false)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            bitmap.recycle();
        } catch (Throwable ignored) {
        }
        finish();
    }

    private void runAction() {
        JSONObject result = new JSONObject();
        try {
            String action = getIntent().getAction();
            if (ACTION_DUMP_DIAGNOSTICS.equals(action)) {
                result = DiagnosticReport.collect(this, new WidgetRepository(this));
            } else if (ACTION_MEDIA_PLAY_PAUSE.equals(action)) {
                Bundle value = PlaybackStateStore.provider().execute(ActionSpec.MEDIA_PLAY_PAUSE);
                result.put("ok", value.getBoolean("ok"));
                result.put("message", value.getString("message", ""));
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

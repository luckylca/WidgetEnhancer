package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class NotificationListViewThemeTest {
    @Test
    public void existingRowsAndNewRowsFollowConfigurationWithoutClearingData() {
        NotificationListView view = createView();
        Bundle first = notification("first", 1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            view.setData(first);
            view.dispatchConfigurationChanged(configuration(Configuration.UI_MODE_NIGHT_NO));
        });
        assertEquals(1, view.debugBoundRowCount());
        assertEquals(0xFFFFFFFF, view.debugCardColor(0));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                view.dispatchConfigurationChanged(configuration(Configuration.UI_MODE_NIGHT_YES)));
        assertEquals(1, view.debugBoundRowCount());
        assertEquals(Collections.singletonList("first"), view.debugBoundKeys());
        assertEquals(0xFF292A2F, view.debugCardColor(0));
        assertEquals(0xFFF1F1F1, view.debugTitleColor(0));
        assertEquals(0xFFB9BAC0, view.debugContentColor(0));
        assertEquals(0xFF999BA3, view.debugEmptyTextColor());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                view.setData(notification("second", 2)));
        assertEquals(1, view.debugBoundRowCount());
        assertEquals(Collections.singletonList("second"), view.debugBoundKeys());
        assertEquals(0xFF292A2F, view.debugCardColor(0));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                view.dispatchConfigurationChanged(configuration(Configuration.UI_MODE_NIGHT_NO)));
        assertEquals(0xFFFFFFFF, view.debugCardColor(0));
        assertEquals(0x991A1A1A, view.debugContentColor(0));
    }

    @Test
    public void themeChangePreservesClickAndInProgressSwipeAndIgnoresWallpaperPalette()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<NotificationListView> ref = new AtomicReference<>();
        AtomicReference<String> opened = new AtomicReference<>();
        AtomicReference<String> dismissed = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NotificationListView view = new NotificationListView(context, true, 3,
                    WidgetConfig.NOTIFICATION_FILTER_OFF, Collections.emptyList());
            view.setCallback(new NotificationListView.Callback() {
                @Override public void onOpen(String key) { opened.set(key); }
                @Override public void onDismiss(String key) { dismissed.set(key); }
            });
            view.setData(notification("tap", 1));
            view.measure(exact(500), exact(300));
            view.layout(0, 0, 500, 300);
            view.dispatchConfigurationChanged(configuration(Configuration.UI_MODE_NIGHT_NO));
            ref.set(view);
        });
        NotificationListView view = ref.get();
        long now = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, 250, 100, now);
        send(view, MotionEvent.ACTION_UP, 250, 100, now + 10);
        assertEquals("tap", opened.get());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            view.setData(notification("swipe", 2));
            view.updateSystemTheme(configuration(Configuration.UI_MODE_NIGHT_NO));
        });
        now = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, 400, 100, now);
        send(view, MotionEvent.ACTION_MOVE, 100, 100, now + 10);
        assertTrue(view.debugSwiping());
        assertEquals(-300f, view.debugActiveOffset(), 0.01f);

        boolean originalWallpaper = WallpaperColorState.isDarkWallpaper();
        try {
            WallpaperColorState.setDarkWallpaper(!originalWallpaper);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    view.dispatchConfigurationChanged(
                            configuration(Configuration.UI_MODE_NIGHT_YES)));
            assertTrue(view.debugSwiping());
            assertEquals(-300f, view.debugActiveOffset(), 0.01f);
            assertEquals(0xFF292A2F, view.debugCardColor(0));
            assertEquals(Collections.singletonList("swipe"), view.debugBoundKeys());

            send(view, MotionEvent.ACTION_UP, 100, 100, now + 20);
            Thread.sleep(250);
            assertEquals("swipe", dismissed.get());
        } finally {
            WallpaperColorState.setDarkWallpaper(originalWallpaper);
        }
    }

    private static NotificationListView createView() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<NotificationListView> ref = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ref.set(
                new NotificationListView(context, true, 3,
                        WidgetConfig.NOTIFICATION_FILTER_OFF, Collections.emptyList())));
        return ref.get();
    }

    private static Bundle notification(String key, long revision) {
        Bundle data = new Bundle();
        data.putLong("revision", revision);
        data.putInt("count", 1);
        data.putString("pkg_0", "com.lucky.mixflipouter");
        data.putString("key_0", key);
        data.putString("title_0", "Title " + key);
        data.putString("text_0", "Body " + key);
        data.putBoolean("clearable_0", true);
        return data;
    }

    private static Configuration configuration(int nightMode) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | nightMode;
        return configuration;
    }

    private static int exact(int size) {
        return android.view.View.MeasureSpec.makeMeasureSpec(size,
                android.view.View.MeasureSpec.EXACTLY);
    }

    private static void send(NotificationListView view, int action, float x, float y, long at) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            MotionEvent event = MotionEvent.obtain(at, at, action, x, y, 0);
            view.dispatchTouchEvent(event);
            event.recycle();
        });
    }
}

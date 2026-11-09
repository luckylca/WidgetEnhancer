package com.lucky.mixflipouter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.content.res.Configuration;

import org.junit.Test;

public final class NotificationThemeTest {
    @Test
    public void lightAndDarkSystemModesSelectNotificationPalettes() {
        NotificationTheme.Palette light = NotificationTheme.fromUiMode(
                Configuration.UI_MODE_NIGHT_NO);
        NotificationTheme.Palette dark = NotificationTheme.fromUiMode(
                Configuration.UI_MODE_NIGHT_YES);

        assertSame(NotificationTheme.LIGHT, light);
        assertEquals(0xFFFFFFFF, light.cardBackground);
        assertEquals(0xFF1A1A1A, light.title);
        assertEquals(0x991A1A1A, light.content);
        assertSame(NotificationTheme.DARK, dark);
        assertEquals(0xFF292A2F, dark.cardBackground);
        assertEquals(0xFFF1F1F1, dark.title);
        assertEquals(0xFFB9BAC0, dark.content);
        assertEquals(0xFF999BA3, dark.auxiliary);
    }

    @Test
    public void undefinedNightModeFallsBackToLightPalette() {
        assertSame(NotificationTheme.LIGHT, NotificationTheme.fromUiMode(0));
    }
}

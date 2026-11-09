package com.lucky.mixflipouter;

import android.content.res.Configuration;

/** System-configuration palette used only by notification cards. */
final class NotificationTheme {
    static final Palette LIGHT = new Palette(0xFFFFFFFF, 0xFF1A1A1A,
            0x991A1A1A, 0x991A1A1A);
    static final Palette DARK = new Palette(0xFF292A2F, 0xFFF1F1F1,
            0xFFB9BAC0, 0xFF999BA3);

    static Palette fromConfiguration(Configuration configuration) {
        return fromUiMode(configuration == null ? Configuration.UI_MODE_NIGHT_NO
                : configuration.uiMode);
    }

    static Palette fromUiMode(int uiMode) {
        int night = uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES ? DARK : LIGHT;
    }

    static final class Palette {
        final int cardBackground;
        final int title;
        final int content;
        final int auxiliary;

        Palette(int cardBackground, int title, int content, int auxiliary) {
            this.cardBackground = cardBackground;
            this.title = title;
            this.content = content;
            this.auxiliary = auxiliary;
        }
    }

    private NotificationTheme() { }
}

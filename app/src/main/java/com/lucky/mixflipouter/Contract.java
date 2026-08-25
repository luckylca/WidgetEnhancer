package com.lucky.mixflipouter;

import android.net.Uri;

final class Contract {
    static final String MODULE_PACKAGE = "com.lucky.mixflipouter";
    static final String TARGET_PACKAGE = "com.miui.fliphome";
    static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    static final String NETEASE_PACKAGE = "com.netease.cloudmusic";
    static final String GALLERY_PACKAGE = "com.miui.gallery";
    static final String AUTHORITY = "com.lucky.mixflipouter.provider";
    static final Uri PROVIDER_URI = Uri.parse("content://" + AUTHORITY);
    static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");
    static final Uri LYRICS_URI = Uri.parse("content://" + AUTHORITY + "/lyrics");
    static final Uri QS_URI = Uri.parse("content://" + AUTHORITY + "/qs");
    static final Uri PLAYBACK_ARTWORK_URI = Uri.parse("content://" + AUTHORITY + "/playback/artwork");
    static final String CUSTOM_TYPE = "mixflip_custom";
    static final String WIDGET_FILE_PREFIX = "mixflip_custom_widget_";
    static final String DEFAULT_WIDGET_ID = "default";
    static final String RUNTIME_VIEW_TAG = "mixflip_custom_runtime_overlay";
    static final String PREFS = "outer_widget";
    static final String EXTRA_WIDGET_ID = "widget_id";
    static final int BUTTON_COUNT = 4;

    static Uri mediaUri(String widgetId) {
        return Uri.parse("content://" + AUTHORITY + "/widgets/" + Uri.encode(widgetId) + "/media");
    }

    static Uri previewUri(String widgetId) {
        return Uri.parse("content://" + AUTHORITY + "/widgets/" + Uri.encode(widgetId) + "/preview");
    }

    static Uri previewUri(String widgetId, long revision) {
        return previewUri(widgetId).buildUpon()
                .appendQueryParameter("revision", Long.toString(revision))
                .build();
    }

    static String widgetFileName(String widgetId) {
        return WIDGET_FILE_PREFIX + widgetId;
    }

    static String widgetIdFromFileName(String fileName) {
        return fileName != null && fileName.startsWith(WIDGET_FILE_PREFIX)
                ? fileName.substring(WIDGET_FILE_PREFIX.length()) : DEFAULT_WIDGET_ID;
    }

    private Contract() {}
}

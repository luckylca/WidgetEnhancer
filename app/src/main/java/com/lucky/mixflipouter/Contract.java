package com.lucky.mixflipouter;

import android.net.Uri;

final class Contract {
    static final String MODULE_PACKAGE = "com.lucky.mixflipouter";
    static final String TARGET_PACKAGE = "com.miui.fliphome";
    static final String AUTHORITY = "com.lucky.mixflipouter.provider";
    static final Uri PROVIDER_URI = Uri.parse("content://" + AUTHORITY);
    static final String CUSTOM_TYPE = "mixflip_custom";
    static final String WIDGET_FILE_PREFIX = "mixflip_custom_widget_";
    static final String DEFAULT_WIDGET_ID = "default";
    static final String RUNTIME_VIEW_TAG = "mixflip_custom_runtime_overlay";
    static final String PREFS = "outer_widget";
    static final int BUTTON_COUNT = 4;

    static Uri mediaUri(String widgetId) {
        return Uri.parse("content://" + AUTHORITY + "/widgets/" + Uri.encode(widgetId) + "/media");
    }

    static Uri previewUri(String widgetId) {
        return Uri.parse("content://" + AUTHORITY + "/widgets/" + Uri.encode(widgetId) + "/preview");
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

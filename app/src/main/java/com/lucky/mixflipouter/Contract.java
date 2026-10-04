package com.lucky.mixflipouter;

import android.net.Uri;

final class Contract {
    static final String MODULE_PACKAGE = "com.lucky.mixflipouter";
    static final String TARGET_PACKAGE = "com.miui.fliphome";
    static final String MAML_CACHE_PACKAGE = "com.miui.home";
    static final String PERSONAL_ASSISTANT_PACKAGE = "com.miui.personalassistant";
    static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    static final String NETEASE_PACKAGE = "com.netease.cloudmusic";
    static final String GALLERY_PACKAGE = "com.miui.gallery";
    static final String AUTHORITY = "com.lucky.mixflipouter.provider";
    static final Uri PROVIDER_URI = Uri.parse("content://" + AUTHORITY);
    static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");
    static final Uri LYRICS_URI = Uri.parse("content://" + AUTHORITY + "/lyrics");
    static final Uri QS_URI = Uri.parse("content://" + AUTHORITY + "/qs");
    static final Uri PLAYBACK_ARTWORK_URI = Uri.parse("content://" + AUTHORITY + "/playback/artwork");
    static final Uri NOTIFICATIONS_URI = Uri.parse("content://" + AUTHORITY + "/notifications");
    static final String CUSTOM_TYPE = "mixflip_custom";
    static final String WIDGET_FILE_PREFIX = "mixflip_custom_widget_";
    static final String MAML_FILE_PREFIX = "mixflip_maml_";
    static final String DEFAULT_WIDGET_ID = "default";
    static final String RUNTIME_VIEW_TAG = "mixflip_custom_runtime_overlay";
    static final String PREFS = "outer_widget";
    static final String PREF_LYRICS_COMPAT_MODE = "lyrics_compat_mode";
    static final String EXTRA_WIDGET_ID = "widget_id";
    static final String EXTRA_SYSTEM_WIDGET_PICK_REQUEST = "mixflip_widget_pick_request";
    static final String EXTRA_SYSTEM_WIDGET_PROVIDER = "mixflip_widget_provider";
    static final String EXTRA_SYSTEM_WIDGET_COLS = "mixflip_widget_cols";
    static final String EXTRA_SYSTEM_WIDGET_ROWS = "mixflip_widget_rows";
    static final String SYSTEM_WIDGET_PICKER_PREFS = "system_widget_picker";
    static final String PREF_PENDING_SYSTEM_WIDGET_REQUEST = "pending_request";
    static final String PREF_PENDING_SYSTEM_WIDGET_ID = "pending_widget_id";
    static final String PREF_PENDING_SYSTEM_WIDGET_AT = "pending_at";
    static final String PREF_SYSTEM_WIDGET_RESULT_ID = "result_widget_id";
    static final String PREF_SYSTEM_WIDGET_RESULT_KIND = "result_kind";
    static final String PREF_SYSTEM_WIDGET_RESULT_PROVIDER = "result_provider";
    static final String PREF_SYSTEM_WIDGET_RESULT_COLS = "result_cols";
    static final String PREF_SYSTEM_WIDGET_RESULT_ROWS = "result_rows";
    static final String PREF_SYSTEM_WIDGET_RESULT_MAML_ID = "result_maml_id";
    static final String PREF_SYSTEM_WIDGET_RESULT_MAML_NAME = "result_maml_name";
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

    static Uri mamlUri(String widgetId) {
        return Uri.parse("content://" + AUTHORITY + "/maml/" + Uri.encode(widgetId));
    }

    static Uri mamlSlotUri(String widgetId, String componentId) {
        return Uri.parse("content://" + AUTHORITY + "/maml/" + Uri.encode(widgetId)
                + "/" + Uri.encode(componentId));
    }

    static String mamlFileName(String widgetId) {
        return MAML_FILE_PREFIX + widgetId;
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

package com.lucky.mixflipouter;

/** Stable action identifiers persisted in WidgetComponent rather than Android class names. */
final class ActionSpec {
    static final String LAUNCH_APP = "package";
    static final String OPEN_URI = "uri";
    static final String SEND_BROADCAST = "broadcast";
    static final String VOLUME_UP = "volume_up";
    static final String VOLUME_DOWN = "volume_down";
    static final String MUTE_TOGGLE = "mute_toggle";
    static final String FLASHLIGHT_ON = "flashlight_on";
    static final String FLASHLIGHT_OFF = "flashlight_off";
    static final String FLASHLIGHT_TOGGLE = "flashlight_toggle";
    static final String LOCK_SCREEN = "lock_screen";

    static boolean requiresValue(String type) {
        return LAUNCH_APP.equals(type) || OPEN_URI.equals(type) || SEND_BROADCAST.equals(type);
    }

    static boolean isFlashlight(String type) {
        return FLASHLIGHT_ON.equals(type)
                || FLASHLIGHT_OFF.equals(type)
                || FLASHLIGHT_TOGGLE.equals(type);
    }

    private ActionSpec() {}
}

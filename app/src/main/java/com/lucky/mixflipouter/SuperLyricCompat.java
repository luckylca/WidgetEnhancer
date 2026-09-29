package com.lucky.mixflipouter;

/**
 * Pure compatibility gate for the SuperLyric integration. Kept free of any
 * Binder/API classes so it can be unit-tested and consulted without touching
 * the SuperLyric service.
 *
 * The bridge ships SuperLyricApi 3.4 — the exact API revision SuperLyric 3.3
 * bundles. Anything else must never register a receiver: a Parcelable ABI
 * mismatch crashes the SuperLyric-Broadcaster thread inside system_server.
 */
final class SuperLyricCompat {
    /** SuperLyricApi 3.4 → BuildConfig.API_VERSION 34. */
    static final int SUPPORTED_API_VERSION = 34;
    static final String SUPPORTED_API_LABEL = "3.4";
    static final String SUPPORTED_SERVER_LABEL = "SuperLyric 3.3";

    static boolean shouldConnect(int localApiVersion) {
        return localApiVersion == SUPPORTED_API_VERSION;
    }

    /** Human readable compatibility verdict for diagnostics. */
    static String compatibilityLabel(int localApiVersion) {
        return shouldConnect(localApiVersion)
                ? "兼容 " + SUPPORTED_SERVER_LABEL
                : "不兼容（本地 API " + versionLabel(localApiVersion) + "，需要 "
                + SUPPORTED_API_LABEL + "）";
    }

    static String versionLabel(int apiVersion) {
        return (apiVersion / 10) + "." + (apiVersion % 10);
    }

    private SuperLyricCompat() {
    }
}

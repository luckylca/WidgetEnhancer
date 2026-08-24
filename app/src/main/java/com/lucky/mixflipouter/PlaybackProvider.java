package com.lucky.mixflipouter;

import android.os.Bundle;

/** Stable runtime contract so player-specific adapters do not leak into Widget rendering. */
interface PlaybackProvider {
    Bundle snapshot();

    Bundle execute(String action);
}

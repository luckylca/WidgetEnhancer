package com.lucky.mixflipouter;

import android.os.Bundle;

/** Player-independent contract for publishing and resolving synchronized lyric lines. */
interface LyricsProvider {
    Bundle publish(Bundle payload);

    Bundle snapshot(Bundle playback);
}

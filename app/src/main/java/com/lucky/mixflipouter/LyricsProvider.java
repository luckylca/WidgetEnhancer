package com.lucky.mixflipouter;

import android.os.Bundle;

/** Player-independent contract for publishing and resolving synchronized lyric lines. */
interface LyricsProvider {
    Bundle publish(Bundle payload, Bundle playback);

    /** Publishes a full-song lyric timeline fetched online for the current track. */
    Bundle publishContext(Bundle payload);

    Bundle stop(Bundle payload);

    Bundle snapshot(Bundle playback);
}

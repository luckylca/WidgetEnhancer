# Playback and lyrics architecture

## Current MediaSession bridge

The runtime talks only to the module provider. It never obtains notification-listener privileges inside FlipHome and never hooks a music app for basic playback data.

```text
user-approved NotificationListenerService
        -> active MediaController selection
        -> PlaybackProvider snapshot / transport controls
        -> guarded ConfigProvider call
        -> visible outer-screen song/artist components
```

The active-session selector prefers a session in `STATE_PLAYING`, then falls back to the first active session. Snapshots expose package, song, artist, album, state, estimated position and duration. Position is projected from `PlaybackState.getLastPositionUpdateTime()` while playback is active.

Runtime polling runs every two seconds only while a song or artist component is attached and visible. Hiding or detaching the Widget cancels polling. Previous, play/pause and next are semantic button actions routed through `MediaController.TransportControls`.

Notification access is never enabled silently. The configuration App links to Android's notification-listener settings and displays whether the user-approved listener is active.

## Provider boundaries

- `PlaybackProvider` is the stable playback contract used by the IPC layer.
- The current implementation is MediaSession-backed and player-agnostic.
- `LyricsProvider` will be separate because standard MediaSession metadata normally does not provide synchronized lyrics.
- Player-specific hooks must publish structured lyric/timing data to the module and must not leak reflected player classes into Widget JSON or rendering code.

## Pending device proof

- User-enable the listener in Android settings.
- Verify metadata and previous/play-pause/next with NetEase Cloud Music first.
- Verify session switching and no-session fallback.
- Add album-art, playback-state and progress components.
- Research a maintainable NetEase lyric source and implement current/next-line synchronization.

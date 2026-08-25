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

`PlaybackNotificationListener` remains the instant active-session callback path. The Provider also
rescans `MediaSessionManager.getActiveSessions()` at most once per second and requests listener
rebinding when its process starts. This handles HyperOS retaining notification-listener approval
without actually rebinding the service after an APK replacement, and catches players that destroy
and recreate their Session while an official music Widget is being added.

When a progress component is present, the same visible-only ticker runs every 500 ms and draws the estimated MediaSession position against duration. Album artwork is copied from MediaSession metadata into a module-private JPEG on a single background worker. FlipHome receives only a guarded read-only content URI; it never reads another player's private file or downloads artwork itself.

Notification access is never enabled silently. The configuration App links to Android's notification-listener settings and displays whether the user-approved listener is active.

## Provider boundaries

- `PlaybackProvider` is the stable playback contract used by the IPC layer.
- The current implementation is MediaSession-backed and player-agnostic.
- `LyricsProvider` remains separate because standard MediaSession metadata normally does not provide synchronized lyrics.
- Player-specific hooks must publish structured lyric/timing data to the module and must not leak reflected player classes into Widget JSON or rendering code.

## NetEase Cloud Music 9.5.61 research

The adapter was derived from the APK installed on the target phone (`com.netease.cloudmusic`, versionName `9.5.61`, versionCode `9005061`), rather than copied from an older online class map.

- Main-process class `com.netease.cloudmusic.module.lyric.e` is the `LrcLoaderManager` singleton.
- Its `o0(LyricInfo, boolean)` method is the centralized loaded-lyric delivery path.
- `LyricInfo` exposes music ID, sorted lines, lyric state and user offset.
- `CommonLyricLine` exposes original text, translation, romanization and start/end milliseconds.
- The adapter hooks this model callback and never scrapes a player-page `TextView`.

The NetEase process may call only two specially guarded provider methods: publish sanitized lyric timing data and report adapter compatibility. It cannot read Widget configuration or execute actions. At most 320 lines and 240 characters per text field are accepted to stay below Binder transaction limits, then stored as schema-v1 structured data. Runtime current/next-line resolution uses the MediaSession position and runs every 500 ms only while a lyric component is visible.

NetEase 9.5.61's device-specific status-lyric controller is not a reliable sole trigger on the target
process. The module therefore also reads the numeric `MEDIA_ID` from the user-approved MediaSession,
requests NetEase's public lyric endpoint on a single bounded worker, and parses original, translated
and romanized LRC tracks. A song-ID match prevents stale lyrics after a track switch. Native Hook
publication can still replace the fallback timeline when its structured callback is available.

The LSPosed scope remains mandatory for FlipHome. NetEase Cloud Music is an optional scope for the
native structured source; synchronized lyrics can fall back to the MediaSession song ID without it.

## Device proof

- Notification-listener access is granted through the supported ADB service command and Android has bound `PlaybackNotificationListener`.
- Metadata, duration, artwork, play/pause and next are proven with a real NetEase session.
- A real track switch refreshed the fallback from 3 to 45 lines, and a 49-line timeline advanced
  current/next text against the estimated MediaSession position without a second publication.
- The optional NetEase scope is enabled and all version-specific hooks install. Native callback
  coverage remains best-effort; the proven fallback is the current acceptance path.

# Action system

Actions are semantic string identifiers stored on button components. Runtime code resolves them at execution time; no Android class names or Binder details appear in user configuration.

| Action | Identifier | Value | Implementation | Current verification |
|---|---|---|---|---|
| Launch app | `package` | package or flattened component | foreground `ACTION_MAIN` launch | Existing package launch path; icon/name picker built |
| Open URI | `uri` | URI | `ACTION_VIEW` | Implemented |
| Broadcast | `broadcast` | action | guarded explicit user configuration | Implemented |
| Volume up/down | `volume_up`, `volume_down` | none | `AudioManager` music stream adjustment | Built; cover test pending |
| Mute toggle | `mute_toggle` | none | `AudioManager.ADJUST_TOGGLE_MUTE` | Built; cover test pending |
| Flashlight | `flashlight_on/off/toggle` | none | module-provider `CameraManager.setTorchMode` bridge | Device verified ON→OFF with automatic restoration |
| Do Not Disturb | `do_not_disturb_toggle` | none | module-provider `NotificationManager` policy access | ADB-granted and device verified priority→restored |
| Auto rotate | `auto_rotate_toggle` | none | module-provider `Settings.System.ACCELEROMETER_ROTATION` | ADB-granted and device verified off→restored |
| Lock screen | `lock_screen` | none | power key injection from privileged FlipHome UID | Permission proven; cover test pending |
| Previous track | `media_previous` | none | guarded `PlaybackProvider` / `MediaController.TransportControls` | Built; notification access and device test pending |
| Play/pause | `media_play_pause` | none | guarded `PlaybackProvider` / `MediaController.TransportControls` | Built; notification access and device test pending |
| Next track | `media_next` | none | guarded `PlaybackProvider` / `MediaController.TransportControls` | Built; notification access and device test pending |
| Quick Settings tile | `qs_tile` | active tile spec | optional SystemUI bridge delegates to the live `QSTile` object | Discovery/guard UX verified; live click pending optional scope |

## Security boundaries

- Only the module UID and `com.miui.fliphome` may call configuration or action provider methods.
- Gallery receives only a read grant for one media URI; it cannot call configuration methods.
- Flashlight requires standard user-approved Camera permission and targets only a detected back-facing flash camera.
- Do Not Disturb and auto-rotate use Android's explicit notification-policy and modify-system-settings grants. They do not require SystemUI or a QS tile.
- MediaSession data and transport controls require the user to explicitly enable the module's notification listener in Android settings.
- Synchronized NetEase lyrics require the separate optional `com.netease.cloudmusic` LSPosed scope; the NetEase UID can only publish bounded lyric data and adapter health.
- Quick Settings actions require the optional `com.android.systemui` scope. Only the SystemUI UID can publish/take/complete bounded requests, and only active available specs can be submitted.
- Installed third-party `TileService` entries are discovery-only until the user adds them to HyperOS control center; the module never binds to them directly.
- Unsupported or denied operations return a structured failure and show a fallback toast instead of crashing FlipHome.
- Safe mode suppresses all custom runtime data while preserving user configuration.

## Capability UX

The Settings status card exposes Camera permission state and an auto-restoring flashlight diagnostic. The app picker enumerates launchable activities with icon and label, then stores the flattened launch component so users never need to type package names.

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
| Lock screen | `lock_screen` | none | power key injection from privileged FlipHome UID | Permission proven; cover test pending |
| Previous track | `media_previous` | none | guarded `PlaybackProvider` / `MediaController.TransportControls` | Built; notification access and device test pending |
| Play/pause | `media_play_pause` | none | guarded `PlaybackProvider` / `MediaController.TransportControls` | Built; notification access and device test pending |
| Next track | `media_next` | none | guarded `PlaybackProvider` / `MediaController.TransportControls` | Built; notification access and device test pending |

## Security boundaries

- Only the module UID and `com.miui.fliphome` may call configuration or action provider methods.
- Gallery receives only a read grant for one media URI; it cannot call configuration methods.
- Flashlight requires standard user-approved Camera permission and targets only a detected back-facing flash camera.
- MediaSession data and transport controls require the user to explicitly enable the module's notification listener in Android settings.
- Unsupported or denied operations return a structured failure and show a fallback toast instead of crashing FlipHome.
- Safe mode suppresses all custom runtime data while preserving user configuration.

## Capability UX

The Settings status card exposes Camera permission state and an auto-restoring flashlight diagnostic. The app picker enumerates launchable activities with icon and label, then stores the flattened launch component so users never need to type package names.

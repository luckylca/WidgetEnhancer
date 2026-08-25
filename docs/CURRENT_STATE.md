# Current state

Last synchronized: 2026-08-25 21:25 CST.

## Product identity

- Module/application ID: `com.lucky.mixflipouter`
- Required LSPosed scope: `com.miui.fliphome`
- Optional scopes: `com.netease.cloudmusic` for the native structured-lyric source and `com.android.systemui` for the advanced QS adapter
- Baseline: MIX Flip 1 (`ruyi`), HyperOS `OS3.0.303.0.WNICNXM`, Android 16
- Current build: `0.6.9-p1` (versionCode 16)
- MixFlipMod remains reverse-engineering reference material only

## Implemented product path

The module injects provider-backed `FlipWidgetInfo` records into FlipHome's official catalogue, maps them to the `自定义` group and lets the official ViewModel/Room path own selection, ordering and persistence. Runtime content remains inside a real `FlipMaMlHostView`, with the custom component renderer added as an overlay.

The configuration app manages multiple schema-v3 Widgets with atomic storage and private assets.
`WidgetTypeRegistry` is the product extension point: it currently registers media, music and
shortcut Widgets, but does not impose a three-type limit. Adding a later outer-screen Widget means
registering a new type and supplying its own editor, fixed semantic layout and runtime behavior.

The normal user flow no longer exposes the internal component canvas, templates, arbitrary
positioning or advanced styling. Media selects one image or video, supports portrait/landscape
rotation, and uses a fixed output frame for direct pan and pinch zoom. Video editing uses the first
frame; the saved crop is shared by catalogue preview and image/video runtime. Video loop/mute remain
the only playback settings.
Music uses a full-screen blurred album-art background, shows lyrics by default and maps single tap
to play/pause, upper-half double tap to previous, lower-half double tap to next, upper-half long
press to repeatedly raise volume, and lower-half long press to repeatedly lower volume until the
touch ends. A shortcut
Widget contains up to six bound actions or apps. `ButtonLayoutEngine` selects one of six fixed
normalized templates: centered one, vertical two/three, centered 2x2, symmetric 2+1+2 or 2x3.
Visual icon size is separate from the larger touch target. This per-Widget button bound is separate
from the number of outer-screen Widget pages; FlipHome's former five-page limit is bypassed.
Playback and lyric notifications update the existing music view only; they are isolated from the
configuration-change channel so a track switch does not rebuild the pager or change its page.

Schema-v2 records migrate in place: media, playback and button components are used to infer the
new `typeId`, while IDs and private assets remain unchanged. The component tree remains an internal
runtime and package-compatibility representation.

The app also exposes a diagnostics page and a versioned `mixflip-diagnostics` JSON export. It reports versions, permissions, Hook timestamps and aggregate subsystem state without exporting Widget names/IDs, media metadata, lyrics, paths or the user's tile inventory.

Common controls use direct semantic actions: app/URI/broadcast launch, volume, mute, flashlight, Do Not Disturb, auto-rotate, lock and media transport. The real SystemUI QSTile bridge is optional and reserved for active tile-only capabilities.

## Device evidence

- Two user Widgets appear dynamically in the official `自定义` group and build real runtime hosts.
- Image and video rendering, official sizing/corners, component composition, flashlight diagnostics and package import/export have physical-device evidence.
- Shared image/video crop transforms are device-proven with portrait/landscape editor renders and
  transformed image/video runtime captures; the temporary test video was removed afterward.
- Notification-listener access is granted and Android binds `PlaybackNotificationListener`; NetEase exposes a real MediaSession.
- NetEase and SystemUI optional LSPosed scopes are selected. A restarted NetEase process logged successful lyric-adapter installation.
- Do Not Disturb and auto-rotate direct actions were toggled and automatically restored while their underlying system values were sampled through ADB.
- A cold boot proved the SystemUI adapter loads. Publication now waits for first unlock and uses coalescing/backoff when the credential-protected provider is unavailable.
- A controlled SystemUI-only reload loaded the fixed Hook without disturbing Keyguard. Pre-unlock waiting was measured at one log every 30 seconds, replacing the old millisecond flood.
- The phone is now first-unlocked. Provider access recovered and the live SystemUI snapshot reports
  a ready bridge with 14 of 15 active tiles available.
- The diagnostics page and schema-v1 snapshot are device-proven. Both Widgets persisted; notification
  listener, Camera, DND and write-settings access were ready after supported ADB grants.
- A real NetEase session is device-proven for metadata, duration, album art and play/pause through
  the module's own transport-control implementation. MediaSession-ID/API lyrics are also proven:
  cutover refreshed 3 to 45 lines, and current/next text advanced on a 49-line timeline.
- MediaSession discovery now has a Provider-owned one-second system rescan fallback. A physical
  reinstall reproduced HyperOS leaving the notification listener disconnected; without manually
  rebinding it, the fallback recovered the playing NetEase session and its cached 58-line lyric.
- 48 unit tests, Debug Lint, Debug assembly and Release assembly pass.

## Remaining acceptance work

- Execute one reversible advanced tile click.
- Complete repeated official/custom/official cover swipes, cover action checks, screen-off/on, process-death and restart matrices.
- Finish type-specific UI polish and release packaging.

The detailed milestone ledger is maintained in [`../STATUS.md`](../STATUS.md) and [`../TARGET.md`](../TARGET.md).

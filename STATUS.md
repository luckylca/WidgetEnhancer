# Status

Last updated: 2026-08-24 09:22 CST

## DONE

- Unified all work under `~/Desktop/project/mixflip-custom-widget/`; MixFlipMod remains reference-only and is neither modified nor required.
- Decompiled and traced the MIX Flip 1 HyperOS 3 FlipHome, MiSettings and Settings packages.
- Confirmed Settings links to FlipHome's exported `WidgetSettingsActivity`; no Settings/SystemUI Hook is needed for P0.
- Replaced the demo's unconditional `WidgetPagerView.setViews()` append with the official catalog/selection flow.
- Injected a dynamic `自定义` group and provider-backed preview into the official widget page.
- Added the custom item through the official UI; `我的` changed from 3 to 4 and the selected state survived process death/cold start.
- Kept runtime entries type-safe by overlaying `MediaWidgetView` inside a real `FlipMaMlHostView` instead of inserting an incompatible native child into `FlipWidgetManager`.
- Added provider health reports for catalog and runtime stages.
- Enabled the LSPosed module with scope limited to `com.miui.fliphome` and verified Hook loading on-device.
- Simulated `OPENED → CLOSED`, observed the real 1208×1392 cover launcher, and confirmed `runtime_ok=true` plus creation of `mixflip_custom_widget_default` with no crash.
- Restored the device to physical/base `OPENED` state after the cover test.
- Migrated the single legacy configuration to atomic schema-v1 JSON with stable Widget IDs, per-Widget media storage and revisions.
- Added a Material 3 / Dynamic Color Widget list and editor shell with create, rename, enable, edit, copy and delete operations.
- Verified two user Widgets (one video and one image) dynamically appear in the official `自定义` group; FlipHome injected both and built a five-item runtime list.
- Replaced raw official-list media paths with cached 440×720 preview PNGs using center crop and rounded alpha; video previews use the first frame plus a bottom-right play badge.
- Matched runtime media to FlipHome's own 103dp×174dp container and `launcher_widget_radius` (20dp), removing the incorrect nested 32dp image clip that exposed black corners.
- Replaced `VideoView`/`SurfaceView` rendering with a crop-aware `TextureView` player so official outline clipping also applies to video frames.
- Added a FlipHome content observer that rebuilds selected custom hosts and refreshes an open official settings page after repository changes.
- Measured the real custom runtime at 335×566 inside a 335×566 host, matching FlipHome's 103dp×174dp resource at device density.
- Built APK 0.3.1-p1 successfully and passed Android lint.
- Added global safe mode, HyperOS compatibility/catalog/runtime/live-refresh health stages and a Material 3 status card.
- Migrated the on-device repository from schema v1 to schema v2 without losing either user Widget, media file, enabled state or stable ID.
- Added a 440×720 component tree for image, video, text, time and buttons, including geometry, z-index, visibility, lock, style and action records.
- Added schema migration/round-trip/action unit tests; `testDebugUnitTest`, `lintDebug` and `assembleDebug` pass together.
- Reworked the runtime to render component geometry and z-order while retaining the verified `TextureView` video path and visibility-aware player lifecycle.
- Added a Material 3 visual canvas with selection, drag, resize handle, lock, layer movement, copy, delete and editable component properties.
- Verified the visual editor on-device: the migrated image fills the canvas, selection outline works, and a new text layer renders above it without altering saved user data.
- Added an icon/name launchable-app picker so launch actions no longer require manual package names.
- Added typed volume, mute, flashlight and lock actions. Flashlight runs through a narrowly guarded provider bridge and requires the standard Camera permission once.
- Added image-click handling that targets Xiaomi Gallery instead of falling through to the official host's Xiaomi Sports action.
- All fold simulations now use cleanup traps that always reset `device_state`; every run ends with committed/base state `OPENED` and no override.
- Built the current component-editor/action milestone as APK 0.4.0-p1; unit tests, Android lint and debug assembly pass.
- Granted Camera through the standard Android permission flow and verified the auto-restoring flashlight diagnostic in CameraService: camera 0 turned on at 08:55:17 and off at 08:55:18.
- Verified the launchable-app picker on-device with icons, localized labels, package names and scrolling.
- Observed a saved user composition containing image + resized time + resized TikTok button; FlipHome reloaded both custom Widget hosts on schema v2 without a crash. Pixel proof remains gated by the secure cover lock screen.
- Added a player-agnostic `PlaybackProvider` contract backed by a user-approved notification listener and MediaSession controller selection.
- Added song/artist canvas components plus previous, play/pause and next actions; runtime playback polling stops whenever the Widget is hidden or detached.
- Exposed media notification-access status in the configuration App without silently granting privileged access.
- Upgraded live-refresh health reporting to record actual refresh success/failure instead of only observer registration.
- Reverse-engineered the target phone's NetEase Cloud Music 9.5.61 APK and located the model-level `LrcLoaderManager` loaded-lyric callback and timed line records.
- Added a guarded, optional NetEase LSPosed adapter that publishes sanitized original/translated/romanized lyric lines without scraping UI text.
- Added a generic `LyricsProvider`, persisted lyric schema, binary-search timeline resolution, current/next lyric canvas components and visibility-aware 500 ms runtime updates.
- Bumped the development APK to 0.5.0-p1 (versionCode 6) because synchronized lyrics add an optional `com.netease.cloudmusic` LSPosed scope.

## IN PROGRESS

- P1: finish exact canvas property UX and prove saved text/time/button components on the unlocked cover runtime.
- P1: verify volume, lock and flashlight actions from the real cover page.
- P1: finish the gallery read-grant regression after the secure cover display is unlocked.
- P2: enable and device-test MediaSession access and the optional NetEase scope, verify lyric timing/offset, then add album art and progress.

## FAILED

- Android 16 rejects ordinary ADB and instrumentation key injection. Repeatable touch automation now uses Monkey script pointer events instead.
- A first persistence check force-stopped FlipHome before confirming the Activity had stopped; a corrected Back/touch + wait + cold-start test passed.
- Android 16 prevents automated acceptance of runtime permission dialogs. The standard Camera prompt therefore requires one physical tap by the user.

## BLOCKED

- Pixel-level cover-page screenshot and repeated official/custom/official swipe validation require unlocking the secure cover lock screen. ADB correctly cannot bypass the user's authentication. Runtime construction and lifecycle were still verified from health and FlipHome logs.

## TODO NEXT

1. Verify the saved image/time/TikTok composition visually on an unlocked cover session.
2. Verify gallery open/read, volume and lock actions on the cover page.
3. Complete repeated official ↔ custom ↔ official swipe regression.
4. Finish component property polish and remove the remaining legacy fixed-button editor projection.
5. Enable the media notification listener manually and verify NetEase metadata and transport controls.
6. Verify current/next lyric timing against real playback, including translated lyrics and user offset.
7. Implement album art/progress components.

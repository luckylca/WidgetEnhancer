# Status

Last updated: 2026-08-24 23:30 CST

Current development build: `0.6.5-p1` (versionCode 12), targeting Xiaomi MIX Flip 1
`ruyi`, Android 16 / HyperOS 3.0.303.0.

## DONE

- Unified all work under `~/Desktop/project/mixflip-custom-widget/`; MixFlipMod remains reference-only and is neither modified nor required.
- Decompiled and traced the MIX Flip 1 HyperOS 3 FlipHome, MiSettings and Settings packages.
- Confirmed Settings links to FlipHome's exported `WidgetSettingsActivity`; no Settings/SystemUI Hook is needed for P0.
- Replaced the demo's unconditional `WidgetPagerView.setViews()` append with the official catalog/selection flow.
- Injected a dynamic `自定义` group and provider-backed preview into the official widget page.
- Added custom items through the official Room, selection, sorting and process-death persistence flow.
- Kept runtime entries type-safe by overlaying `MediaWidgetView` inside a real `FlipMaMlHostView`.
- Added guarded provider health reports for compatibility, catalog, runtime and live refresh.
- Enabled and verified the LSPosed module in `com.miui.fliphome`; NetEase is an optional second scope.
- Simulated the cover state, observed the real 1208×1392 cover launcher and restored committed/base state to `OPENED` with no override after every run.
- Migrated the repository to atomic schema-v3 JSON with stable Widget IDs, type IDs, revisions and per-Widget assets.
- Added a Material 3 Widget list with registry-driven creation, rename, enable, edit and delete operations.
- Verified two user Widgets dynamically appear in the official `自定义` group and build real runtime hosts.
- Matched official preview and runtime dimensions/corners; video uses a crop-aware `TextureView` and a visible play badge in list previews.
- User confirmed both image and video rendering on the cover screen are now correct.
- Kept a semantic 440x720 component tree as the internal runtime representation for image, video, buttons, playback metadata, lyrics, album art and progress.
- Replaced the exposed free-canvas editor with small type-specific editors and fixed layouts.
- Added launchable-app picker and typed volume, mute, flashlight, lock, app, URI, broadcast and media-control actions.
- Granted Camera through the normal system prompt and physically verified the auto-restoring flashlight diagnostic in CameraService.
- Removed runtime media click-through so media-display Widgets remain display-only.
- Added player-agnostic MediaSession metadata/transport plumbing and visible-only playback polling.
- Made MediaSession discovery self-healing: the Provider requests notification-listener rebind and
  directly rescans enabled active sessions once per second. Playback and lyric recovery no longer
  depend on HyperOS delivering `onListenerConnected()` after an APK update or Session recreation.
- Reverse-engineered NetEase Cloud Music 9.5.61 and added guarded native and MediaSession-ID/API lyric sources with persisted current/next timeline resolution.
- Added asynchronous album-art storage and playback-progress components.
- Verified a real NetEase MediaSession end to end: session discovery, metadata, duration,
  artwork and the module's `MediaController.TransportControls` play/pause path all work.
- Added a central `WidgetTypeRegistry`; the current media, music and shortcut entries are built-ins rather than a fixed product limit.
- Added a media editor for one full-screen image or looping video, including loop and mute options.
- Added a fixed music layout with blurred full-screen album art, lyrics-first presentation,
  single-tap play/pause, upper/lower double-tap previous/next, and upper/lower long-press
  continuous volume up/down control that stops on release, swipe cancellation or view detachment.
- Added a shortcut editor with up to six buttons per Widget, each bound to a system action or installed app.
- Added six normalized `ButtonLayoutEngine` templates: centered one; vertical two and three;
  centered 2x2; symmetric 2+1+2; and 2x3. Visual icon sizes scale separately from larger touch
  targets, while the editor preview, catalogue preview and cover runtime share the same engine.
- Removed FlipHome's separate five-page limit at its add-control, adapter and Room-persistence
  checks, so the number of outer-screen Widget pages is no longer capped at five.
- Removed the borrowed Xiaomi MAML renderer/touch target from custom runtime hosts, preventing
  empty shortcut-page taps from launching the original Xiaomi Health widget.
- Made music and shortcut gestures cancel their own click after touch-slop movement and release
  interception to FlipHome in every swipe direction.
- Separated Widget configuration notifications from lyric and Quick Settings updates, preventing
  track changes from rebuilding FlipHome's pager and returning to its first page.
- Enlarged music text while giving all three lyric rows independent bounds, line limits and
  bounded auto-sizing so long credit lines cannot be clipped behind the adjacent lyric.
- Registered callbacks for every available MediaSession and select the newest playing session,
  preserving the current source on ties. NetEase play/pause changes refresh immediately even when
  a stale Bilibili session remains in the system stack.
- Added versioned `.mixflipwidget.zip` import/export with entry allowlisting, size limits, schema checks, media SHA-256 and failure cleanup.
- Completed a real-device export/import round trip. The imported video hash matched the source; the temporary Widget and Download package were removed, while the original two IDs and source hash remained unchanged.
- Reverse-engineered HyperOS SystemUI's active `QSTile` host and added an optional SystemUI-scoped bridge that delegates to the real live tile instead of binding directly to third-party services.
- Added a tile picker that merges active SystemUI tiles with installed `TileService` capabilities. Installed-but-not-active and unavailable tiles remain visible but disabled.
- Added a bounded, caller-checked, expiring QS request mailbox plus unit coverage. ADB shell was rejected from both SystemUI-only and FlipHome-only provider methods.
- Verified the discovery-only picker and disabled-tile warning on the physical phone while preserving both user Widgets and the unfolded device state.
- Removed the legacy four-button panel and the later free-canvas controls from the normal product flow. Components remain an internal compatibility/runtime format.
- Added direct Do Not Disturb and auto-rotate actions that do not depend on QS tiles. Both special permissions were granted through supported ADB commands and both actions were device-verified with exact automatic state restoration.
- Granted notification-listener access through ADB and verified Android bound `PlaybackNotificationListener` while a real NetEase MediaSession was present.
- Enabled the optional NetEase and SystemUI LSPosed scopes through Monkey pointer automation. A restarted NetEase process logged successful installation of the structured lyric adapter.
- Added a NetEase API fallback keyed only by the active MediaSession `MEDIA_ID`. It parses bounded
  original, translated and romanized LRC tracks while retaining the native Hook as the preferred source.
- Device-verified real synchronized lyrics: a cutover from a 3-line track to a 45-line track refreshed
  automatically, and a separate 49-line timeline advanced current/next text without republishing.
- Cold-booted the physical phone and proved the SystemUI adapter loads. The boot test exposed provider-unavailable log flooding before first unlock; publication is now coalesced, gated on `UserManager.isUserUnlocked()` and failure-backed-off.
- Reloaded only SystemUI with the fixed APK while the phone remained securely locked. The new process preserved Keyguard and emitted exactly one pre-unlock wait log every 30 seconds instead of the former millisecond flood.
- Completed first unlock through a temporary AOAv2 USB HID keyboard without storing the
  credential in the repository. Provider access and the SystemUI QS heartbeat recovered after
  unlock; the current snapshot reports 14 of 15 active tiles available.
- Added an in-app diagnostics page and versioned JSON export covering device/app/package versions, conservative Root/LSPosed evidence, permissions, Hook timestamps, Widget aggregates, playback/lyrics and the optional QS bridge. User content, Widget IDs, paths and tile inventory are excluded.
- Physically verified the diagnostics UI and schema-v1 JSON snapshot. It reports both preserved
  Widgets, connected notification listener, direct-control permissions, live playback/artwork and
  a ready QS bridge while keeping all declared privacy flags false.
- Added Debug-only exported device-test activities for deterministic ADB diagnostics and media
  regression. Release builds retain the non-exported diagnostics Activity and contain no test bridge.
- Simplified Settings to connection status, safe mode, permissions, diagnostics, system Widgets and the registry-driven Widget list. Import remains for compatibility; copy/export are not exposed.
- Added type inference for schema-v2 data so existing image/video and button Widgets migrate in place.
- `testDebugUnitTest` (44 tests), `lintDebug`, `assembleDebug` and `assembleRelease` pass together for the current milestone.

## IN PROGRESS

- P1: verify each type-specific editor and its fixed runtime layout on the real cover display.
- P1: verify shortcut volume, lock, flashlight and app-launch actions from the real cover page.
- P2: MediaSession metadata/artwork/controls and synchronized NetEase lyrics are device-proven.
- P3: the recovered SystemUI snapshot heartbeat is proven; one reversible advanced tile click remains.
- P4: full release regression and evidence bundle; diagnostics page/report is implemented.

## FAILED / LEARNED

- HyperOS rejects ordinary `adb shell input` injection. Monkey delivers some events but is unreliable
  for Material buttons and this dual-display input topology; temporary AOAv2 USB HID is the reliable
  unattended fallback for key and pointer input.
- Android security prompts are not bypassed. Supported ADB notification commands and AppOps can
  grant the media-listener, DND and write-settings capabilities; Camera was granted through its normal prompt.
- Supported ADB service commands successfully grant notification-listener and notification-policy access; `appops` grants modify-system-settings access without UI clicking.
- HyperOS rejected ADB/Monkey on the secure PIN keypad. AOAv2 behaved as a physical USB keyboard
  and completed the user-authorized unlock; no credential or unlock source is kept in this project.
- Xiaomi's secure file picker hides unknown extensions. Share packages therefore use `.mixflipwidget.zip` while retaining `format: mixflipwidget` inside the manifest.
- NetEase 9.5.61's Flyme status-lyric controller depends on initialization paths that are not stable
  in this process. The module therefore keeps its native structured Hook but uses the numeric
  MediaSession song ID and NetEase's lyric endpoint as a bounded fallback instead of spoofing a device.

## TODO NEXT

1. Complete one reversible advanced QS bridge click; keep all common control actions on the direct path.
2. Run controlled cover swiping, screen-off/on, process-death and restart matrices with cleanup traps.
3. Finish type-specific UI polish, then prepare the release APK/source/evidence bundle.

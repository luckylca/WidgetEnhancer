# Status

Last updated: 2026-08-24 10:00 CST

Current development build: `0.6.0-p1` (versionCode 7), targeting Xiaomi MIX Flip 1
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
- Migrated the repository to atomic schema-v2 JSON with stable Widget IDs, revisions and per-Widget assets.
- Added a Material 3 Widget list with create, rename, enable, edit, copy, export and delete operations.
- Verified two user Widgets dynamically appear in the official `自定义` group and build real runtime hosts.
- Matched official preview and runtime dimensions/corners; video uses a crop-aware `TextureView` and a visible play badge in list previews.
- User confirmed both image and video rendering on the cover screen are now correct.
- Added a 440×720 component tree for image, video, text, time, buttons, playback metadata, lyrics, album art and progress.
- Added a visual canvas with selection, drag, resize, lock, layer movement, copy, delete and component properties.
- Added launchable-app picker and typed volume, mute, flashlight, lock, app, URI, broadcast and media-control actions.
- Granted Camera through the normal system prompt and physically verified the auto-restoring flashlight diagnostic in CameraService.
- Changed image clicks to target Xiaomi Gallery with a bounded one-URI read grant.
- Added player-agnostic MediaSession metadata/transport plumbing and visible-only playback polling.
- Reverse-engineered NetEase Cloud Music 9.5.61 and added a guarded structured-lyric adapter with persisted current/next timeline resolution.
- Added asynchronous album-art storage and playback-progress components.
- Added photo, video, music/lyrics, quick-control and clock templates as editable `WidgetConfig` data.
- Added versioned `.mixflipwidget.zip` import/export with entry allowlisting, size limits, schema checks, media SHA-256 and failure cleanup.
- Completed a real-device export/import round trip. The imported video hash matched the source; the temporary Widget and Download package were removed, while the original two IDs and source hash remained unchanged.
- `testDebugUnitTest`, `lintDebug` and `assembleDebug` pass together for the current milestone.

## IN PROGRESS

- P1: finish exact canvas property UX and remove the remaining legacy fixed-button projection.
- P1: prove saved text/time/button composition and Gallery read grant on the unlocked cover runtime.
- P1: verify volume, lock and flashlight actions from the real cover page.
- P2: device-test MediaSession and NetEase lyrics after the user grants notification access and enables the optional scope.
- P3: research and implement the Quick Settings Tile bridge.
- P4: diagnostics page/report and full release regression.

## FAILED / LEARNED

- Android 16 rejects ordinary ADB and instrumentation key injection. Repeatable touch automation uses Monkey pointer scripts; system key injection remains blocked.
- Android 16 prevents automated acceptance of runtime permission dialogs; security-sensitive prompts require a physical user tap.
- Xiaomi's secure file picker hides unknown extensions. Share packages therefore use `.mixflipwidget.zip` while retaining `format: mixflipwidget` inside the manifest.

## BLOCKED ON USER-GATED STATE

- Pixel-level locked-cover screenshots and repeated official/custom/official swipe validation require the user to unlock the cover; the module does not bypass authentication.
- MediaSession verification requires manual notification-listener approval.
- NetEase lyric verification requires manually adding `com.netease.cloudmusic` to the LSPosed module scope and restarting it.

## TODO NEXT

1. Research the HyperOS Quick Settings Tile discovery and execution path.
2. Add a diagnostics page and versioned diagnostic-report export.
3. Complete MediaSession/NetEase playback tests when the two manual grants are ready.
4. Run controlled cover swiping, screen-off/on, process-death and restart matrices with cleanup traps.
5. Add Undo/Redo, alignment and grid polish, then prepare the release APK/source/evidence bundle.

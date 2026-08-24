# Status

Last updated: 2026-08-24 08:17 CST

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

## IN PROGRESS

- P0: move video from the proof-of-concept `VideoView` lifecycle to a selection-aware, recoverable player.
- P0: add safe mode and adapter compatibility checks.
- P1: evolve schema v1 from the migrated media/action model to a canvas + component tree.
- P1: build the visual component editor on top of the Material 3 list/editor shell.

## FAILED

- Android 16 rejects ordinary ADB and instrumentation key injection. Repeatable touch automation now uses Monkey script pointer events instead.
- A first persistence check force-stopped FlipHome before confirming the Activity had stopped; a corrected Back/touch + wait + cold-start test passed.

## BLOCKED

- Pixel-level cover-page screenshot and repeated official/custom/official swipe validation require unlocking the secure cover lock screen. ADB correctly cannot bypass the user's authentication. Runtime construction and lifecycle were still verified from health and FlipHome logs.

## TODO NEXT

1. Add provider content observation and live runtime refresh without restarting FlipHome.
2. Introduce canvas/component records for image, video, text, time and button layers.
3. Add safe mode plus Hook adapter compatibility checks.
4. Replace the proof-of-concept video lifecycle with a visibility-aware player controller.
5. Complete pixel-level image/video regression on an unlocked cover session.

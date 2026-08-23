# Status

Last updated: 2026-08-24 07:45 CST

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
- Built APK 0.2.0-p0 successfully and passed Android lint.

## IN PROGRESS

- P0: replace the single legacy SharedPreferences widget with a versioned multi-widget repository and revision notifications.
- P0: move video from the proof-of-concept `VideoView` lifecycle to a selection-aware, recoverable player.
- P0: add safe mode and adapter compatibility checks.
- P1: build the Material 3 widget list/editor and component model.

## FAILED

- Android 16 rejects ordinary ADB and instrumentation key injection. Repeatable touch automation now uses Monkey script pointer events instead.
- A first persistence check force-stopped FlipHome before confirming the Activity had stopped; a corrected Back/touch + wait + cold-start test passed.

## BLOCKED

- Pixel-level cover-page screenshot and repeated official/custom/official swipe validation require unlocking the secure cover lock screen. ADB correctly cannot bypass the user's authentication. Runtime construction and lifecycle were still verified from health and FlipHome logs.

## TODO NEXT

1. Introduce `WidgetRepository` schema v1 with multiple widget IDs and per-widget assets.
2. Make catalog injection enumerate every enabled repository widget.
3. Add provider revisions/content observation and live runtime refresh.
4. Replace the configuration screen with a Material 3 list + editor shell.
5. Complete image/video gesture and resource regression on an unlocked cover session.

# Current state

## Product identity

- Module/application ID: `com.lucky.mixflipouter`
- LSPosed scope: only `com.miui.fliphome`
- Baseline: MIX Flip 1 (`ruyi`), HyperOS `OS3.0.303.0.WNICNXM`, Android 16
- Current build: `0.2.0-p0`
- MixFlipMod: pulled for reverse-engineering reference only; never patched or used as a dependency

## P0 integration now implemented

The old demo hook that appended a native page to every `WidgetPagerView.setViews(List)` call has been removed.

The module now follows the official system path:

1. Hook `FlipWatchDefaultConfig.loadAllWidget()` and append provider-backed `FlipWidgetInfo` records.
2. Hook the widget type mapping so these records appear under `自定义` in FlipHome's exported official settings page.
3. Let `WidgetViewModel` and `FlipWidgetModel` perform the official add/remove, ordering, LiveData and Room/Gson persistence flow.
4. When `FlipMaMlWidgetCompat.createMamlHostView()` receives one of our selected IDs, retain a real `FlipMaMlHostView` and add the native runtime renderer as a match-parent overlay.

This keeps `FlipWidgetManager.WidgetViewInfo`, MAML lifecycle commands, pager containers and background helpers type-correct.

## On-device evidence

- LSPosed shows the module enabled and only `外屏桌面 / com.miui.fliphome` selected.
- Official widget settings showed a distinct `自定义` group with provider-backed media preview.
- Clicking its green add control changed `我的` from 3 to 4.
- Leaving normally, waiting for Room's asynchronous write, killing FlipHome and reopening preserved 4 items and the selected check.
- Device-state override `CLOSED` started the real `FlipLauncher` at 1208×1392.
- Provider diagnostics reported both `catalogue_ok=true` and `runtime_ok=true`.
- FlipHome logged successful creation of `mixflip_custom_widget_default`; no fatal exception occurred.

## Corrected swipe/freeze analysis

`WidgetPagerView.isViewValid()` first requires the selected child to be a `FlipMaMlHostView` with a `FlipWidgetInfo` tag before analytics casts it. Therefore the demo's old plain native child was excluded rather than directly crashing from a String tag. The old architecture was still incomplete because it bypassed `FlipWidgetManager`'s selected list, cache and lifecycle.

The new architecture removes that mismatch: the system sees a genuine MAML host, while the custom native renderer is its child overlay. Parent `WidgetPagerView.onInterceptTouchEvent()` retains vertical-gesture priority once movement exceeds touch slop. Final repeated swipe testing remains pending only because the cover session is behind secure authentication.

## Current functional limits

- Repository currently exposes one migrated `default` widget.
- Configuration UI is still the proof-of-concept programmatic Views screen, not Material 3.
- Image rendering works through a protected content URI and sampled decode.
- Video still uses `VideoView`; it needs selection-aware lifecycle, retry/fallback and crop configuration.
- Four fixed action slots support app/component, URI and broadcast actions; they are not yet the requested component tree.

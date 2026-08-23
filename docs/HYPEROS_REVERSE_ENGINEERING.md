# HyperOS reverse engineering

## Device baseline

| Field | Value |
|---|---|
| Device | Xiaomi MIX Flip 1 (`2405CPX3DC`, `ruyi`) |
| HyperOS | `OS3.0.303.0.WNICNXM` |
| Android | 16 / API 36 |
| Cover display | 1208 × 1392 @ 520 dpi; physical ID `local:4630947108695800452` |
| Inner display | 1224 × 2912 @ 520 dpi; physical ID `local:4630947108695800451` |
| Fold states | 0 CLOSED, 1 TENT, 2 HALF_OPENED, 3 OPENED, 4–6 reverse/presentation |
| Root/Hook | KernelSU; LSPosed Zygisk active |

Logical display IDs swap when folded. Tests resolve the physical display ID instead of assuming display 0/1.

## Actual settings chain

The Android/Xiaomi Settings page does not implement the widget catalog. `MiuiFlipScreenPreferenceController` links to the exported FlipHome activity:

`com.miui.fliphome.settings.widget.WidgetSettingsActivity`

That activity owns a `WidgetViewModel`, observes `mWidgetLiveData`, renders `WidgetAdapter`, and calls `saveData()` from `onStop()`.

`WidgetViewModel` loads:

- selected IDs from `FlipWidgetModel.getAddedListBlocked()`;
- catalog entries from `FlipWatchDefaultConfig.loadAllWidget()`;
- category names through `getNameOfType(String)` and `mWidgetTypeMap`.

Add/remove/move operations update `mAddedIdList`; the model persists the selected `List<FlipWidgetInfo>` as Gson JSON in a single Room entity. The official limit is 1–5 widgets.

## Runtime chain

`FlipWidgetManager` observes `FlipWidgetModel.getWidgetLiveData()` and, for every selected record:

1. calls private `createMamlView(FlipWidgetInfo)`;
2. obtains `FlipMaMlHostView` from `FlipMaMlWidgetCompat.createMamlHostView()`;
3. stores the typed view in `WidgetViewInfo`;
4. passes the resulting views to `WidgetPagerView.setViews()`;
5. forwards resume/pause/window commands to every typed host.

This means replacing a result with an arbitrary native View would break typed manager/lifecycle assumptions. The current adapter instead overlays the native renderer inside the real host.

## Pager and touch facts

- `WidgetPagerView` is a vertical cyclic pager.
- It accepts ordinary `View` children, but `isViewValid()` recognizes only `FlipMaMlHostView` children tagged with `FlipWidgetInfo` for analytics.
- Parent interception begins after the vertical delta exceeds touch slop, so ordinary image/video children that do not disallow interception should not trap page swipes.
- Three wrapper containers are reused for multi-page operation.

## MAML facts

- Presets live under `/system/media/theme/default/maml/flip_watch_maml_*.mtz`.
- An `.mtz` contains metadata, previews and a nested `widget_2x3` ZIP.
- The nested resource has `manifest.xml`, assets and localized strings.
- FlipHome extracts widget resources under its device-protected files `maml/res` directory.
- `FlipMaMlHostView` extends MAML `MamlView`, which extends `FrameLayout`; therefore a native overlay is layout-compatible while preserving the official host type.

## P0 hook adapter

| Concern | Hook/contract |
|---|---|
| Catalog | after `FlipWatchDefaultConfig.loadAllWidget()` |
| Group title | `WidgetViewModel` type map + `getNameOfType(String)` fallback |
| Runtime | after `FlipMaMlWidgetCompat.createMamlHostView(Context, FlipWidgetInfo)` |
| Data/media | guarded `content://com.lucky.mixflipouter.provider` |
| Diagnostics | provider `report_hook` / read-only `get_health` |

System APKs are never modified. Reflection is package-gated, failures are caught/logged, and official behavior remains unchanged if no custom record is returned.

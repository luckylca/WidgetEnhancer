# Quick Settings Tile bridge

## Purpose

The bridge lets a button component delegate a click to a tile that is already active in the
HyperOS control center. It does not bind to third-party `TileService` implementations and does
not imitate their lifecycle. SystemUI remains the tile host and the only process that invokes the
real `QSTile` instance.

The optional LSPosed scope is `com.android.systemui`. Without that scope the rest of the module
continues to work; the editor can discover installed third-party tile services but cannot select or
execute them.

## Device-specific path

The adapter for MIX Flip 1 HyperOS 3.0.303.0 uses:

- `MiuiQSHostAdapter.getTiles()` as the current active-tile catalogue;
- `QSTile.getTileSpec()`, `getTileLabel()`, `getState()` and `isAvailable()` for capability data;
- the public one-argument `QSTile.click(null)` entry point for execution;
- SystemUI's own `CustomTile` implementation for active third-party `TileService` tiles.

This preserves Android's SystemUI-owned `TileService` lifecycle. Discovery through
`PackageManager` is used only to show installed-but-not-active services in the picker.

## Data flow

1. The SystemUI hook publishes a bounded snapshot of active tiles every 30 seconds and after tile
   refreshes.
2. The module provider records the snapshot and treats it as stale after 90 seconds.
3. FlipHome submits a request only for an exact active and currently available tile spec.
4. SystemUI takes the request once, resolves the spec again against the live tile collection and
   calls the real tile.
5. Requests expire after 15 seconds and are removed immediately after completion.

Snapshots are capped at 256 KiB and 256 tiles. Specs, labels and implementation names are bounded,
unknown fields are discarded, duplicate specs are rejected, and `STATE_UNAVAILABLE` can never be
published as executable.

## Capability matrix

| Tile state/type | Picker | Execution | Notes |
|---|---|---|---|
| Active built-in SystemUI tile | Selectable when available | Supported through the live `QSTile` object | Example: Wi-Fi, Bluetooth, flashlight |
| Active third-party custom tile | Selectable when available | Supported through SystemUI `CustomTile` | The module never binds directly to the app's `TileService` |
| Installed service not added to control center | Visible but disabled | Blocked | The user must add it in HyperOS first |
| Active `STATE_UNAVAILABLE` tile | Visible but disabled | Blocked and rechecked at execution | Prevents stale or unsupported actions |
| Tile requiring unlock or opening a panel/activity | Selectable if SystemUI reports available | SystemUI-dependent | Authentication and panel behavior remain controlled by the OS |
| SystemUI scope absent or heartbeat stale | Discovery-only | Deterministic failure | Other Widget features remain available |
| Unknown HyperOS/SystemUI implementation | Discovery-only until adapter match is proven | Not claimed | Failure is reported instead of falling back to direct service invocation |

## Security boundary

- Only the SystemUI UID may publish snapshots, take requests or complete requests.
- Only the module UID and FlipHome may read the snapshot or submit an action.
- A requested spec must occur in the latest non-stale active snapshot, then is checked again against
  the live host before click.
- The provider does not accept component names as direct Binder targets and grants no tile-service
  permissions.

## Enable and verify

1. Install the current APK and enable the module for `com.miui.fliphome` as usual.
2. In the LSPosed module scope, additionally select `com.android.systemui` (`系统界面`).
3. Restart SystemUI or reboot the phone.
4. Open the module app. The health card should show the QS bridge as connected.
5. Open “查看快捷设置磁贴”. Active and available tiles should appear first and be selectable.
6. In a Widget editor, select a button, choose “快捷设置磁贴”, then pick a tile and save.
7. Verify the button on the real cover screen and confirm the corresponding control-center state.

Restarting SystemUI is user-gated because it temporarily redraws core system UI. Current automated
coverage verifies discovery, disabled-state UX, provider caller checks, snapshot bounds and builds;
the live click path remains pending until that scope/restart step is performed on the device.

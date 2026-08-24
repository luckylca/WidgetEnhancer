# Test plan

## P0 swipe regression

- official → custom → official, at least 50 round trips
- slow, fast, continuous and reverse swipes
- repeat after FlipHome process restart and after device reboot
- capture MotionEvent/interception diagnostics on any failure

## Media

- portrait, landscape, small and large images
- MP4/H.264 baseline sample
- image → video → official, video → image, video → video
- screen off/on, lock/unlock, process death
- verify decoder is released while the custom page is not selected

## System flow

- create, save, list in Settings, add, render, edit/refresh, remove and delete
- multiple custom Widgets and duplicate names
- corrupt/missing asset fallback without crashing Settings or FlipHome

## Actions

- volume up/down/mute
- flashlight toggle
- launch Settings, browser, NetEase and third-party apps
- lock screen
- unsupported capabilities must report a deterministic reason

## Quick Settings bridge

- without the SystemUI scope, list installed `TileService` entries as disabled and reject execution
- reject shell/non-SystemUI snapshot publication and request consumption
- after enabling the scope, verify heartbeat, active built-in tiles and active custom tiles
- block installed-but-not-active and `STATE_UNAVAILABLE` tiles
- click an active reversible tile and verify state through SystemUI, then restore its initial state
- expire unclaimed requests and invalidate the bridge after a SystemUI restart/heartbeat timeout
- verify unlock/panel-dependent tiles retain SystemUI behavior and never bypass authentication

## Stability

- restart FlipHome, Settings, SystemUI and NetEase
- force-stop the app
- reboot and LSPosed restart
- provider unavailable, invalid JSON and stale schema

## Import/export

- export with and without media
- verify exact ZIP entry set, manifest/schema versions, media size and SHA-256
- import creates a new UUID and unique name without overwriting the source
- reject duplicate/unknown entries, future versions, oversized JSON/media and hash mismatch
- verify Xiaomi secure picker can see `.mixflipwidget.zip`
- delete the imported test copy and confirm original IDs/assets remain unchanged

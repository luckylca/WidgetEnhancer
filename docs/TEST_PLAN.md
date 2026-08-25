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

## Editor

- create every entry returned by `WidgetTypeRegistry`, then save/reload without losing `typeId`
- migrate representative schema-v2 media, playback and button records to the expected type
- select and clear an image/video; verify video loop/mute values persist
- verify music uses the fixed lyric layout and exact single/double-tap action mapping
- add, bind, delete and reload one through six shortcut buttons
- verify all six fixed templates: centered 1; vertical 2/3; centered 2x2; symmetric 2+1+2; and 2x3
- verify editor, catalogue preview and cover runtime use identical centers and visual icon sizes
- verify visual icons remain smaller than their touch targets and six-button icons meet the baseline
- verify no free-canvas positioning, template picker or advanced styling controls are exposed

## Actions

- volume up/down/mute
- flashlight toggle
- Do Not Disturb toggle and exact state restoration
- auto-rotate toggle and exact state restoration
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

- verify music single tap, upper/lower double tap and upper/lower long press map to
  play/pause, previous/next and repeated volume up/down without blocking page swipes; volume
  repetition must stop immediately on release, cancellation, page movement or view detachment
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

## Diagnostics

- verify device/app/target-package versions, permissions and Hook timestamps on the diagnostics page
- verify Root is reported conservatively and LSPosed status distinguishes manager visibility from Hook evidence
- export `mixflip-diagnostics` schema v1 through the system file picker and parse it as JSON
- assert the report contains no Widget names/IDs, action targets, media titles, lyric text, file paths or tile inventory
- repeat with Provider/media/QS unavailable and confirm the report still exports deterministic false/zero states

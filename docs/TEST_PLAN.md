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

## Stability

- restart FlipHome, Settings, SystemUI and NetEase
- force-stop the app
- reboot and LSPosed restart
- provider unavailable, invalid JSON and stale schema


# Product target

Status values: `DONE`, `IN PROGRESS`, `FAILED`, `BLOCKED`, `TODO`.

## P0 — System integration

| Item | Status |
|---|---|
| Locate and preserve the existing standalone demo | DONE |
| Document device, packages and current Hook path | DONE |
| Fix custom photo page integration mismatch | DONE |
| Make video playback lifecycle-safe | DONE |
| Identify the real HyperOS outer-screen Settings page | DONE |
| Inject a dynamic `自定义` Settings group | DONE |
| Create multiple user Widgets in the app | DONE |
| Add/remove a selected custom Widget through the system flow | DONE |
| Render selected custom Widgets in FlipHome | DONE |
| Verify official ↔ custom ↔ official swiping repeatedly | BLOCKED |
| Show LSPosed activation and scope diagnostics | DONE |
| Add global safe mode and crash degradation | DONE |

## P1 — Editor and base components

| Item | Status |
|---|---|
| Versioned WidgetConfig schema and migration | DONE |
| Material 3 app shell and navigation | IN PROGRESS |
| Widget list: create/edit/delete/rename/copy/preview/enable | DONE |
| Canvas editor: drag/resize/layer/delete/copy | IN PROGRESS |
| Image, video, text, time and button components | IN PROGRESS |
| Volume, flashlight, launch-app and lock actions | IN PROGRESS |
| Launchable app picker with icons and labels | DONE |
| Provider revision and runtime cache invalidation | DONE |

## P2 — Music and lyrics

| Item | Status |
|---|---|
| PlaybackProvider abstraction | DONE |
| MediaSession metadata and transport controls | IN PROGRESS |
| LyricsProvider abstraction | DONE |
| NetEase Cloud Music lyric research and adapter | IN PROGRESS |
| Synced current/next lyric components | IN PROGRESS |
| Album art, song, artist and playback components | IN PROGRESS |

## P3 — Quick Settings bridge

| Item | Status |
|---|---|
| Research HyperOS SystemUI tile lifecycle and click path | TODO |
| Discover system and third-party tiles | TODO |
| Capability matrix and safe adapter | TODO |
| Editor tile picker and runtime action | TODO |

## P4 — Product completeness

| Item | Status |
|---|---|
| Templates as editable WidgetConfig data | DONE |
| `.mixflipwidget.zip` import/export | DONE |
| Import/export security bounds and device round trip | DONE |
| Undo/redo, alignment, grid and advanced layering | TODO |
| Diagnostics export | TODO |
| Full regression matrix and release packaging | TODO |

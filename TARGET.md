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
| Widget list: create/edit/delete/rename/preview/enable | DONE |
| Extensible Widget type registry and schema-v3 type migration | DONE |
| Type-specific editors with fixed product layouts | DONE |
| Media display: full-screen image or looping video | DONE |
| Music: blurred artwork, lyrics and required tap/long-press gestures | IN PROGRESS |
| Shortcut buttons: vertical system/app bindings | IN PROGRESS |
| Keep free-canvas and advanced style controls out of the user flow | DONE |
| Volume, flashlight, launch-app and lock actions | IN PROGRESS |
| Direct Do Not Disturb and auto-rotate actions | DONE |
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

## P3 — Optional advanced Quick Settings adapter

| Item | Status |
|---|---|
| Research HyperOS SystemUI tile lifecycle and click path | DONE |
| Discover active system tiles and installed third-party TileServices | DONE |
| Capability matrix, bounded mailbox and caller checks | DONE |
| Editor tile picker and runtime action | IN PROGRESS |
| Enable optional SystemUI scope | DONE |
| Prove a real reversible tile click | TODO |

## P4 — Product completeness

| Item | Status |
|---|---|
| Registry-driven built-in types, with room for future types | DONE |
| `.mixflipwidget.zip` import/export | DONE |
| Import/export security bounds and device round trip | DONE |
| Preserve old schema/component/package compatibility | DONE |
| Diagnostics page and versioned privacy-bounded JSON export | DONE |
| Full regression matrix and release packaging | TODO |

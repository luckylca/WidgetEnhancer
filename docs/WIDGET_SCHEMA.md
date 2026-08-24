# Widget schema

## Repository and migration

The repository is stored atomically in `files/widgets-v1.json` and currently writes schema v2. The legacy filename is deliberately retained so an OTA-style upgrade opens and migrates the existing store instead of creating a parallel database. The root contains `schemaVersion`, a monotonic `revision`, global `safeMode`, and `widgets[]`.

Media is copied into private per-Widget storage at `files/widgets/<id>/media`. External SAF paths are never used at runtime. Copying a Widget copies its media; deleting it removes only that Widget's asset directory.

The original migration imports `outer_widget` preferences and `selected_media` into a stable default Widget. The v1→v2 migration converts full-card image/video and fixed action slots into components, writes through `AtomicFile`, and preserves IDs/assets. Provider bundles include the revision so FlipHome previews and runtime hosts invalidate immediately.

## Schema v2

Every Widget contains a logical 440×720 canvas and an ordered `components[]` tree. A component contains:

- stable opaque `id` and semantic `type` (`image`, `video`, `text`, `time`, `button`);
- `frame` (`x`, `y`, `width`, `height`) in logical canvas units;
- `zIndex`, `visible`, and `locked`;
- style fields for opacity, corner radius, media fill mode, color, text size, and alignment;
- semantic `content` plus an optional action `{type, value}`.

Android View class names, filesystem paths and HyperOS implementation classes are never persisted. Runtime views are reconstructed from the semantic component records.

The old media/action fields remain temporarily as a compatibility projection for the first editor revision. `mergeLegacyEditorState()` updates those controls without deleting canvas-only text/time/style records. Once the legacy editor panel is removed, a later schema migration can drop that projection.

# Widget schema

## Schema v1

The repository is stored atomically in `files/widgets-v1.json` and contains a root `schemaVersion`, a monotonic `revision`, and a `widgets` array. Each Widget has a stable opaque ID, user-visible name, enabled state, media descriptor, runtime video flags and four action slots.

Media is copied into private per-Widget storage at `files/widgets/<id>/media`. External SAF paths are never used at runtime. Copying a Widget copies its media; deleting it removes only that Widget's asset directory.

The first v1 launch migrates the former `outer_widget` preferences and `selected_media` file into the `default` Widget. Provider bundles include the root revision so FlipHome preview URIs change when configuration changes.

## Next migration

Schema v2 will add a logical 440×720 canvas and a `components[]` tree. Component records will contain stable IDs, type, normalized geometry, z-index, visibility/lock state, style, data source and optional action. Android View class names will not be persisted.

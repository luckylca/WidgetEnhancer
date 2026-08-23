# Architecture

The target architecture separates product data from HyperOS integration.

```text
Material 3 configuration app
        |
        | ContentProvider/Binder contract + revision
        v
Versioned WidgetRepository
        |
        +-- WidgetConfig[]
        +-- asset storage
        +-- templates/import/export
        |
        v
LSPosed integration layer
        +-- FlipHome official-catalog adapter
        +-- FlipHome runtime adapter
        +-- optional music/SystemUI adapters
        |
        v
Runtime renderer
        +-- component registry
        +-- data-source registry
        +-- action registry
        +-- lifecycle/resource coordinator
```

HyperOS class names and fields belong only in versioned adapters. User JSON never stores Android View class names or reflected system fields.

## Planned modules/packages

- `model`: schema, serializers, migrations and validation
- `storage`: Widget repository, assets and revisions
- `ipc`: provider contract and caller validation
- `editor`: Material 3 list/editor/templates/diagnostics
- `runtime`: component rendering and lifecycle
- `actions`: capability-aware action executors
- `hooks`: adapter selection, FlipHome catalog/runtime hooks and self-tests
- `music`: PlaybackProvider and LyricsProvider

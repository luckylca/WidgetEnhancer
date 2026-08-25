# Architecture

The target architecture separates product data from HyperOS integration.

```text
Material 3 configuration app
        +-- type picker from WidgetTypeRegistry
        +-- small, type-specific editors
        |
        | ContentProvider/Binder contract + revision
        v
Versioned WidgetRepository
        |
        +-- WidgetConfig[]
        +-- asset storage
        +-- typeId + semantic component data
        +-- asset storage/import compatibility
        |
        v
LSPosed integration layer
        +-- FlipHome official-catalog adapter
        +-- FlipHome runtime adapter
        +-- optional music/SystemUI adapters
        |
        v
Runtime renderer
        +-- fixed layout per registered Widget type
        +-- shared normalized ButtonLayoutEngine for shortcut preview/runtime
        +-- data-source registry
        +-- action registry
        +-- lifecycle/resource coordinator
```

HyperOS class names and fields belong only in versioned adapters. User JSON never stores Android View class names or reflected system fields.

## Planned modules/packages

- `model`: schema, serializers, migrations and validation
- `storage`: Widget repository, assets and revisions
- `ipc`: provider contract and caller validation
- `editor`: Material 3 list, type-specific editors and diagnostics
- `runtime`: component rendering and lifecycle
- `actions`: capability-aware action executors
- `hooks`: adapter selection, FlipHome catalog/runtime hooks and self-tests
- `music`: PlaybackProvider and LyricsProvider

`WidgetTypeRegistry` is the extension point for product types. The three current entries
(`media`, `music`, and `shortcuts`) are built-ins, not an architectural limit. A new type adds a
registry definition plus its own editor, fixed semantic layout and runtime behavior. The normal
user flow does not expose the underlying 440x720 component canvas or general style controls.

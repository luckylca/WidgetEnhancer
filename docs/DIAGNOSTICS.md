# Diagnostics

The app's `诊断与报告` page builds a point-in-time health snapshot and can export it through
Android's system file picker. The export is JSON with this stable envelope:

```json
{
  "format": "mixflip-diagnostics",
  "schemaVersion": 1,
  "generatedAt": "2026-08-24T05:15:00Z",
  "privacy": {
    "userContentIncluded": false,
    "widgetIdentifiersIncluded": false,
    "filePathsIncluded": false,
    "tileInventoryIncluded": false
  }
}
```

## Sections

- `app`, `device`, `packages`: module, Android/HyperOS and fixed integration-target versions
- `environment`: conservative Root indicator, LSPosed manager visibility and actual Hook evidence
- `permissions`: Camera, notification listener, notification policy and write-settings access
- `hooks`: Provider availability and per-stage success/report timestamps
- `widgets`: schema/revision, enabled and component counts, media bytes and type/action aggregates
- `playback`, `lyrics`: provider/session/artwork/lyric availability without song or lyric content
- `quickSettings`: bridge age and aggregate tile counts without specs, labels or inventory

`not_detectable_by_app` for Root is deliberately not equivalent to `not rooted`; modern Root
implementations may hide all app-visible indicators. LSPosed is considered evidenced only after a
scoped process has reported at least one Hook stage.

## Privacy boundary

The report never includes Widget names or IDs, component text, action values, media title/artist,
lyric lines, filesystem paths, QS tile specs/labels or the raw Hook messages that can contain those
values. The fixed package names are part of the module's public integration contract.

The repository-side `scripts/collect-diagnostics.sh` remains a developer-only ADB tool for full
system dumps and logcat. Its output is broader than the in-app report and should be reviewed before
sharing.

## Debug device automation

Debug builds export `DiagnosticsActivity` and include `DeviceTestActivity` so ADB regression does
not depend on vendor touch injection. `DUMP_DIAGNOSTICS` writes the same schema-v1 report to the
app-private `files/device-test-result.json`; `MEDIA_PLAY_PAUSE` exercises the actual in-process
MediaController path. The file can be read with `run-as`. Neither entry point exists as an exported
test bridge in release builds.

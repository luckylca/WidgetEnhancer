# `.mixflipwidget.zip` package format

Xiaomi 的“安全访问”文件选择器会隐藏未知扩展名，因此对外文件使用
`.mixflipwidget.zip`。内部 manifest 的格式标识仍为 `mixflipwidget`，读取器不依赖
文件名判断可信度。

```text
example.mixflipwidget.zip
├── manifest.json
├── widget.json
└── assets/
    └── media          # 可选，原始图片或视频字节
```

## manifest version 1

```json
{
  "format": "mixflipwidget",
  "formatVersion": 1,
  "schemaVersion": 3,
  "appVersion": "0.6.2-p1",
  "exportedAt": 1787535910465,
  "media": {
    "included": true,
    "path": "assets/media",
    "size": 3446897,
    "sha256": "hex-encoded-sha256",
    "mimeType": "video/mp4"
  }
}
```

`widget.json` 是 [Widget schema v3](WIDGET_SCHEMA.md)，包含由注册表解析的 `typeId`。
旧包缺少 `typeId` 时会根据组件内容推断类型。导入总是创建新的 UUID，
同名项目自动增加数字后缀，不覆盖现有配置。

## Reader safety rules

- 只允许 `manifest.json`、`widget.json` 和可选 `assets/media`；
- 拒绝重复条目、未知路径、格式版本或未来 schema；
- 两个 JSON 各不超过 2 MiB，媒体不超过 512 MiB；
- 媒体必须同时通过长度和 SHA-256 校验；
- 最多保留 64 个已知组件；坐标、尺寸、透明度、圆角、字号和层级会被限制；
- 未知组件和动作不会进入运行时；缺失媒体时移除媒体组件并停用页面；
- 媒体先写临时文件，全部校验成功后才原子加入仓库，失败时清理临时目录。

## Device evidence (2026-08-24)

在 MIX Flip 1 上导出含视频的 `test`，重新从 Xiaomi 安全访问选择器导入为
`test 2`。包内媒体与原媒体 SHA-256 均为
`a1b62c1dcee66f3262b30beb784cbd835281c158f75fd055093c7d6a80675503`。
验证后删除临时副本及 Download 中的测试包，原两个 Widget ID 保持不变。

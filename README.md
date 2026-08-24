# MIX Flip 外屏自定义 Widget

面向 Xiaomi MIX Flip 1（`ruyi`）的独立 LSPosed 模块。它不修改系统 APK，不依赖或修改 MixFlipMod。

当前 `0.6.0-p1` 已经打通真实系统链路，并提供可编辑的多 Widget 平台：

- 在官方“设置 → 外屏 → 小部件”页面增加 `自定义` 分组；
- 使用官方添加/移除、排序、LiveData 和 Room 持久化；
- 在官方 `FlipMaMlHostView` 中承载自定义图片、视频与快捷按键；
- 通过受调用方校验的 ContentProvider 提供配置、媒体、预览和 Hook 健康状态；
- 必选 LSPosed 作用域为 `com.miui.fliphome`；网易云逐行歌词需要额外勾选
  `com.netease.cloudmusic`；
- 使用 Material 3 列表创建、编辑、复制、启用和删除多个用户 Widget；
- 官方列表预览与系统 440×720 规格一致，视频缩略图带播放标识；
- 运行时尺寸与圆角直接采用 FlipHome 的 103dp×174dp、20dp 系统资源。
- 支持文本、时间、图片、视频、按钮、专辑封面、歌曲/歌手、歌词和播放进度组件；
- 内置照片、视频、音乐歌词、快捷控制、时钟五个可继续编辑的模板；
- 支持带媒体资源和 SHA-256 校验的 `.mixflipwidget.zip` 导入/导出。

快捷设置 Tile 桥接、完整重启/锁屏回归和发布验收仍按 [TARGET.md](TARGET.md) 继续开发。
媒体架构与授权边界见 [docs/PLAYBACK.md](docs/PLAYBACK.md)，分享包格式见
[docs/PACKAGE_FORMAT.md](docs/PACKAGE_FORMAT.md)。

## 构建与安装

```bash
./scripts/build.sh
./scripts/install.sh
```

安装后在 LSPosed 中启用模块，至少勾选“外屏桌面 / `com.miui.fliphome`”，然后强制停止并重新打开外屏桌面（首次启用建议重启手机）。如需网易云逐行歌词，再勾选 `com.netease.cloudmusic` 并重启网易云。

打开 LSPosed 模块页：

```bash
./scripts/open-lsposed-module.sh
```

收集诊断：

```bash
./scripts/collect-diagnostics.sh
```

## 文档

- [当前状态](docs/CURRENT_STATE.md)
- [HyperOS 逆向记录](docs/HYPEROS_REVERSE_ENGINEERING.md)
- [架构](docs/ARCHITECTURE.md)
- [测试计划](docs/TEST_PLAN.md)
- [导入导出格式](docs/PACKAGE_FORMAT.md)
- [已知问题](docs/KNOWN_ISSUES.md)

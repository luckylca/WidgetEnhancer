# MIX Flip 外屏自定义 Widget

面向 Xiaomi MIX Flip 1（`ruyi`）的独立 LSPosed 模块。它不修改系统 APK，不依赖或修改 MixFlipMod。

当前 `0.7.1-p1` 已经打通真实系统链路，并提供可扩展的固定类型 Widget 平台：

- 在官方“设置 → 外屏 → 小部件”页面增加 `自定义` 分组；
- 使用官方添加/移除、排序、LiveData 和 Room 持久化；
- 在官方 `FlipMaMlHostView` 中承载媒体、音乐与快捷按钮等自定义页面；
- 通过受调用方校验的 ContentProvider 提供配置、媒体、预览和 Hook 健康状态；
- 必选 LSPosed 作用域为 `com.miui.fliphome`；网易云原生结构化歌词源可额外勾选
  `com.netease.cloudmusic`，未勾选时可通过已授权 MediaSession 的歌曲 ID 获取同步歌词；
  快捷设置磁贴需要额外勾选 `com.android.systemui`；
- 使用 Material 3 列表创建、编辑、启用和删除多个用户 Widget；
- 配置应用使用“小部件 / 关于”双 Tab；权限检查、安全模式、系统入口和调试工具集中在关于页，主 Tab 只保留小部件管理；
- 官方列表预览与系统 440×720 规格一致，视频缩略图带播放标识；
- 运行时尺寸与圆角直接采用 FlipHome 的 103dp×174dp、20dp 系统资源。
- Widget 类型由集中注册表提供，不把产品架构写死为当前三类，后续可增加新的外屏小部件类型；
- 当前媒体展示支持全屏图片或循环视频；音乐默认显示歌词，以模糊专辑封面为背景，并支持单击播放/暂停、双击上半区上一首、双击下半区下一首、持续长按上半区增加音量、持续长按下半区减小音量；
- 快捷按钮使用 1～6 套固定自适应布局，每个按钮绑定系统操作或已安装应用，不向用户暴露自由画布和复杂样式参数；
- 音量、静音、手电筒、勿扰、自动旋转、锁屏、应用和媒体控制使用直接语义动作，不依赖控制中心磁贴；
- 兼容带媒体资源和 SHA-256 校验的 `.mixflipwidget.zip` 导入包；底层仍保留版本化导出能力，但不作为当前主界面操作。
- 提供应用内诊断页和版本化 JSON 报告导出；报告只包含版本、能力状态和聚合计数，
  不包含 Widget 名称/ID、媒体标题、歌词正文、文件路径或磁贴清单。
- 支持发现控制中心活动磁贴和已安装的第三方 TileService；活动且可用的磁贴通过
  SystemUI 的真实 `QSTile` 实例执行，不直接调用第三方 TileService。

真实 QS Tile 仅作为第三方或特殊控制的高级适配器。其点击验证、首次解锁后的完整重启/锁屏回归和发布验收仍按 [TARGET.md](TARGET.md) 继续开发。
媒体架构与授权边界见 [docs/PLAYBACK.md](docs/PLAYBACK.md)，分享包格式见
[docs/PACKAGE_FORMAT.md](docs/PACKAGE_FORMAT.md)，磁贴兼容范围见
[docs/QS_TILE_BRIDGE.md](docs/QS_TILE_BRIDGE.md)，诊断报告格式见
[docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md)。

## 构建与安装

```bash
./scripts/build.sh
./scripts/install.sh
```

安装后在 LSPosed 中启用模块，至少勾选“外屏桌面 / `com.miui.fliphome`”，然后强制停止并重新打开外屏桌面（首次启用建议重启手机）。网易云同步歌词需要授予媒体通知访问；额外勾选 `com.netease.cloudmusic` 可启用原生结构化歌词源。如需快捷设置磁贴，再勾选“系统界面 / `com.android.systemui`”并重启 SystemUI 或重启手机。

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
- [快捷设置磁贴桥接](docs/QS_TILE_BRIDGE.md)
- [已知问题](docs/KNOWN_ISSUES.md)

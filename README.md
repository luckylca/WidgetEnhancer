# MIX Flip 外屏自定义 Widget

面向 Xiaomi MIX Flip 1（`ruyi`）的独立 LSPosed 模块。它不修改系统 APK，不依赖或修改 MixFlipMod。

当前 0.3.1-p1 版本已经打通真实系统链路，并开始提供多 Widget 配置能力：

- 在官方“设置 → 外屏 → 小部件”页面增加 `自定义` 分组；
- 使用官方添加/移除、排序、LiveData 和 Room 持久化；
- 在官方 `FlipMaMlHostView` 中承载自定义图片、视频与快捷按键；
- 通过受调用方校验的 ContentProvider 提供配置、媒体、预览和 Hook 健康状态；
- LSPosed 作用域只需要 `com.miui.fliphome`。
- 使用 Material 3 列表创建、编辑、复制、启用和删除多个用户 Widget；
- 官方列表预览与系统 440×720 规格一致，视频缩略图带播放标识；
- 运行时尺寸与圆角直接采用 FlipHome 的 103dp×174dp、20dp 系统资源。

可视化组件画布、完整视频生命周期、音乐/歌词、快捷设置桥接和模板系统仍按 [TARGET.md](TARGET.md) 继续开发。

## 构建与安装

```bash
./scripts/build.sh
./scripts/install.sh
```

安装后在 LSPosed 中启用模块，作用域只勾选“外屏桌面 / `com.miui.fliphome`”，然后强制停止并重新打开外屏桌面（首次启用建议重启手机）。

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
- [已知问题](docs/KNOWN_ISSUES.md)

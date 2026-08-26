# WidgetEnhancer

为 **Xiaomi MIX Flip 外屏**提供自定义小部件能力的 LSPosed 模块。

WidgetEnhancer 将自定义小部件直接接入 HyperOS 原生外屏小部件系统，可以像官方小部件一样添加、排序和使用。

## 功能

### 媒体小部件

支持在外屏显示：

- 图片
- 循环视频

支持图片 / 视频裁剪、缩放和位置调整，并按照外屏比例生成预览。

---

### 音乐小部件

在外屏显示当前播放的音乐信息：

- 歌曲名称
- 歌手
- 专辑封面
- 当前歌词
- 下一句歌词
- 播放状态

支持 MediaSession 媒体控制。

手势操作：

| 操作 | 功能 |
|---|---|
| 单击 | 播放 / 暂停 |
| 双击上半区域 | 上一首 |
| 双击下半区域 | 下一首 |
| 长按上半区域 | 增加音量 |
| 长按下半区域 | 降低音量 |

目前对网易云音乐提供额外的同步歌词适配。

---

### 快捷按钮小部件

可以创建包含 **1～6 个按钮**的快捷小部件。

按钮数量会自动匹配对应布局：

- 1 个：居中
- 2 个：纵向
- 3 个：纵向
- 4 个：2 × 2
- 5 个：2 + 1 + 2
- 6 个：2 × 3

按钮可以绑定应用或系统操作。

目前支持：

- 打开应用
- 音量控制
- 静音
- 手电筒
- 勿扰模式
- 自动旋转
- 锁屏
- 播放 / 暂停
- 上一首
- 下一首
- 部分快捷设置功能

---

## 小部件管理

模块自带配置应用，可以：

- 创建小部件
- 编辑小部件
- 删除小部件
- 重命名小部件
- 启用 / 禁用小部件
- 查看预览
- 导入小部件

创建后的小部件会出现在系统：

```text
设置
→ 外屏
→ 小部件
→ 自定义
```

中，可以和官方小部件一起添加到外屏。

---

## 实现原理

WidgetEnhancer 基于 **LSPosed / Xposed Hook** 实现。

模块主要 Hook：

```text
com.miui.fliphome
```

也就是 Xiaomi MIX Flip 的外屏桌面进程。

模块会把自定义小部件信息注入 HyperOS 原有的小部件列表，使系统将它们作为正常的外屏小部件处理。

整体结构大致为：

```text
WidgetEnhancer
      │
      ├── 小部件配置
      ├── 图片 / 视频 / 音乐等数据
      └── LSPosed Hook
               │
               ▼
        com.miui.fliphome
               │
               ├── 官方小部件列表
               ├── 小部件添加 / 删除
               ├── 小部件排序
               └── 外屏页面
                       │
                       ▼
                自定义小部件
```

因此 WidgetEnhancer 不需要自己实现一套独立的外屏桌面。

小部件的：

- 添加
- 删除
- 排序
- 页面管理
- 持久化

尽可能继续使用 HyperOS 原有逻辑。

模块主要负责自定义小部件的配置、数据和实际显示内容。

---

## 兼容性

当前主要适配：

| 项目 | 支持情况 |
|---|---|
| 设备 | Xiaomi MIX Flip |
| 代号 | `ruyi` |
| 系统 | HyperOS |
| LSPosed | 需要 |
| 模块包名 | `com.lucky.mixflipouter` |

由于 HyperOS 不同版本的内部实现可能存在差异，系统 OTA 后可能需要更新模块才能继续正常使用。

---

## LSPosed 作用域

### 必选

```text
com.miui.fliphome
```

这是模块的主要 Hook 目标。

### 可选：网易云音乐

```text
com.netease.cloudmusic
```

用于获取更完整的网易云音乐同步歌词数据。

### 可选：SystemUI

```text
com.android.systemui
```

用于部分高级快捷设置功能。

如果不使用相关功能，可以不勾选这些可选作用域。

---

## 安装

1. 安装 WidgetEnhancer APK。
2. 在 LSPosed 中启用模块。
3. 至少勾选：

```text
com.miui.fliphome
```

4. 重启手机。
5. 打开 WidgetEnhancer 创建小部件。
6. 进入：

```text
设置
→ 外屏
→ 小部件
→ 自定义
```

添加创建好的小部件。

---

## 权限

根据使用的功能，模块可能需要：

- 通知使用权：获取媒体播放状态和音乐信息
- 相机权限：控制手电筒
- 勿扰模式访问权限：控制勿扰模式
- 修改系统设置权限：控制自动旋转等功能

不使用对应功能时无需授予相关权限。

---

## 构建

```bash
git clone https://github.com/luckylca/WidgetEnhancer.git
cd WidgetEnhancer
./gradlew assembleRelease
```

APK 位于：

```text
app/build/outputs/apk/release/
```

---

## 开源协议

本项目采用 **GNU General Public License v3.0（GPL-3.0）** 开源协议。

# Mix Flip 外屏自定义 Widget 平台 — 全量开发任务

你现在接手一个 **Xiaomi MIX Flip 外屏自定义 Widget 平台**项目。

这不是一个简单的 Demo，也不是只显示图片的小插件。

最终目标是：

> 基于 Android + LSPosed 深度集成 Xiaomi MIX Flip / HyperOS 外屏系统，提供一个 Material Design 3 配置 App，让用户自己组合图片、视频、文本、歌词、快捷按钮等组件生成自定义外屏 Widget，并让这些 Widget 像官方 Widget 一样出现在系统“设置 → 外屏 → 小部件”中，可以被添加、显示、滑动和正常使用。

你的目标不是只做技术验证，而是尽可能把整个产品从：

**研究 → Hook 分析 → 架构 → App → LSPosed → Widget Runtime → 测试 → 修复 → 打包**

全部完成。

---

# 一、执行原则

## 1. 不要频繁停下来询问用户

这是一个长期自主开发任务。

除非遇到以下真正无法自行解决的问题，否则不要因为普通实现细节停下来询问：

* 缺少必要硬件；
* 无法连接手机；
* 缺少必须由用户提供的账号；
* 必须由用户手动完成系统确认；
* 需求存在两个完全相反且无法兼容的解释。

除此之外：

**自行研究、自行判断、自行实现、自行测试、自行修复。**

遇到技术问题时优先：

1. 阅读现有代码；
2. 检查设备；
3. 分析 APK / class / log；
4. 查阅 Android / LSPosed / HyperOS 相关资料；
5. 写最小实验；
6. 实机验证；
7. 再决定最终架构。

不要一遇到未知类名或 Hook 点就停下来问用户。

---

# 二、工作目录

所有工作必须放在：

```text
~/Desktop/project/
```

下面新建一个独立工程目录。

建议名称：

```text
mixflip-custom-widget
```

最终：

```text
~/Desktop/project/mixflip-custom-widget/
```

包括：

* Android 工程；
* LSPosed 模块；
* 逆向记录；
* scripts；
* test；
* screenshots；
* logs；
* APK；
* 文档；
* 临时分析结果；
* Hook 记录；
* 测试结果。

不要把文件散落到其他目录。

必要的 Gradle / Android SDK 缓存不受此限制。

---

# 三、先检查现有工作

用户当前已经做过一个初步 Demo。

现状：

1. 已经能够让自定义照片相关内容出现在 MIX Flip 外屏；
2. 但是滑动到照片页之后会卡死，无法继续滑动；
3. 视频功能似乎没有成功；
4. 当前 Demo 很可能已经验证了一部分正确 Hook 路径。

因此：

**不要直接推倒重来。**

先搜索工作区、历史工程以及当前可用代码，找到已有 Mix Flip / LSPosed / 外屏 Demo。

分析：

* 当前 Hook 了什么包；
* Hook 了哪些类；
* Hook 了哪些方法；
* 当前图片 View 如何被加入；
* 为什么图片页会阻塞滑动；
* 当前视频实现为什么失败；
* 官方 Widget 页面如何生成；
* 官方 Widget 列表数据来自哪里；
* 当前 Hook 是否可以继续复用。

把现状写入：

```text
docs/CURRENT_STATE.md
```

---

# 四、项目核心产品定义

产品不是：

```text
照片 Widget
视频 Widget
歌词 Widget
几个固定 Widget
```

而应该是：

# 自定义 Widget 平台

用户可以创建：

```text
Widget A
├── 图片
├── 时间
├── 歌词
├── 手电筒按钮
└── 网易云按钮
```

或者：

```text
Widget B
├── 视频背景
├── 当前歌曲
├── 当前歌词
├── 上一曲
├── 播放/暂停
└── 下一曲
```

每个 Widget 都是用户自己搭配出来的。

因此内部架构必须尽量采用：

```text
Widget
 ├─ Layout
 ├─ Components[]
 ├─ Actions[]
 ├─ Style
 └─ RuntimeConfig
```

而不是每增加一个 Widget 类型就硬编码一个页面。

---

# 五、系统最终体验

用户安装：

```text
配置 App
+
LSPosed Module
```

配置好作用域后。

进入：

```text
设置
→ 外屏
→ 小部件
```

应该在官方 Widget 之外出现：

```text
自定义
```

分组。

例如：

```text
官方

天气
音乐
日历
……

自定义

我的照片
动态相册
歌词播放器
快捷控制
我的 Widget 01
我的 Widget 02
```

这些项目必须来源于用户在配置 App 中创建的 Widget。

也就是说：

用户在 App 中新建：

```text
书桌控制
```

保存后。

系统：

```text
设置
→ 外屏
→ 小部件
→ 自定义
```

中应该能够看到：

```text
书桌控制
```

而不是仅仅显示几个静态预设项目。

---

# 六、必须研究真实 HyperOS 实现

不要凭猜测硬编码系统架构。

必须在实际设备/系统版本上确认：

* 外屏 Launcher / Home 包名；
* Settings 中外屏页面所属包；
* SystemUI 是否参与；
* Widget 数据 Provider；
* Widget model；
* Widget adapter；
* Fragment / Activity；
* RecyclerView / ViewPager / Compose 实现；
* Widget ID；
* 数据持久化方式；
* 添加 Widget 的调用链；
* 外屏 Widget runtime 创建流程；
* 生命周期；
* 手势分发；
* 页面滑动机制。

可能涉及的包，例如：

```text
com.miui.fliphome
Settings
SystemUI
```

但不要把任何未经实机验证的包名或类名当成事实。

通过：

* adb；
* dumpsys；
* logcat；
* jadx；
* apktool；
* LSPosed log；
* reflection；
* class enumeration；
* Hook 实验；

等手段自行确认。

记录在：

```text
docs/HYPEROS_REVERSE_ENGINEERING.md
```

至少记录：

```text
系统版本
目标 APK
包名
版本号

Widget 设置页面：
类
方法
数据结构

Widget Runtime：
类
方法

滑动：
类
机制

自定义 Hook：
Hook 点
输入
输出
原因
风险
```

---

# 七、第一优先级：修复现有“照片页卡死”

现在：

```text
官方 Widget
→ 滑
→ 自定义照片
→ 卡死
```

必须首先解决。

最终：

```text
官方 Widget A
↔
官方 Widget B
↔
自定义 Widget
↔
官方 Widget C
```

必须始终可以正常左右滑动。

重点调查：

* 自定义 View 是否消费 ACTION_DOWN；
* `onInterceptTouchEvent`；
* `dispatchTouchEvent`；
* `requestDisallowInterceptTouchEvent`；
* GestureDetector；
* Compose pointerInput；
* ViewPager / ViewPager2；
* RecyclerView；
* 官方自定义手势容器；
* NestedScrolling；
* 自定义 Media View；
* View 生命周期；
* overlay/window；
* focus；
* z-order。

设计原则：

# 外层 Widget 页面切换手势优先

普通 Widget 组件不能导致外层滑动彻底失效。

必须实机测试：

```text
官方 → 自定义 → 官方
官方 → 自定义 → 官方
```

连续滑动至少几十次。

不能偶发卡死。

---

# 八、视频支持

现有视频似乎没有成功。

重新完成。

支持：

```text
MP4
H.264
常见 Android 支持格式
```

可考虑：

```text
Media3 / ExoPlayer
```

或者更符合系统生命周期的实现。

要求：

* 自动播放；
* 循环；
* 默认静音；
* 可设置静音；
* cover / contain / crop；
* Widget 不可见后暂停；
* 息屏暂停；
* 回到页面继续；
* 不抢占外屏 Widget 滑动；
* 播放失败有 fallback；
* 不发生明显内存泄漏；
* 不长期后台解码；
* 多 Widget 视频时管理播放器资源。

需要测试：

```text
图片 → 视频 → 官方 Widget
视频 → 图片
视频 → 视频
息屏 → 亮屏
锁屏 → 解锁
反复进入/离开
```

---

# 九、配置 App

开发一个真正面向普通用户的 Android App。

设计语言：

# Material Design 3

要求：

* Material You；
* Dynamic Color；
* Dark Mode；
* Light Mode；
* Material 3 Card；
* Navigation；
* Bottom Sheet；
* Dialog；
* FAB；
* 合理动画；
* 响应式布局；
* 不做成“工程调试工具 UI”。

主导航建议：

```text
外屏
编辑器
模板
设置
```

可以根据实际 UX 调整。

---

# 十、首页

核心：

```text
我的外屏

+ 创建小部件

我的小部件

Widget 01
Widget 02
Widget 03
```

支持：

* 创建；
* 编辑；
* 删除；
* 重命名；
* 复制；
* 预览；
* 启用；
* 查看是否已经被系统添加。

---

# 十一、Widget 编辑器

这是整个产品核心。

方向：

> 简化版 KWGT / Figma。

用户创建 Widget 后可以：

```text
+ 添加组件
```

然后添加：

```text
图片
视频
文本
时间
日期
歌词
歌曲信息
按钮
App
```

组件支持：

```text
拖动
缩放
调整尺寸
删除
复制
隐藏
锁定
调整层级
```

样式：

```text
透明度
颜色
圆角
背景
字体
字号
文字对齐
裁剪
填充方式
```

最好预留：

```text
rotation
scale
zIndex
```

但第一版不需要所有高级功能完全暴露。

---

# 十二、Widget 数据模型

设计稳定的数据模型。

例如：

```text
WidgetConfig

id
name
version
createdAt
updatedAt

canvas
components[]
settings
```

Component：

```text
id
type

x
y
width
height

zIndex

style

dataSource

action
```

不要把系统 View / Android class 信息直接写进用户配置。

配置数据需要可升级。

加入：

```text
schemaVersion
```

为以后迁移做准备。

---

# 十三、媒体组件

图片：

```text
单图
多图
```

支持：

```text
裁剪
适应
填充
圆角
透明度
```

多媒体未来支持：

```text
图片
图片
视频
图片
```

但注意：

# 不要让内部媒体横向滑动直接破坏系统 Widget 横向滑动。

如果两层横向手势冲突无法稳定解决：

优先：

```text
系统 Widget 左右滑动
```

内部媒体可以第一版采用：

```text
自动轮播
点击切换
按钮切换
```

不要为了媒体内部滑动牺牲系统体验。

---

# 十四、文本组件

至少支持：

```text
普通文本
时间
日期
电量
歌曲名
歌手
歌词
```

预留 DataSource 系统，以后能够增加：

```text
天气
步数
通知
网络状态
```

---

# 十五、按钮系统

按钮必须采用可扩展：

```text
Action
```

架构。

不要每一个按钮在 UI 层硬编码。

例如：

```text
ActionType
```

第一版至少：

```text
VolumeUp
VolumeDown
Mute

LaunchApp

LockScreen

ClearRecentApps / CleanMemory

FlashlightOn
FlashlightOff
FlashlightToggle
```

如果某些系统操作 HyperOS 权限限制很大：

寻找 LSPosed / SystemUI / system_server 层的可靠实现。

如果仍然无法稳定实现：

保留能力检测并明确标记“不支持”，不要假装执行成功。

---

# 十六、打开应用

用户不应该手输 packageName。

UI 应扫描：

```text
可启动应用
```

显示：

```text
图标
名称
```

选择后保存：

```text
packageName
activity
```

必要时启动时重新解析。

支持外屏直接打开 App。

测试：

* 网易云；
* 设置；
* 浏览器；
* 至少若干第三方 App。

---

# 十七、Quick Settings Tile Bridge

这是重要能力。

目标：

> 尽可能允许用户把通知栏快捷设置磁贴映射成外屏按钮。

研究 Android：

```text
TileService
QS Tile
SystemUI
```

以及 HyperOS 自定义机制。

希望能够发现：

```text
系统 Tile
第三方 TileService
```

然后在编辑器：

```text
添加按钮
→ 快捷设置磁贴
```

显示可用 Tile。

例如：

```text
手电筒
勿扰
自动旋转
第三方 App Tile
```

但注意：

不要假设所有 TileService 都可以直接跨进程调用。

需要实际研究：

* SystemUI tile lifecycle；
* binder；
* service；
* click；
* listening；
* permission；
* custom tile。

目标是：

**尽可能兼容。**

如果无法实现通用直接调用：

可以设计 Adapter / Bridge。

并记录：

```text
支持
部分支持
不支持
```

---

# 十八、音乐系统

音乐架构不要直接绑死网易云。

设计：

```text
PlaybackProvider
LyricsProvider
```

通用 PlaybackProvider 优先基于：

```text
MediaSession
MediaController
NotificationListener
```

视实际能力决定。

可获取：

```text
歌曲
歌手
专辑
封面
播放状态
进度
duration
```

---

# 十九、网易云音乐

第一优先级播放器：

```text
网易云音乐
```

目标尽量获取：

```text
歌曲
歌手
专辑
封面
播放状态
当前进度
实时歌词
下一句歌词
```

歌词需要跟随播放时间同步。

如果官方 MediaSession 不提供歌词：

可以研究网易云：

* 内部歌词对象；
* Service；
* player；
* lyric manager；
* notification；
* broadcast；
* database/cache；

并通过可维护 Hook 获取。

不要依赖极其脆弱的 UI TextView 抓字作为唯一方案。

---

# 二十、歌词 Widget

支持至少：

```text
当前歌曲
歌手
封面
当前歌词
下一句歌词
```

控制：

```text
上一曲
播放/暂停
下一曲
```

样式至少有两种：

## 极简

```text
晴天

故事的小黄花
从出生那年就飘着
```

## 完整

```text
封面
歌名
歌手

当前歌词
下一句歌词

上一曲  播放  下一曲
```

用户仍然可以自己在编辑器组合，而不是只能选择两套模板。

---

# 二十一、其他音乐 App

网易云稳定后，再通过通用 MediaSession 兼容：

```text
QQ Music
Spotify
Apple Music
YouTube Music
酷狗
其他播放器
```

无歌词时：

至少显示：

```text
歌曲
歌手
封面
播放状态
```

不要为了第二优先级播放器影响网易云核心功能完成。

---

# 二十二、LSPosed 模块

LSPosed 是系统整合层。

目标 Hook 主要包括：

```text
Settings
FlipHome / 外屏 Home
SystemUI
必要的音乐 App
```

但是：

**以实际逆向结果为准。**

不要因为 Prompt 写了 SystemUI 就无意义地 Hook SystemUI。

原则：

> Hook 最少必要进程。

降低：

* 系统稳定性风险；
* Bootloop 风险；
* 性能消耗。

---

# 二十三、LSPosed 推荐作用域

模块应能够让用户明确知道需要启用哪些 App。

至少在配置 App 中显示类似：

```text
LSPosed

✓ 模块已激活

必要作用域

✓ 外屏桌面
✓ 系统设置

可选

○ SystemUI
用于快捷设置增强

○ 网易云音乐
用于歌词增强
```

如果模块 scope declaration / recommended scope 可以完成：

直接配置。

尽量避免让用户自己猜包名。

---

# 二十四、Hook Settings 小部件页面

这是核心需求。

找到：

```text
设置
→ 外屏
→ 小部件
```

对应真实实现。

在官方 Widget 列表中增加：

```text
自定义
```

分组。

自定义分组内容必须动态读取 App 创建的 Widget。

不是写死：

```text
Custom Widget 1
Custom Widget 2
```

---

# 二十五、Hook Widget Runtime

用户选择一个 Custom Widget 后。

外屏需要识别：

```text
custom widget id
```

并读取配置 App 的 WidgetConfig。

然后 Runtime Engine 渲染：

```text
WidgetConfig
      ↓
Component Tree
      ↓
Runtime View
```

组件：

```text
Image
Video
Text
Lyrics
Button
...
```

---

# 二十六、配置共享

LSPosed Hook 运行于不同进程。

不要依赖应用私有 SharedPreferences 直接跨 UID 读取这种脆弱实现。

设计可靠 IPC / Provider。

可以考虑：

```text
ContentProvider
Binder
AIDL
Broadcast
文件 + 权限
```

根据系统限制选择。

需要：

* 安全；
* 快速；
* 稳定；
* 不频繁 IO；
* 数据修改后及时刷新。

可以使用：

```text
version
revision
updatedAt
```

做缓存失效。

---

# 二十七、Runtime 性能

外屏环境尤其需要关注功耗。

要求：

不可见 Widget：

```text
不刷新
不解码视频
不持续动画
```

歌词：

合理刷新频率。

时间：

不要毫秒刷新。

视频：

只在真正可见时运行。

图片：

缓存。

避免：

* bitmap OOM；
* player 泄漏；
* Handler 泄漏；
* Activity Context 泄漏；
* Hook 每帧反射；
* 主线程重 IO。

---

# 二十八、生命周期

至少完整处理：

```text
外屏亮起
外屏关闭
锁屏
解锁

进入 Widget
离开 Widget

App 更新配置
系统重新加载
进程死亡
系统杀后台
横向切换
```

不能依赖“正常流程”。

需要测试 Process Death。

---

# 二十九、兼容性层

HyperOS OTA 很可能改变：

```text
class
method
field
resource id
```

所以 Hook 不应全部堆在一个类。

设计：

```text
HookAdapter
```

例如：

```text
HyperOsVersionAdapter
WidgetSettingsHook
WidgetRuntimeHook
GestureHook
```

同时加入：

```text
Hook self-test
```

App 的诊断页面显示：

```text
系统版本

Settings Hook     ✓
FlipHome Hook     ✓
Widget Provider   ✓
Runtime Hook      ✓
Lyrics Hook       ✓
```

出现 OTA 后可以马上知道哪里失效。

---

# 三十、诊断系统

配置 App 增加：

```text
诊断
```

至少显示：

```text
设备
HyperOS 版本
Android 版本

Root
LSPosed

模块版本

目标包版本

Hook 状态

歌词 Provider

Quick Settings Bridge
```

日志支持：

```text
导出诊断报告
```

不要要求用户自行去 `/data/adb/lspd/log` 找问题。

---

# 三十一、稳定性保护

这是系统模块。

必须防止：

```text
Hook exception
```

导致 Settings / FlipHome 崩溃循环。

所有 Hook：

```text
try/catch
```

做好降级。

如果 Custom Widget 加载失败：

显示 fallback。

不要直接导致系统外屏 crash。

必要时：

```text
Safe Mode
```

配置 App 可以：

```text
禁用所有自定义 Widget
```

以便恢复系统。

---

# 三十二、模板

第一版内置几个模板，用于展示能力：

```text
照片
视频
音乐歌词
快捷控制
时钟
```

这些只是：

```text
WidgetConfig template
```

而不是独立硬编码 Widget。

用户可以打开模板继续编辑。

---

# 三十三、导入 / 导出

架构中从第一版就预留。

例如：

```text
.mixflipwidget
```

实际格式可以：

```text
ZIP
├── manifest.json
├── widget.json
└── assets/
```

先完成：

```text
导出
导入
```

即可。

社区分享可以以后扩展。

---

# 三十四、资源管理

不要把照片、视频写死为绝对 URI 后就不管。

需要考虑：

* SAF；
* persistable URI permission；
* App 卸载；
* 图片删除；
* SD 卡；
* 内容失效。

可以考虑用户选择后：

```text
复制到 App Widget Asset Storage
```

使 Widget 稳定。

需要控制：

```text
视频大小
缓存
重复资源
```

---

# 三十五、测试策略

不允许：

> “编译成功，因此功能完成。”

必须至少分：

# Unit Test

测试：

```text
WidgetConfig
serialization
migration
action mapping
lyrics parser
time sync
```

# Integration Test

测试：

```text
App ↔ Provider
Hook ↔ Config
Runtime rendering
```

# 实机测试

这是最重要的。

---

# 三十六、核心实机测试矩阵

至少测试：

## 系统滑动

```text
官方
→ 自定义
→ 官方
```

反复。

包括：

```text
快速滑
慢滑
连续滑
反向滑
```

不能卡死。

---

## 图片

```text
单图
大图
小图
横图
竖图
多图
```

---

## 视频

```text
进入
播放
离开
暂停
返回
继续
息屏
亮屏
```

---

## Widget

```text
创建
保存
Settings 出现
添加
显示
修改
刷新
删除
```

---

## Button

测试：

```text
音量+
音量-
静音

手电筒

打开 App

锁屏
```

---

## 音乐

网易云：

```text
播放
暂停
切歌
拖动进度
歌词同步
锁屏
重新进入
```

---

## 稳定性

```text
FlipHome restart
Settings restart
SystemUI restart
App force-stop
网易云 force-stop
手机重启
LSPosed 重启
```

---

# 三十七、ADB 自动测试

尽可能创建：

```text
scripts/
```

例如：

```text
build.sh
install.sh
collect_logs.sh
restart_fliphome.sh
smoke_test.sh
```

能够自动完成：

```text
build
install
launch
log
```

减少人工操作。

---

# 三十八、截图 / 视频验证

每完成重要 UI：

保存：

```text
test/screenshots/
```

如果可以：

保存测试录屏。

尤其：

```text
Settings 自定义分组
Widget 外屏
滑动
视频
歌词
按钮
```

作为验收证据。

---

# 三十九、项目文档

持续维护：

```text
README.md

TARGET.md
STATUS.md

docs/
    ARCHITECTURE.md
    CURRENT_STATE.md
    HYPEROS_REVERSE_ENGINEERING.md
    HOOKS.md
    WIDGET_SCHEMA.md
    ACTION_SYSTEM.md
    MEDIA.md
    LYRICS.md
    QS_TILE_BRIDGE.md
    TEST_PLAN.md
    COMPATIBILITY.md
    KNOWN_ISSUES.md
```

---

# 四十、TARGET.md

把所有需求拆为：

```text
P0
P1
P2
P3
P4
```

并持续更新状态。

---

# 四十一、开发优先级

## P0 — 系统链路

必须优先完成：

```text
找到现有 Demo
修复图片卡死

视频正常

Settings Hook

增加“自定义”分组

App 创建 Widget

Settings 动态看到 Widget

选择 Widget

外屏正常显示

官方 Widget 正常滑动

LSPosed 状态检查
```

P0 没完成：

不要把大量时间投入高级编辑器美化。

---

# 四十二、P1 — Widget 编辑器

完成：

```text
Image
Video
Text
Time
Button

拖动
缩放
保存

多个 Widget

Material 3 UI
```

Actions：

```text
volume
flashlight
launch app
lock
```

---

# 四十三、P2 — 音乐歌词

优先：

```text
网易云
```

完成：

```text
MediaSession
歌词
进度同步
播放控制
```

然后再通用播放器。

---

# 四十四、P3 — QS Tile Bridge

完成：

```text
发现 Tile
映射
执行
兼容性测试
```

---

# 四十五、P4 — 高级能力

之后再做：

```text
导入导出
模板
组件层级
复制
Undo/Redo
对齐
网格
动画
更多数据源
```

---

# 四十六、不要犯的错误

## 不要：

为了视频而破坏系统滑动。

## 不要：

把 Custom Widget 写死成几个项目。

## 不要：

让所有逻辑都依赖 LSPosed Module 内部配置。

## 不要：

把一个 HyperOS 版本的类名散落全工程。

## 不要：

为了“兼容 Tile”声明实际上没测试。

## 不要：

用简单延时 / sleep 掩盖生命周期 Bug。

## 不要：

因为 Build Success 就宣布完成。

## 不要：

只在 Emulator 测试 LSPosed 外屏功能。

## 不要：

因为某功能复杂直接删需求。

应该：

```text
研究
尝试
验证
如果确实受到系统限制
记录证据
设计降级
```

---

# 四十七、每阶段执行方式

工作循环：

```text
读取当前状态
↓
选择最高优先级未完成事项
↓
分析
↓
实现
↓
编译
↓
静态检查
↓
单元测试
↓
安装
↓
实机测试
↓
查看 log
↓
发现问题
↓
修复
↓
再次测试
↓
记录 STATUS
↓
继续下一项
```

不要：

```text
改 10 个功能
→ 最后一起编译
```

尽量：

```text
小步闭环验证
```

---

# 四十八、遇到失败

例如：

```text
视频黑屏
```

不要直接换方案。

先收集：

```text
logcat
player state
surface
lifecycle
codec
```

定位真正原因。

例如：

```text
滑动卡死
```

分析 MotionEvent：

```text
DOWN
MOVE
UP
CANCEL
```

必要时记录：

```text
parent intercept
child consume
```

明确是哪一个 View 抢事件。

---

# 四十九、版本控制

如果项目可以使用 Git：

每完成一个稳定阶段做 Commit。

例如：

```text
fix: restore flip widget swipe gesture

feat: inject custom widget category

feat: add widget configuration provider

feat: add video runtime component

feat: add netease lyric provider
```

不要提交：

```text
build/
.gradle/
巨大测试视频
```

---

# 五十、最终交付

最终至少需要得到：

```text
APK
```

或者如果 App / Module 分开：

```text
app-release.apk
module-release.apk
```

如果采用 LSPosed Android App + Module 一体化：

一个 APK 即可。

同时提供：

```text
README
安装教程
作用域说明
支持版本
测试结果
Known Issues
```

---

# 五十一、最终验收标准

只有同时满足以下核心条件，才可以认为主项目基本完成：

### 1.

用户可以在 App 创建自定义 Widget。

### 2.

进入：

```text
设置 → 外屏 → 小部件
```

能够看到：

```text
自定义
```

分组。

### 3.

用户创建的 Widget 动态出现在其中。

### 4.

选择后可以添加到外屏。

### 5.

外屏可以正常渲染。

### 6.

滑到自定义 Widget 后仍然可以继续滑到官方 Widget。

### 7.

图片正常。

### 8.

视频正常。

### 9.

至少基础按钮：

```text
音量
手电筒
打开 App
锁屏
```

可以正常使用。

### 10.

网易云歌词能够显示，并能跟随歌曲变化。

### 11.

App UI 基本符合 Material Design 3。

### 12.

LSPosed 状态和作用域能够诊断。

### 13.

系统重启后功能仍然正常。

### 14.

模块不会因为单个 Widget 错误导致 FlipHome / Settings crash loop。

---

# 五十二、最后的产品原则

整个项目的核心不是：

> “让 MIX Flip 多几个我写的小部件。”

而是：

> “让 MIX Flip 用户可以自己创造外屏小部件。”

因此所有设计决策优先考虑：

```text
可组合
可扩展
可维护
稳定
与官方体验融合
```

而不是：

```text
针对一个 Demo 写死实现。
```

---

# 五十三、现在开始执行

现在直接开始：

1. 检查 Mac `~/Desktop/project`；
2. 创建独立项目目录；
3. 搜索和分析已有 Mix Flip Demo；
4. 检查 Android / Gradle / adb / LSPosed / 手机环境；
5. 建立 `TARGET.md`、`STATUS.md`；
6. 保存当前逆向结果；
7. 优先定位“照片页卡死”；
8. 修复并实机验证；
9. 修复视频；
10. 研究 Settings Widget 数据源；
11. 打通 Custom Widget 注入链；
12. 开发 Material 3 配置 App；
13. 开发组件式 Widget Runtime；
14. 实现基础 Action；
15. 实现网易云歌词；
16. 研究 QS Tile Bridge；
17. 持续实机测试和修复；
18. 最终构建 Release APK；
19. 完成文档；
20. 做完整最终回归测试。

除非出现必须用户介入的外部阻塞，否则：

**不要停留在“分析完成”“给出方案”“建议下一步”的状态。**

直接继续执行下一阶段。

每次更新 `STATUS.md` 时明确：

```text
DONE
IN PROGRESS
FAILED
BLOCKED
TODO
```

FAILED 不代表任务结束。

FAILED 后应：

```text
分析原因
→ 换方案
→ 再验证
```

最终目标是尽可能自行完成：

# 研究 + 开发 + Hook + 编译 + 安装 + 实机验证 + 修复 + Release

而不是只完成代码生成。

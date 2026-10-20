# Ghost Downloader · Miuix Android UI

把 Ghost Downloader 的安卓端界面从 **Material Design (Material 3)** 换成小米 **Miuix (HyperOS)** 材质，
并内置了 **HTTP/HTTPS 下载引擎、B 站/YouTube 站点解析、下载分类和浏览器扩展 WebSocket 桥接**，
让这个安卓端不再只是界面原型。

> 这个模块是 `Ghost-Downloader-3` 仓库里新增的安卓 UI 层（`android-miuix/`），与桌面端 Qt 界面完全解耦：
> 同一套 Compose 界面既能打成 Android APK，也能在 Desktop 上以手机比例预览、截图、跑引擎冒烟测试。

---

## 为什么是独立模块？

上游 v4.4.3 的安卓端 UI 并不在本仓库里：

* 主仓库只包含 Python 引擎 + 桌面端 PySide6/Qt UI；
* 安卓 APK 由 CI 检出**私有仓库** `XiaoYouChR/Ghost-Downloader-Android`（Kotlin + Jetpack Compose Material3 +
  Chaquopy）后构建，`android/` 目录已于 2026-09-08 从公开仓库移除（`refactor: remove Qt Android`）。

因此本模块用 **官方 Miuix for Compose Multiplatform** 重新实现了安卓 UI 层：
组件、配色、圆角、间距、动效全部换成 HyperOS 风格。

上游 Python 引擎无法直接嵌入：它依赖 `wreq`（Rust TLS 指纹）与 `libtorrent`，两者都需要为
Android arm64 交叉编译的 wheel（私有仓库的 `build-scripts` 才会产出）。所以这里用 **Kotlin + OkHttp**
实现了同一套下载语义（HTTP/HTTPS 多线程分块、Range 断点续传、并发上限、同名冲突、任务持久化），
UI 与引擎通过 `state/AppState.kt` 解耦——将来接入 Chaquopy/Python 引擎时只换数据来源即可。

## 目录结构

```
android-miuix/
├── app/                        # Android APK 模块（AGP 9 内置 Kotlin）
│   └── src/main/
│       ├── kotlin/.../MainActivity.kt      # 创建引擎 + 沉浸式状态栏 + setContent { App() }
│       ├── kotlin/.../platform/            # FileProvider 打开文件
│       ├── res/                            # 图标 / 应用名 / 窗口主题 / FileProvider paths
│       └── AndroidManifest.xml             # INTERNET / ACCESS_NETWORK_STATE / FileProvider
├── preview/                    # Desktop 预览模块（Compose Multiplatform + JVM）
│   └── src/
│       ├── main/kotlin/.../Main.kt         # 420x900 手机比例窗口，可直接用真实引擎下载
│       ├── main/kotlin/.../Screenshot.kt   # 无窗口渲染截图
│       ├── main/kotlin/.../SmokeDownload.kt# 无 GUI 下载冒烟测试
│       ├── main/kotlin/.../ResolveSmoke.kt # B 站 / YouTube 解析冒烟测试
│       ├── main/kotlin/.../platform/       # Desktop 打开文件
│       └── test/kotlin/.../                # 引擎 + 浏览器桥接集成测试（34 例）
├── shared-ui/src/...           # 两端共用的全部代码
│   ├── App.kt                  # 根组件 / 主题 / 引擎轮询 / 桥接生命周期
│   ├── engine/                 # 下载引擎：HttpDownloadEngine / TaskStore / Model
│   ├── resolver/               # B 站 / YouTube 解析 + 下载分类规则
│   ├── bridge/                 # 浏览器扩展 WebSocket 桥接（协议 / 模型 / 服务端）
│   ├── platform/               # PlatformFiles 抽象（打开文件）
│   ├── theme/                  # MiuixTheme（浅色 / 深色 / 跟随系统）
│   ├── model/                  # Task / TaskStatus / 演示数据
│   ├── state/                  # AppState / AppSettings / EngineBridgeHost / TokenStore
│   ├── util/                   # 尺寸 / 速度 / 百分比格式化
│   └── ui/                     # 界面：外壳 / 任务列表 / 设置 / 新建任务 / 关于
└── gradle/libs.versions.toml   # 版本目录
```

## 下载引擎

引擎按开发者文档《下载引擎》《任务系统》与上游 `features/http_pack/task.py` 的语义实现：

| 能力 | 实现 |
| --- | --- |
| Range 探测 | 两阶段：先 `bytes=1-1` 偏移探测（区分「支持 Range」与「忽略 Range」），200 且长度未知/为 1 时回退 `bytes=0-0`；206/416 的 `content-range` 取总大小 |
| 三种下载模式 | `206 + content-range` → 分片多线程；`200 + content-length` → 单线程（重试截断重下）；`200 无 content-length` → 单线程（重试从当前位置续写） |
| 分片（Subworker） | 按 `subworkerCount` 等分 byte-range，并行 Range 请求定位写入同一文件；分片完成后**接管剩余量最大分片的后半段**，尾部不空等 |
| 断点续传 | 进度文件 `{成品路径}.ghd`（与上游一致的二进制格式：24 字节小端 `start/position/end`），每秒落盘，暂停/退出时也会写 |
| 暂停 / 继续 | 取消协程与 OkHttp Call，保留 `.ghd`；继续时长任务从各分片偏移续传 |
| 运行时降级 | 分片过程中服务器返回 200（CDN 切换/策略变化）→ 截断文件、删进度、自动改单线程重下 |
| 自动加速 | 每秒采样，连续 5 个样本偏差 < 15% 时拆 4 个分片；5 秒后比较「速度增益 / 并行增益」，≥ 80% 继续、否则本次下载永久停止加速（可在设置关闭） |
| 调度 | 有序等待队列 + 最大并发；降低并发数时把超出的可暂停任务取消回等待队列 |
| 任务持久化 | `tasks.jsonl`（JSONL，200ms 防抖 + 临时文件原子重命名）；重启后等待/运行中的任务自动入队，失败/暂停保持原状 |
| 错误策略 | 临时错误（超时/断线）每 5 秒重试并在卡片上显示重试次数；永久错误 `400/401/403/404/405/410/451` 直接失败 |
| 自定义请求头 | Task 级 `headers`（B 站 CDN 需要 Referer/Origin），随任务持久化 |
| 同名冲突 | 同时检查内存任务、磁盘成品、磁盘 `.ghd`；保留两者自动加 `(1)`，或覆盖 |
| 文件打开 | Android 用 `FileProvider` + `ACTION_VIEW`；Desktop 用 `java.awt.Desktop` |
| 进度与速度 | 监管协程每秒汇总分片字节、计算速度与自动加速；UI 500ms 轮询快照，未知大小显示不确定进度条 |

下载目录：

* Android：`/storage/emulated/0/Android/data/<应用包名>/files/Download`（API 26+ 无需存储权限）；
* Desktop：`~/Downloads/GhostDownloader`（任务记录 `~/.ghostdownloader`）。

已实现的操作：新建任务（真实探测）、暂停 / 继续、重试、**重新下载**（删成品与进度后从头开始）、
删除任务（保留文件 / 同时删文件）、打开文件、打开所在文件夹、全部开始 / 全部暂停、
按名称搜索、全部/未完成/已完成筛选。

## 站点解析（哔哩哔哩 / YouTube / eD2k / BT）

`resolver/MediaResolver.kt` 对应上游 `features/bili_pack`、`features/yt_dlp_pack`，按能力分三档：

| 来源 | 状态 | 说明 |
| --- | --- | --- |
| 哔哩哔哩 | ✅ 可用 | 支持 `BV` / `av` / `b23.tv`；`view` 取标题与分 P，`playurl(durl)` 取单文件 MP4（实测 720P 51.9 MB），下载自动带 `Referer/Origin` |
| YouTube | ⚠️ 尽力解析 | 走 `youtubei/player` 的 ANDROID_VR/ANDROID/IOS 客户端，只选**渐进式单文件**格式；只有分轨的清晰度需要 yt-dlp + FFmpeg 混流（未内置），会给出明确提示 |
| eD2k | ❌ 不可用 | 需要 Kelpie（Go 交叉编译 .so，上游私有构建），当前安卓运行时未内置 |
| 磁力 / BT | ❌ 不可用 | 需要 libtorrent 的 Android arm64 wheel（上游私有构建），当前安卓运行时未内置 |

B 站 DASH 分轨与多段 FLV 不在支持范围；YouTube 在本机网络不可达时也会给出网络错误提示。

## 下载分类

`resolver/CategoryRules.kt` 内置 6 类：视频 / 音频 / 软件 / 压缩包 / 图片 / 文档。设置页开启后，
下载目录自动变为 `{保存路径}/{分类}/`；新建任务抽屉会显示识别出的分类。分类同样作用于浏览器扩展
发来的下载（`EngineBridgeHost` 复用同一规则）。

## 浏览器扩展桥接

`bridge/` 实现了与公开浏览器扩展兼容的 WebSocket 服务端（协议对齐 `browser_service.py` 与扩展端
`desktop-bridge.ts`）：

- 默认监听 `0.0.0.0:14370`，扩展地址填 `ws://<手机IP>:14370`，或电脑上执行
  `adb reverse tcp:14370 tcp:14370` 后使用默认 `ws://127.0.0.1:14370`；
- 首次连接发 `pair_request`，App 弹出 Miuix 配对确认框，允许后把**持久化**的配对令牌下发给扩展；
- 已实现 `hello/hello_ack`、`subscribe_tasks`、`task_snapshot`（去重推送）、`create_task`
  （浏览器下载接管 / 资源面板直链）、`task_action`（暂停/继续、取消、删除、重新下载、打开文件）；
- 设置页可查看连接状态、修改端口、查看/重新生成配对令牌；令牌保存在数据目录，重启后无需重新配对；
- 安卓端暂不支持 `resource_merge`（音视频合流）与 `page_media`（yt-dlp 页面解析），会向扩展返回 rejected。

## Material 3 → Miuix 对照表

| 原 Material 3（当前安卓端） | 本模块的 Miuix 组件 |
| --- | --- |
| `MaterialTheme` / `MaterialTheme.colorScheme` | `MiuixTheme(controller = ThemeController(...))` / `MiuixTheme.colorScheme` |
| Material You 动态取色 | 按要求**已移除**，仅保留浅色 / 深色 / 跟随系统（Miuix 自带 HyperOS 色板） |
| `TopAppBar` / `CenterAlignedTopAppBar` | 自绘左对齐大标题顶栏（标题 30sp 加粗 + 实时速度副标题 + actions） |
| 右上角溢出菜单 | `OverlayIconDropdownMenu`（弹出式下拉菜单，Android 端等价于 `WindowIconDropdownMenu`） |
| `NavigationBar` + `NavigationBarItem` | `NavigationBar` + `NavigationBarItem`（HyperOS 胶囊/图标文字模式） |
| `FloatingActionButton` | `FloatingActionButton` |
| `Card` / `ElevatedCard` | `Card`（`surfaceContainer`、16dp 圆角、`insideMargin`） |
| `Button` / `FilledTonalButton` | `Button` + `ButtonDefaults.buttonColors()` / `buttonColorsPrimary()` |
| `ListItem` / `Switch` / `Slider` 行 | `BasicComponent` / `ArrowPreference` / `SwitchPreference` / `SliderPreference` |
| `AlertDialog` | `OverlayDialog` |
| `ModalBottomSheet` | `OverlayBottomSheet` |
| `FilterChip` / `SegmentedButton` | `TabRow` |
| `LinearProgressIndicator` | `LinearProgressIndicator` |
| `SnackbarHost` / `SnackbarHostState` | 同名 Miuix 组件 |
| `Icons.*` / Material Icons | `MiuixIcons` + `top.yukonga.miuix.kmp.icon.extended.*`（156 个 HyperOS 图标） |
| 字体：Roboto / Material Typography | `MiuixTheme.textStyles`（HyperOS 字号体系） |

## 已实现界面

1. **任务列表**：左对齐大标题（30sp）与实时速度；未完成 / 已完成分组、筛选 TabRow、搜索；
   下载卡片（进度条、速度、连接数、模式与分类、重试提示、错误信息）、暂停/继续/重试按钮；
   卡片「更多」弹层（打开文件 / 打开文件夹 / 重新下载 / 删除任务 / 删除并清理文件）；
   右下角 FAB、底部 `NavigationBar`、Snackbar；已完成卡片点击即打开文件。
2. **设置**：主题模式（跟随系统 / 浅色 / 深色，已移除 Monet）、语言、综合下载设置
   （下载路径、最大任务数、预分配线程数、自动提速、最小再分配大小、保留修改时间、SSL 校验、
   同名文件、系统 DNS）、下载分类（6 类自动归档）、浏览器扩展（启停 / 端口 / 配对令牌 / 连接状态）、
   应用（自动更新 / 关于）。「最大任务数」「预分配线程数」「同名文件」「自动提速」均作用于引擎。
3. **新建任务抽屉（加高版）**：链接 → 保存路径 / 分片线程数 / 分类等选项 → 解析 →
   **展示解析出的文件卡片**（名称、大小、下载模式、来源说明）→ 确认下载；解析支持直链、B 站、YouTube。
   「取消」「解析链接」改为固定小宽度右对齐，按钮间距加大。
4. **关于**：版本信息、开源地址、界面库信息。
5. **主题**：浅色 / 深色 / 跟随系统，状态栏与导航栏沉浸式透明。

## 截图

> 由 `gradlew :preview:screenshot` 无窗口渲染（420x900dp @2x），不受本机 DPI / 窗口装饰影响。

| 任务列表（深色） | 任务列表（浅色） | 已完成 / 失败 |
| --- | --- | --- |
| ![tasks-dark](screenshots/tasks-dark.png) | ![tasks-light](screenshots/tasks-light.png) | ![tasks-completed](screenshots/tasks-completed-dark.png) |

| 设置（深色） | 设置（浅色） | 新建任务 | 关于 |
| --- | --- | --- | --- |
| ![settings-dark](screenshots/settings-dark.png) | ![settings-light](screenshots/settings-light.png) | ![add-task](screenshots/add-task-dark.png) | ![about](screenshots/about-dark.png) |

## 构建

环境要求：**JDK 21**、Android SDK（`platforms;android-37.0`、`build-tools;37.0.0`）。本机已安装在：

```
E:\dev\jdk21
E:\dev\android-sdk
```

```powershell
$env:JAVA_HOME = "E:\dev\jdk21"
$env:ANDROID_HOME = "E:\dev\android-sdk"
$env:ANDROID_SDK_ROOT = "E:\dev\android-sdk"

cd android-miuix

# 1) 打安卓 APK
.\gradlew.bat :app:assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk

# 2) 桌面预览（420 x 900，效果与安卓一致，可直接用内置引擎下载）
.\gradlew.bat :preview:run

# 3) 无窗口渲染 README 截图（输出到 android-miuix/screenshots/）
.\gradlew.bat :preview:screenshot

# 4) 引擎集成测试（本地 Range 服务器：分块 / 暂停继续 / 持久化 / 非法协议）
.\gradlew.bat :preview:test

# 5) 真实网络下载冒烟测试（打印探测、进度、完成路径）
.\gradlew.bat :preview:engineSmoke
.\gradlew.bat :preview:engineSmoke "-Purl=https://speed.cloudflare.com/__down?bytes=5000000"

# 6) B 站 / YouTube 解析冒烟测试
.\gradlew.bat :preview:resolverSmoke "-Purl=https://www.bilibili.com/video/BV1GJ411x7h7"
```

Gradle 发行版走腾讯镜像（见 `gradle/wrapper/gradle-wrapper.properties`），依赖仓库优先走阿里云镜像，
Miuix 0.9.4 从 Maven Central 拉取。

## 版本

| 组件 | 版本 |
| --- | --- |
| Miuix (`top.yukonga.miuix.kmp:*`) | 0.9.4 |
| Compose Multiplatform | 1.12.2 |
| Kotlin | 2.4.21 |
| OkHttp（下载引擎） | 5.5.0 |
| Java-WebSocket（浏览器桥接） | 1.6.0 |
| Gson（桥接 / 站点解析 JSON） | 2.14.0 |
| AGP | 9.4.1 |
| Gradle | 9.8.2 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |

## 已知限制

* 下载协议：直链走内置 HTTP 引擎；B 站走轻量解析（durl 单文件）；YouTube 只支持渐进式单文件。
  **eD2k / 磁力 / BT / FTP / m3u8 仍需要上游 Python 运行时**（Kelpie / libtorrent / wreq 的
  Android 交叉编译产物只在私有仓库构建脚本里），当前会给出明确不支持提示；
* 浏览器桥接只在 App 进程存活时有效，切后台被系统回收会断开；扩展需与手机同网段或使用 `adb reverse`；
* 不支持 Range 的服务器（单流模式）不可断点续传：暂停后再次开始会从头下载（与上游 `canPause` 一致）；
* 下载运行在 App 进程内，进程被系统杀死时会暂停（任务记录已持久化，重开 App 后自动恢复等待/运行中的任务）；
  尚未接前台 Service / 通知栏进度；
* 未实现全局限速 / 代理 / 指纹 Client（对应上游 SpeedMeter、wreq），这些属于后续可选项；
* Android 端「打开所在文件夹」返回 false（系统没有通用目录 Intent），UI 会提示下载路径；
* 演示截图仍使用 `AppState(demo = true)` 的模拟任务，真实 App 使用内置引擎；
* 上游真实安卓 UI 源码为私有仓库，本模块是公开仓库中可维护的替代实现。

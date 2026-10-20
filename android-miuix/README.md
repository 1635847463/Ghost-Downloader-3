# Ghost Downloader · Miuix Android UI

把 Ghost Downloader 的安卓端界面从 **Material Design (Material 3)** 换成小米 **Miuix (HyperOS)** 材质的独立 UI 模块。

> 这个模块是 `Ghost-Downloader-3` 仓库里新增的安卓 UI 层（`android-miuix/`），与桌面端 Qt 界面完全解耦：
> 同一套 Compose 界面既能打成 Android APK，也能在 Desktop 上以手机比例预览、截图。

---

## 为什么是独立模块？

上游 v4.4.3 的安卓端 UI 并不在本仓库里：

* 主仓库只包含 Python 引擎 + 桌面端 PySide6/Qt UI；
* 安卓 APK 由 CI 检出**私有仓库** `XiaoYouChR/Ghost-Downloader-Android`（Kotlin + Jetpack Compose Material3 +
  Chaquopy）后构建，`android/` 目录已于 2026-09-08 从公开仓库移除（`refactor: remove Qt Android`）。

因此本模块用 **官方 Miuix for Compose Multiplatform** 重新实现了安卓 UI 层：
组件、配色、圆角、间距、动效全部换成 HyperOS 风格，业务数据先用 `AppState` 里的模拟任务驱动，
后续接入 Python 引擎（Chaquopy）时只需替换 `state/AppState.kt` 的数据来源，UI 不需要再动。

## 目录结构

```
android-miuix/
├── app/                        # Android APK 模块（AGP 9 内置 Kotlin）
│   └── src/main/
│       ├── kotlin/.../MainActivity.kt   # 沉浸式状态栏 + setContent { App() }
│       ├── res/                          # 图标 / 应用名 / 窗口主题
│       └── AndroidManifest.xml
├── preview/                    # Desktop 预览模块（Compose Multiplatform）
│   └── src/main/kotlin/.../Main.kt       # 420x900 手机比例窗口
├── shared-ui/src/...           # 两端共用的全部 UI 代码
│   ├── App.kt                  # 根组件 / 主题 / 模拟进度时钟
│   ├── theme/                  # MiuixTheme + Monet 动态取色
│   ├── model/                  # Task / TaskStatus / 演示数据
│   ├── state/                  # AppState（下载进度模拟）/ AppSettings
│   ├── util/                   # 尺寸 / 速度 / 百分比格式化
│   └── ui/                     # 五个界面：外壳 / 任务列表 / 设置 / 新建任务 / 关于
└── gradle/libs.versions.toml   # 版本目录
```

## Material 3 → Miuix 对照表

| 原 Material 3（当前安卓端） | 本模块的 Miuix 组件 |
| --- | --- |
| `MaterialTheme` / `MaterialTheme.colorScheme` | `MiuixTheme(controller = ThemeController(...))` / `MiuixTheme.colorScheme` |
| Material You 动态取色 | `ColorSchemeMode.MonetSystem / MonetLight / MonetDark` |
| `TopAppBar` / `CenterAlignedTopAppBar` | `SmallTopAppBar`（标题 + 副标题 + actions + bottomContent） |
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

1. **任务列表**：未完成 / 已完成分组、全部/未完成/已完成 `TabRow` 过滤、搜索、实时速度副标题、
   下载卡片（进度条、速度、上传速度、连接数、分享率、文件数、错误提示）、暂停/继续胶囊按钮、
   长按式「更多」底部弹层、右下角 FAB、底部 `NavigationBar`、Snackbar 提示。
2. **设置**：外观与个性化（主题模式 / 动态取色 / 语言）、综合下载设置（下载路径、最大任务数、
   预分配线程数、自动提速、最小再分配大小、保留修改时间、SSL 校验、同名文件、系统 DNS）、
   下载分类、浏览器扩展（端口 / 配对令牌）、应用（自动更新 / 关于）。
3. **新建任务**：链接输入 → 解析 → 文件名 / 保存路径 / 分类 → 开始下载（对应桌面端 Task Draft）。
4. **关于**：版本信息、开源地址、界面库信息。
5. **主题**：浅色 / 深色 / 跟随系统 + Monet 动态取色，状态栏与导航栏沉浸式透明。

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

# 2) 桌面预览（420 x 900，效果与安卓一致）
.\gradlew.bat :preview:run

# 3) 无窗口渲染 README 截图（输出到 android-miuix/screenshots/）
.\gradlew.bat :preview:screenshot
```

Gradle 发行版走腾讯镜像（见 `gradle/wrapper/gradle-wrapper.properties`），依赖仓库优先走阿里云镜像，
Miuix 0.9.4 从 Maven Central 拉取。

## 版本

| 组件 | 版本 |
| --- | --- |
| Miuix (`top.yukonga.miuix.kmp:*`) | 0.9.4 |
| Compose Multiplatform | 1.12.2 |
| Kotlin | 2.4.21 |
| AGP | 9.4.1 |
| Gradle | 9.8.2 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |

## 已知限制

* 当前用模拟任务数据演示 UI，尚未接入真实下载引擎；
* 接入真实引擎需要在 `app` 模块加 Chaquopy + 主仓库 `app/`、`features/` 的 Python 源码，
  并把 `state/AppState.kt` 换成 Engine 的桥接实现（UI 层不需要改动）；
* 上游真实安卓 UI 源码为私有仓库，本模块是公开仓库中可维护的替代实现。

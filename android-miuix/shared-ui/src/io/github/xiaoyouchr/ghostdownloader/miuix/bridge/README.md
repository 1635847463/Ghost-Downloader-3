# 浏览器扩展 WebSocket 桥接（Android / Miuix）

让公开的 Ghost Downloader Chrome/Firefox 扩展连接 Android 端：配对 → 创建下载任务 → 订阅任务快照 →
暂停 / 删除 / 重新下载。协议与桌面端 `app/services/browser_service.py`、扩展端
`browser_extension/app/src/background/desktop-bridge.ts` 完全对齐，**不新增、不改名字段**。

- 包：`io.github.xiaoyouchr.ghostdownloader.miuix.bridge`
- 默认监听：`0.0.0.0:14370`（扩展默认地址 `ws://127.0.0.1:14370`，见 `browser_extension/app/src/shared/constants.ts`）
- 协议版本：`2`（`BridgeProtocol.VERSION`）
- 依赖：`org.java-websocket:Java-WebSocket:1.6.0`、`com.google.code.gson:gson:2.14.0`（已在 `libs.versions.toml` 与 app/preview 模块配置）

## 文件

| 文件 | 内容 |
| --- | --- |
| `BridgeProtocol.kt` | 协议常量、消息类型、错误码、任务操作 / 来源 / 创建状态枚举 |
| `BridgeModels.kt` | `PairRequest`、`BridgeTaskSummary`、`BridgeResource`、`BridgeCreateTaskRequest`、`BridgeCreateTaskResult` |
| `BrowserBridgeHost.kt` | App 侧适配接口（由 Lead 用 `HttpDownloadEngine` 实现） |
| `BrowserBridge.kt` | Java-WebSocket 服务端：握手、配对、订阅、快照推送、消息路由、线程安全 |
| `README.md` | 本文档 |
| 测试 `preview/src/test/.../bridge/BrowserBridgeTest.kt` | 随机端口 + 假 Host 的端到端测试（不依赖外网） |

## 协议对照

### 消息一览

| 方向 | `type` | 字段 | 说明 |
| --- | --- | --- | --- |
| 扩展 → 服务端 | `hello` | `protocolVersion`、`token`、`extensionVersion`、`clientKind`、`installType`、`requestId?` | 握手认证；`protocolVersion` 必须为 `2` |
| 服务端 → 扩展 | `hello_ack` | `protocolVersion`、`appVersion`、`capabilities{taskSnapshots,taskActions}` | 认证成功 |
| 扩展 → 服务端 | `pair_request` | `requestId`、`protocolVersion`、`extensionVersion`、`clientKind` | 无 token 时发起配对 |
| 服务端 → 扩展 | `pair_result` | `requestId`、`ok`、`token?`、`message?` | `ok=true` 时带 token |
| 扩展 → 服务端 | `subscribe_tasks` | — | 订阅任务快照（需已认证） |
| 服务端 → 扩展 | `task_snapshot` | `tasks: BridgeTaskSummary[]` | 只发给「已认证 + 已订阅」连接，按 `createdAt` 倒序、内容去重 |
| 扩展 → 服务端 | `create_task` | `requestId`、`source`、`title`、`draft?`、`payload` | 创建下载任务 |
| 服务端 → 扩展 | `create_task_result` | `requestId`、`status`、`taskId?`、`message?` | `status`：`created` / `drafted` / `rejected` |
| 扩展 → 服务端 | `task_action` | `requestId`、`action`、`taskId` | 任务操作 |
| 服务端 → 扩展 | `task_action_result` | `requestId`、`ok`、`message?` | |
| 服务端 → 扩展 | `error` | `message`、`code`、`requestId?` | `code`：`bad_request` / `protocol_mismatch` / `unauthorized` |
| 服务端 → 扩展 | `reload` | — | 扩展自更新用；Android 端暂不发送 |

### 握手与配对流程

```
扩展                                   Android 桥接
  │  (无 token) pair_request             │
  ├─────────────────────────────────────►│  pendingPairRequest 暴露给 UI
  │                                      │  用户在设置页点「允许」→ approvePair(requestId)
  │◄─────────────────────────────────────┤  pair_result{ok:true, token}
  │  保存 token，主动重连                 │
  ├── hello{protocolVersion:2, token} ───►│  校验版本 + token
  │◄─────────────────────────────────────┤  hello_ack{appVersion, capabilities}
  ├── subscribe_tasks ───────────────────►│
  │◄─────────────────────────────────────┤  task_snapshot{tasks:[...]}
```

- 未认证连接只允许 `hello` / `pair_request`；其它消息回 `error{code:unauthorized}` 并断开（同桌面端）。
- 同一时刻只允许一个待确认配对请求；第二个连接发 `pair_request` 会收到 `pair_result{ok:false, message:"已有配对请求待处理"}`。
- 配对连接断开时自动清空待确认请求（Host 收到 `onPairRequestChanged(null)`）。
- `regenerateToken()` 会断开所有已认证连接，迫使扩展用新 token 重连（同桌面端）。

### `create_task.payload` 按 `source` 的字段

| `source` | payload 字段 | 说明 |
| --- | --- | --- |
| `download` / `resource` | `url`、`headers`（`Record<string,string>`）、`filename`、`size`、`supportsRange`、`decryptionKeys?` | 浏览器接管下载 / 资源面板发送；`size` 可能为 `0`、`supportsRange` 可能未知 |
| `resource_merge` | `resources: [{url, filename, mime, size, headers, pageTitle, supportsRange}]` | 音视频分离资源合并（桌面端在线合流） |
| `page_media` | `url`、`pageUrl`、`pageTitle`、`headers` | 页面媒体外部下载（桌面端走 yt-dlp） |

桥接层把 payload 解析成 `BridgeCreateTaskRequest`（`source` / `title` / `draft` / `url` / `headers` /
`fileName` / `fileSize` / `canUseRange` / `pageUrl` / `pageTitle` / `resources` / `decryptionKeys` /
`rawPayloadJson`）交给 Host；缺 `url`（或 merge 缺资源）直接回 `rejected`，未知 `source` 回 `error{bad_request}`。

### `task_snapshot.tasks[]` 字段

与桌面端 `toTaskSummary()` 对齐（另含扩展端本地字段 `shouldOpenWhenDone`，服务端固定 `false`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `taskId` | string | 引擎任务 id 的十进制字符串 |
| `name` | string | 文件名 |
| `status` | string | `waiting` / `running` / `paused` / `completed` / `failed`（必须小写，扩展 UI 依赖） |
| `progress` | number | 0~100 百分比（保留两位小数） |
| `receivedBytes` / `fileSize` / `speed` | number | 字节 / 字节每秒 |
| `createdAt` | number | 毫秒时间戳 |
| `canPause` | bool | 是否允许暂停 |
| `canOpenFile` / `canOpenFolder` | bool | Android 端建议按成品是否存在计算 |
| `fileExt` | string | 扩展名（小写、不带点） |
| `packName` | string | 桌面端的包名 / 合集名，Android 端可留空 |

### `task_action.action`

| action | 桌面端语义 | Android 建议映射 |
| --- | --- | --- |
| `toggle_pause` | 运行中→暂停；等待/暂停/失败→开始；已完成→失败 | `status == RUNNING → engine.pause`；`WAITING/PAUSED/FAILED → engine.start`；`COMPLETED → false` |
| `cancel` | 删除任务并删除文件 | `engine.remove(id, deleteFiles = true)` |
| `remove` | 删除任务、保留文件 | `engine.remove(id, deleteFiles = false)` |
| `redownload` | 清空进度/成品后重下 | `engine.redownload(id)` |
| `open_file` / `open_folder` | 打开文件 / 所在目录 | Android 可用 Intent + FileProvider；暂不支持则返回 `false` |

> `open_when_done` 由扩展端本地处理（见 `background.ts`），不会发到服务端，`TaskAction` 枚举中也没有它。

## 公共 API

```kotlin
class BrowserBridge(
    host: BrowserBridgeHost,
    port: Int = 14370,                 // 0 = 随机端口（测试用）
    bindAddress: String = "0.0.0.0",   // 默认监听全部网卡
    snapshotIntervalMs: Long = 1000    // 0 = 关闭定时快照，仅手动 broadcastSnapshot()
) : AutoCloseable {
    val actualPort: Int                // 实际绑定端口（随机端口时以它为准）
    val isRunning: Boolean
    val pendingPairRequest: PairRequest?

    fun start()                        // 阻塞到端口绑定完成，失败抛 IllegalStateException
    override fun close()               // 幂等，停止监听、断开连接、释放线程
    fun approvePair(requestId: String): Boolean
    fun rejectPair(requestId: String): Boolean
    fun regenerateToken(): String      // 重置 token 并断开已认证连接
    fun broadcastSnapshot()            // 线程安全，内容不变不重复推送
}

interface BrowserBridgeHost {
    fun pairToken(): String
    fun regenerateToken(): String
    fun tasks(): List<BridgeTaskSummary>
    fun createTask(request: BridgeCreateTaskRequest): BridgeCreateTaskResult
    fun action(taskId: String, action: TaskAction): Boolean
    fun onPairRequestChanged(request: PairRequest?)
    fun appVersion(): String = "GhostDownloader Miuix"   // 可覆盖
    fun connectionChanged(connected: Boolean) {}          // 可覆盖
    fun log(message: String) {}                           // 可覆盖
}
```

线程模型：`tasks()` / `createTask()` / `action()` 在桥接内部工作线程调用（Host 实现需线程安全；
`createTask` 允许阻塞，例如探测链接）；`onPairRequestChanged()` / `connectionChanged()` / `log()`
可能在 WS 线程回调，应快速返回。所有发送走 Java-WebSocket 线程安全队列，任意线程调用
`broadcastSnapshot()` 都安全。

## Lead 接入步骤（AppState / MainActivity）

1. **实现 Host（约 80 行）**：把 `HttpDownloadEngine` 包一层。

   ```kotlin
   private fun TaskSnapshot.toBridgeSummary() = BridgeTaskSummary(
       taskId = id.toString(),
       name = name,
       status = when (status) {
           EngineStatus.WAITING -> "waiting"
           EngineStatus.RUNNING -> "running"
           EngineStatus.PAUSED -> "paused"
           EngineStatus.COMPLETED -> "completed"
           EngineStatus.FAILED -> "failed"
       },
       progress = if (totalBytes > 0) {
           round(downloadedBytes * 10000.0 / totalBytes) / 100.0
       } else 0.0,
       receivedBytes = downloadedBytes,
       fileSize = totalBytes,
       speed = speedBytes,
       createdAt = createdAt,
       canPause = status == EngineStatus.RUNNING,
       canOpenFile = status == EngineStatus.COMPLETED,
       canOpenFolder = true,
       fileExt = name.substringAfterLast('.', "").lowercase(),
       packName = ""
   )

   override fun tasks(): List<BridgeTaskSummary> = engine.snapshots().map { it.toBridgeSummary() }

   override fun createTask(request: BridgeCreateTaskRequest): BridgeCreateTaskResult {
       if (request.source != TaskSource.DOWNLOAD && request.source != TaskSource.RESOURCE) {
           return BridgeCreateTaskResult.rejected("安卓端暂不支持 ${request.source.wire} 任务")
       }
       return runCatching {
           val snapshot = engine.addTask(
               url = request.url,
               folder = engine.defaultFolder(),
               preferredName = request.fileName,
               totalBytes = request.fileSize,
               canUseRange = request.canUseRange,
               headers = request.headers,        // 引擎已支持自定义请求头
               subworkerCount = settings.preBlockNum
           )
           BridgeCreateTaskResult.created(snapshot.id.toString())
       }.getOrElse { BridgeCreateTaskResult.rejected(it.message ?: "创建任务失败") }
   }

   override fun action(taskId: String, action: TaskAction): Boolean {
       val id = taskId.toLongOrNull() ?: return false
       val snapshot = engine.snapshots().firstOrNull { it.id == id } ?: return false
       return when (action) {
           TaskAction.TOGGLE_PAUSE -> when (snapshot.status) {
               EngineStatus.RUNNING -> { engine.pause(id); true }
               EngineStatus.WAITING, EngineStatus.PAUSED, EngineStatus.FAILED -> { engine.start(id); true }
               EngineStatus.COMPLETED -> false
           }
           TaskAction.CANCEL -> { engine.remove(id, deleteFiles = true); true }
           TaskAction.REMOVE -> { engine.remove(id, deleteFiles = false); true }
           TaskAction.REDOWNLOAD -> { engine.redownload(id); true }
           TaskAction.OPEN_FILE, TaskAction.OPEN_FOLDER -> false // 需要 Intent + FileProvider
       }
   }
   ```

   `pairToken()` / `regenerateToken()` 接到持久化的 token（见下方风险）；`onPairRequestChanged`
   把请求写进 `AppState` 的 Compose 状态供设置页弹窗；`appVersion` 用 `settings.appVersion`。

2. **启动 / 停止桥接**：在 `MainActivity` 持有 `BrowserBridge`（或由 `AppState` 管理），
   `engine` 创建后 `bridge.start()`，`onDestroy()` 里先 `bridge.close()` 再 `engine.close()`。
   设置页 `browserExtensionEnabled` / `browserExtensionPort` 变化时重启桥接（`close()` 后新建实例）。

   ```kotlin
   bridge = BrowserBridge(host, port = settings.browserExtensionPort)
   runCatching { bridge.start() }
       .onFailure { error ->
           // 端口占用等错误：写入 AppState 的新状态字段，在设置页「浏览器扩展」分组展示
           bridgeError = error.message
       }
   ```

3. **快照推送**：`AppState.onTick()` 已经每 500ms 调 `refreshFromEngine()`，在末尾加一行
   `bridge?.broadcastSnapshot()` 最省事（桥接层按连接做内容去重，不会放大流量）；也可以依赖
   默认 1s 定时推送。任务操作（confirm / toggle / remove / redownload）后也建议主动推一次。

4. **配对 UI**：设置页展示 `bridge.pendingPairRequest`（扩展名 + 版本 + 地址）与
   「允许 / 拒绝」按钮，分别调用 `bridge.approvePair(requestId)` / `bridge.rejectPair(requestId)`；
   展示 `settings.pairToken` 与「重新生成」按钮（调用 `bridge.regenerateToken()`）；
   扩展连接状态用 `host.connectionChanged` 回写到 `AppState`（`bridge.isRunning` 可显示服务状态）。

5. **地址提示**：桌面浏览器与 Android 手机不在同一 localhost。扩展设置里把服务器地址改成
   `ws://<手机局域网 IP>:<端口>`；调试也可以用 `adb reverse tcp:14370 tcp:14370` 后直接使用
   `ws://127.0.0.1:14370`。Android 清单已含 `INTERNET` 权限，本地 `ws://` 不受明文流量限制影响
   （`usesCleartextTraffic="true"` 已开启）。

## 已知限制 / 风险

- **token 持久化**：扩展把 token 存在 `chrome.storage.local`，Android 端目前 `AppSettings.pairToken`
  是内存 Compose 状态，进程重启后会回到默认值，导致扩展每次都要重新配对。接入时必须把 token
  落到 `SharedPreferences` / DataStore（或在 `AppState.init` 中读取），否则配对体验不可用。
- **后台存活**：桥接只在 App 进程存活时可用。切后台后系统可能回收进程，扩展会显示断开并重连。
  要长期后台可用需要 `ForegroundService`（属于 Lead 的生命周期改造范围）。
- **局域网暴露**：默认监听 `0.0.0.0`，token 是唯一认证手段。若只通过 `adb reverse` 使用，可把
  `bindAddress` 设为 `127.0.0.1` 减少暴露面。
- **引擎能力缺口**：`resource_merge`、`page_media`、`decryptionKeys`、`open_file` / `open_folder`
  在 Android 端未实现（Host 返回 `rejected` / `false`）；`draft` 没有草稿队列，建议直接创建并返回
  `created`（扩展按成功处理）。
- **任务 `totalBytes` / `canUseRange`**：扩展上报的 `size` 可能为 0、`supportsRange` 可能为 false，
  `engine.addTask` 会按未知大小（单流）处理；若需要更准确，Host 可在 `fileSize <= 0` 时先调用
  `engine.probe(url)`（注意 `probe` 目前不接自定义请求头）。
- **协议层未做限流 / TLS**：`ws://` 明文，仅建议本机 / 受信局域网使用；单条消息上限 1 MiB
  （`BridgeProtocol.MAX_MESSAGE_CHARS`），超限回 `error` 并断开。
- **扩展端 `reload` 自更新未实现**：Android 端不负责替换浏览器扩展，`reload` 仅保留常量。

## 测试

```powershell
cd E:\van\android-miuix
$env:JAVA_HOME='E:\dev\jdk21'
.\gradlew.bat :preview:test --tests "io.github.xiaoyouchr.ghostdownloader.miuix.bridge.BrowserBridgeTest" --console=plain
```

测试用 `port = 0` 随机端口 + 假 Host（内存任务），不访问外网、不启动下载引擎，覆盖：
hello_ack、token 错误、协议版本错误、配对同意/拒绝/并发配对、配对断开清理、订阅快照字段对齐、
快照增量去重、create_task（resource / resource_merge / 缺 url / 未知 source / Host 拒绝）、
task_action（成功 / 失败 / 不支持）、未认证保护、无效 JSON、token 重置断开旧连接。

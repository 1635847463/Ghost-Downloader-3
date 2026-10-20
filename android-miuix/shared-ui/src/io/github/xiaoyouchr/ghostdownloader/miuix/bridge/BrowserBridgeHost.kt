package io.github.xiaoyouchr.ghostdownloader.miuix.bridge

/**
 * [BrowserBridge] 与应用（下载引擎 / 应用状态）之间的适配接口。
 *
 * 桥接层只负责 WebSocket 协议、配对与订阅；真正的任务数据与操作由 App 侧实现本接口完成。
 * 典型实现把 [io.github.xiaoyouchr.ghostdownloader.miuix.engine.HttpDownloadEngine] 包一层
 * （见 `bridge/README.md` 的接入示例）。
 *
 * 线程约定（重要）：
 * - `tasks()` / `createTask()` / `action()` 会在桥接层的**工作线程**上调用，实现必须线程安全；
 *   `createTask()` 允许阻塞（例如探测链接），桥接层不会因此卡住 WebSocket 收发线程；
 * - `onPairRequestChanged()` / `connectionChanged()` / `log()` 可能在 WebSocket 线程或工作线程上
 *   回调，实现应快速返回，不要做耗时 IO 或阻塞等待。
 */
interface BrowserBridgeHost {

    /** 当前配对令牌（扩展端 `hello.token` 必须与它完全一致）。不能返回 null。 */
    fun pairToken(): String

    /**
     * 生成并持久化新的配对令牌，返回新令牌。
     *
     * 用户在设置页点「重新生成」时调用；桥接层会断开所有已认证连接，迫使扩展用新令牌重连
     * （与桌面端 `BrowserService.regenerateToken()` 行为一致）。
     */
    fun regenerateToken(): String

    /**
     * 当前任务列表快照，用于 `task_snapshot` 推送。
     *
     * 每次调用都应返回最新数据的独立列表；桥接层会自行按 `createdAt` 倒序排序并做去重。
     */
    fun tasks(): List<BridgeTaskSummary>

    /**
     * 创建下载任务。返回 [BridgeCreateTaskResult.created] / [BridgeCreateTaskResult.drafted]
     * 表示成功，[BridgeCreateTaskResult.rejected] 携带失败原因（会显示在扩展弹窗里）。
     *
     * 实现通常调用 `engine.addTask(url, folder, fileName, fileSize, canUseRange, headers = ...)`，
     * 并把 `draft == true` 落到 App 的「暂存/确认」流程（若暂时没有草稿机制，可直接创建并返回 created）。
     */
    fun createTask(request: BridgeCreateTaskRequest): BridgeCreateTaskResult

    /**
     * 执行任务操作；任务不存在或操作不被支持时返回 false。
     *
     * 语义应对齐桌面端 `browser_service.py` 的 `_onTaskAction`：
     * - [TaskAction.TOGGLE_PAUSE]：运行中 → 暂停；等待 / 暂停 / 失败 → 开始；已完成 → false；
     * - [TaskAction.CANCEL]：删除任务并删除已下载文件；
     * - [TaskAction.REMOVE]：删除任务但保留文件；
     * - [TaskAction.REDOWNLOAD]：清空进度 / 成品后重新下载；
     * - [TaskAction.OPEN_FILE] / [TaskAction.OPEN_FOLDER]：Android 上可返回 false（或由 Host 用 Intent 打开）。
     */
    fun action(taskId: String, action: TaskAction): Boolean

    /**
     * 待确认配对请求变化：新的请求（非 null）或请求被处理 / 连接断开（null）。
     *
     * 与桌面端 `pairRequestChanged` 信号等价，App 可据此弹配对确认框。
     */
    fun onPairRequestChanged(request: PairRequest?)

    /** hello_ack 里的 appVersion，默认取应用版本；Host 可覆盖。 */
    fun appVersion(): String = "GhostDownloader Miuix"

    /**
     * 已认证连接数从 0→1 或 1→0 时回调（桌面端 `connectionChanged` 信号）。
     * 仅用于展示「扩展已连接」状态，默认空实现。
     */
    fun connectionChanged(connected: Boolean) {}

    /** 诊断日志钩子，默认输出到 stdout；Host 可转发到 App 日志。 */
    fun log(message: String) {
        println("[BrowserBridge] $message")
    }
}

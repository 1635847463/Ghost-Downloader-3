package io.github.xiaoyouchr.ghostdownloader.miuix.bridge

/**
 * 待确认的配对请求，对齐桌面端 `app/services/browser_service.py` 的 `PairRequest`
 * 与扩展端 `pair_request` 报文字段。
 *
 * UI 通过 [BrowserBridge.pendingPairRequest] 展示它，再调用 [BrowserBridge.approvePair] /
 * [BrowserBridge.rejectPair] 处理。
 */
data class PairRequest(
    val requestId: String,
    val peerAddress: String = "",
    val extensionVersion: String = "",
    val clientKind: String = ""
)

/**
 * 推送给扩展端的任务摘要，字段与桌面端 `toTaskSummary()` 对齐，另外补上扩展端
 * `TaskSummary.shouldOpenWhenDone`（扩展本地状态，服务端固定 false）。
 *
 * JSON 由 Gson 序列化（字段名即属性名），扩展端 `TaskCard.tsx` 依赖：
 * - `status`：`waiting` / `running` / `paused` / `completed` / `failed`，必须小写；
 * - `progress`：0~100 的百分比；
 * - `receivedBytes` / `fileSize` / `speed`：字节 / 字节每秒；
 * - `createdAt`：毫秒时间戳。
 */
data class BridgeTaskSummary(
    val taskId: String,
    val name: String,
    val status: String,
    val progress: Double,
    val receivedBytes: Long,
    val fileSize: Long,
    val speed: Long,
    val createdAt: Long,
    val canPause: Boolean,
    val canOpenFile: Boolean,
    val canOpenFolder: Boolean,
    val fileExt: String = "",
    val packName: String = "",
    val shouldOpenWhenDone: Boolean = false
)

/**
 * create_task 的 `resource_merge` 来源里 `resources` 数组的单项。
 * 字段对齐扩展端 `sendMergeResources()`（url / filename / mime / size / headers / pageTitle / supportsRange）。
 */
data class BridgeResource(
    val url: String,
    val fileName: String = "",
    val mime: String = "",
    val size: Long = 0L,
    val canUseRange: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val pageTitle: String = ""
)

/**
 * 解析后的 create_task 请求，交给 [BrowserBridgeHost.createTask]。
 *
 * 覆盖扩展端可能发送的全部任务来源；App 侧引擎当前只支持普通 HTTP(S) 直链，
 * 因此 Host 适配层应把 [TaskSource.RESOURCE_MERGE] / [TaskSource.PAGE_MEDIA]
 * 以 [BridgeCreateTaskResult.rejected] 返回（或后续扩展引擎能力）。
 *
 * [rawPayloadJson] 保留原始 payload，便于以后接入新字段而不破坏公开 API。
 */
data class BridgeCreateTaskRequest(
    val source: TaskSource,
    /** create_task 顶层的 title，扩展端用来命名任务（下载接管时是浏览器文件名）。 */
    val title: String = "",
    /** 扩展端 draft 标记（“另存为”右键菜单为 true）；null 表示未指定，由服务端设置决定。 */
    val draft: Boolean? = null,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val fileName: String = "",
    val fileSize: Long = 0L,
    val canUseRange: Boolean = false,
    val pageUrl: String = "",
    val pageTitle: String = "",
    val resources: List<BridgeResource> = emptyList(),
    val decryptionKeys: List<String> = emptyList(),
    val rawPayloadJson: String = ""
)

/**
 * create_task 的执行结果。[BrowserBridgeHost.createTask] 返回它，桥接层原样转成
 * `create_task_result` 报文（status / taskId / message）。
 */
data class BridgeCreateTaskResult(
    val status: CreateTaskStatus,
    val taskId: String = "",
    val message: String = ""
) {
    companion object {
        fun created(taskId: String): BridgeCreateTaskResult =
            BridgeCreateTaskResult(status = CreateTaskStatus.CREATED, taskId = taskId)

        fun drafted(message: String = ""): BridgeCreateTaskResult =
            BridgeCreateTaskResult(status = CreateTaskStatus.DRAFTED, message = message)

        fun rejected(message: String): BridgeCreateTaskResult =
            BridgeCreateTaskResult(status = CreateTaskStatus.REJECTED, message = message)
    }
}

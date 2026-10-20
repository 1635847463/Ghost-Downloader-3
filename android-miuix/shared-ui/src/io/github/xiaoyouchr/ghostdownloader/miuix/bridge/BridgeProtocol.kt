package io.github.xiaoyouchr.ghostdownloader.miuix.bridge

/**
 * 浏览器扩展桥接协议的固定值。
 *
 * 全部对齐公开仓库 Ghost Downloader 的两端实现，不自行发明字段：
 * - 扩展端：`browser_extension/app/src/shared/constants.ts`（DEFAULT_SERVER_URL）、
 *   `browser_extension/app/src/background/constants.ts`（PROTOCOL_VERSION / PAIR_TOKEN_KEY）；
 * - 桌面端：`app/services/browser_service.py`（MessageType / ErrorCode / TaskAction / CreateTaskStatus）。
 */
object BridgeProtocol {
    /** 扩展端 `PROTOCOL_VERSION`。hello / pair_request 携带的版本必须与它一致。 */
    const val VERSION: Int = 2

    /** 扩展端 `DEFAULT_SERVER_URL = ws://127.0.0.1:14370` 中的端口。 */
    const val DEFAULT_PORT: Int = 14370

    /** 扩展端默认连接地址，供 App 显示 / 复制。 */
    const val DEFAULT_SERVER_URL: String = "ws://127.0.0.1:14370"

    /**
     * 单条消息最大字符数。桌面端 `WebSocketStream(maxSize=2**20)` 同值；
     * 超过后回 error 并断开，避免异常报文吃满内存。
     */
    const val MAX_MESSAGE_CHARS: Int = 1 shl 20
}

/** 消息类型（扩展端 `desktop-bridge.ts` 与桌面端 `MessageType` 的并集）。 */
enum class BridgeMessageType(val wire: String) {
    ERROR("error"),
    HELLO("hello"),
    HELLO_ACK("hello_ack"),
    PAIR_REQUEST("pair_request"),
    PAIR_RESULT("pair_result"),
    SUBSCRIBE_TASKS("subscribe_tasks"),
    TASK_SNAPSHOT("task_snapshot"),
    CREATE_TASK("create_task"),
    CREATE_TASK_RESULT("create_task_result"),
    TASK_ACTION("task_action"),
    TASK_ACTION_RESULT("task_action_result"),
    RELOAD("reload");

    companion object {
        fun fromWire(value: String): BridgeMessageType? = entries.firstOrNull { it.wire == value }
    }
}

/** 错误码，扩展端 `DesktopConnectionState` 状态机依赖 `unauthorized` / `protocol_mismatch`。 */
enum class BridgeErrorCode(val wire: String) {
    BAD_REQUEST("bad_request"),
    PROTOCOL_MISMATCH("protocol_mismatch"),
    UNAUTHORIZED("unauthorized")
}

/**
 * 任务操作。扩展端 `TaskAction = "toggle_pause" | "redownload" | "open_file" | "open_folder" | "cancel" | "remove" | "open_when_done"`。
 *
 * `open_when_done` 由扩展端本地处理（见 `background.ts` 的 `popup_task_action`），不会发到服务端，故不在此列。
 */
enum class TaskAction(val wire: String) {
    TOGGLE_PAUSE("toggle_pause"),
    CANCEL("cancel"),
    REMOVE("remove"),
    REDOWNLOAD("redownload"),
    OPEN_FILE("open_file"),
    OPEN_FOLDER("open_folder");

    companion object {
        fun fromWire(value: String): TaskAction? = entries.firstOrNull { it.wire == value }

        /** hello_ack.capabilities.taskActions 的内容，与桌面端 `[a.value for a in TaskAction]` 一致。 */
        val allWire: List<String> = entries.map { it.wire }
    }
}

/**
 * create_task 的任务来源。扩展端 `resource-bridge.ts` / `background.ts` 实际会发：
 * `download`（浏览器下载接管 / 右键 / 图片）、`resource`（资源面板）、`resource_merge`（音视频合并）、
 * `page_media`（页面媒体解析，桌面端走 yt-dlp）。
 */
enum class TaskSource(val wire: String) {
    DOWNLOAD("download"),
    RESOURCE("resource"),
    RESOURCE_MERGE("resource_merge"),
    PAGE_MEDIA("page_media");

    companion object {
        fun fromWire(value: String): TaskSource? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * create_task_result.status。
 *
 * 扩展端把 `created` / `drafted` 视为成功，其余（含桌面端的 `rejected`）视为失败并展示 message。
 */
enum class CreateTaskStatus(val wire: String) {
    CREATED("created"),
    DRAFTED("drafted"),
    REJECTED("rejected")
}

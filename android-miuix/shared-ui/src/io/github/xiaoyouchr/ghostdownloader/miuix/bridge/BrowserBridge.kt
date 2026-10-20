package io.github.xiaoyouchr.ghostdownloader.miuix.bridge

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer

/**
 * 浏览器扩展 WebSocket 桥接服务端（Java-WebSocket）。
 *
 * 协议与公开的 Ghost Downloader 桌面端 / 浏览器扩展保持一致，实现以下链路：
 * 1. 扩展连接后发 `hello{protocolVersion=2,token,...}` → 校验协议版本与配对令牌 →
 *    `hello_ack{protocolVersion,appVersion,capabilities}`；
 * 2. 无令牌时发 `pair_request{requestId,...}` → App 通过 [approvePair] / [rejectPair] 决定 →
 *    扩展收到 `pair_result{ok,token?,message}` 后保存令牌并重连；
 * 3. `subscribe_tasks` → 推送 `task_snapshot{tasks:[...]}`；之后可通过 [broadcastSnapshot]
 *    （或构造参数里的 `snapshotIntervalMs` 定时器）增量 / 去重推送；
 * 4. `create_task{requestId,source,title,draft,payload}` → [BrowserBridgeHost.createTask] →
 *    `create_task_result{requestId,status,taskId?,message?}`；
 * 5. `task_action{requestId,action,taskId}` → [BrowserBridgeHost.action] →
 *    `task_action_result{requestId,ok,message?}`；
 * 6. 出错时发 `error{message,code,requestId?}`；未认证连接只允许 hello / pair_request，
 *    其它消息回 `unauthorized` 并断开（对齐桌面端行为）。
 *
 * 线程模型：
 * - Java-WebSocket 的 onOpen / onClose / onError 只做轻量状态维护；
 * - onMessage 立即转交给内部固定线程池，[BrowserBridgeHost] 的阻塞调用不会卡住 WS 收发线程；
 * - 所有发送通过 Java-WebSocket 的线程安全队列完成，任意线程调用 [broadcastSnapshot] 都安全；
 * - 快照按连接去重（内容不变不重复发），最多每秒推送一次（可配置）。
 *
 * 生命周期：构造 → [start] → … → [close]；实例不可重复启动。默认监听 0.0.0.0:14370
 * （手机与电脑不在同一 localhost，扩展需把服务器地址改成手机局域网 IP；也可用 `adb reverse`）。
 */
class BrowserBridge(
    private val host: BrowserBridgeHost,
    private val port: Int = BridgeProtocol.DEFAULT_PORT,
    private val bindAddress: String = DEFAULT_BIND_ADDRESS,
    private val snapshotIntervalMs: Long = DEFAULT_SNAPSHOT_INTERVAL_MS
) : AutoCloseable {

    /** 单个连接的服务端状态。字段均为 volatile，跨 WS / 工作线程读取。 */
    private class ClientSession(val connection: WebSocket, val peerAddress: String) {
        @Volatile
        var authenticated: Boolean = false

        @Volatile
        var subscribed: Boolean = false

        @Volatile
        var extensionVersion: String = ""

        @Volatile
        var clientKind: String = ""

        @Volatile
        var installType: String = ""

        @Volatile
        var lastSnapshot: String? = null
    }

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    private val sessions = ConcurrentHashMap<WebSocket, ClientSession>()

    private val snapshotLock = Any()
    private val pairLock = Any()

    @Volatile
    private var pendingPair: PairRequest? = null

    @Volatile
    private var pendingPairConnection: WebSocket? = null

    private val messageExecutor: ExecutorService =
        Executors.newFixedThreadPool(MESSAGE_THREADS, daemonThreadFactory("gd-browser-bridge"))

    private val snapshotScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("gd-browser-bridge-snapshot"))

    private var snapshotTimer: ScheduledFuture<*>? = null

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var closed: Boolean = false

    private var server: BridgeServer? = null

    private var boundPort: Int = port

    /** 实际监听端口。构造时传 0（随机端口）时，[start] 成功后这里会更新为真实端口。 */
    val actualPort: Int
        get() = boundPort

    val isRunning: Boolean
        get() = running

    /** 当前待 App 确认的配对请求，没有则为 null。 */
    val pendingPairRequest: PairRequest?
        get() = pendingPair

    /**
     * 启动监听。[start] 会阻塞到端口绑定完成（通常毫秒级，便于把端口占用错误同步抛给 UI）。
     *
     * @throws IllegalStateException 端口被占用、绑定失败或实例已关闭。
     */
    @Synchronized
    fun start() {
        check(!closed) { "BrowserBridge 已关闭，不能重复启动" }
        if (running) return

        // 对齐桌面端：令牌为空时自动生成，保证扩展总能配对。
        val token = runCatching { host.pairToken() }.getOrDefault("")
        if (token.isBlank()) {
            runCatching { host.regenerateToken() }
                .onFailure { log("生成配对令牌失败：${it.message}") }
            val regenerated = runCatching { host.pairToken() }.getOrDefault("")
            if (regenerated.isBlank()) {
                log("警告：配对令牌仍为空，扩展将无法通过 hello 认证")
            }
        }

        val newServer = BridgeServer(InetSocketAddress(bindAddress, port))
        server = newServer
        try {
            newServer.start()
            val started = newServer.awaitStarted(START_TIMEOUT_MS)
            if (!started || newServer.startFailure != null) {
                throw IllegalStateException(
                    "无法在 $bindAddress:$port 启动浏览器扩展桥接",
                    newServer.startFailure
                )
            }
        } catch (error: Throwable) {
            server = null
            runCatching { newServer.stop(200) }
            throw if (error is IllegalStateException) error
            else IllegalStateException("浏览器扩展桥接启动失败", error)
        }

        boundPort = newServer.portNow()
        running = true
        startSnapshotTimer()
        log("浏览器扩展桥接已启动：ws://$bindAddress:$boundPort")
    }

    /** 停止监听并释放全部连接与线程。幂等。 */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        running = false

        snapshotTimer?.cancel(false)
        snapshotTimer = null

        val currentServer = server
        server = null

        val clearedPending = synchronized(pairLock) {
            val pending = pendingPair
            pendingPair = null
            pendingPairConnection = null
            pending
        }

        currentServer?.let { srv ->
            sessions.keys.forEach { connection -> runCatching { connection.close() } }
            runCatching { srv.stop(300) }
        }
        sessions.clear()
        if (clearedPending != null) {
            notifyPairRequest(null)
        }

        snapshotScheduler.shutdownNow()
        messageExecutor.shutdownNow()
        log("浏览器扩展桥接已停止")
    }

    // ------------------------------------------------------------------ 配对

    /**
     * 同意配对请求：给发起 `pair_request` 的连接回 `pair_result{ok=true,token}`。
     *
     * 扩展端收到后会保存 token 并主动重连（见 `requestPairing()`），因此这里不把该连接标记为已认证。
     *
     * @return 请求不存在 / 已被处理 / 连接已断开时返回 false。
     */
    fun approvePair(requestId: String): Boolean {
        val connection = takePairConnection(requestId) ?: return false
        if (!connection.isOpen) return false

        val token = runCatching { host.pairToken() }.getOrDefault("")
        val payload = JsonObject().apply {
            addProperty("type", BridgeMessageType.PAIR_RESULT.wire)
            addProperty("requestId", requestId)
            addProperty("ok", true)
            addProperty("token", token)
            addProperty("message", "配对成功")
        }
        send(connection, payload)
        notifyPairRequest(null)
        log("已同意扩展配对请求：$requestId")
        return true
    }

    /** 拒绝配对请求：回 `pair_result{ok=false,message}`。返回值含义同 [approvePair]。 */
    fun rejectPair(requestId: String): Boolean {
        val connection = takePairConnection(requestId) ?: return false
        if (!connection.isOpen) return false

        val payload = JsonObject().apply {
            addProperty("type", BridgeMessageType.PAIR_RESULT.wire)
            addProperty("requestId", requestId)
            addProperty("ok", false)
            addProperty("message", "已拒绝配对请求")
        }
        send(connection, payload)
        notifyPairRequest(null)
        log("已拒绝扩展配对请求：$requestId")
        return true
    }

    private fun takePairConnection(requestId: String): WebSocket? = synchronized(pairLock) {
        val pending = pendingPair
        if (pending == null || pending.requestId != requestId) {
            return@synchronized null
        }
        val connection = pendingPairConnection
        pendingPair = null
        pendingPairConnection = null
        connection
    }

    /**
     * 重置配对令牌并断开所有已认证连接（桌面端 `BrowserService.regenerateToken()` 语义）。
     *
     * @return Host 生成的新令牌。
     */
    fun regenerateToken(): String {
        val token = host.regenerateToken()
        val stale = sessions.values.filter { it.authenticated }
        stale.forEach { session ->
            session.authenticated = false
            session.subscribed = false
            session.lastSnapshot = null
            runCatching { session.connection.close() }
        }
        if (stale.isNotEmpty()) {
            notifyConnectionChanged(false)
        }
        log("配对令牌已重置，已断开 ${stale.size} 个已认证连接")
        return token
    }

    // ------------------------------------------------------------------ 快照

    /**
     * 立即向所有「已认证且已订阅」的连接推送任务快照。
     *
     * 线程安全；内容与上次推送完全相同的连接会被跳过。App 可在任务变化后主动调用，
     * 也可以依赖构造参数 `snapshotIntervalMs` 的定时推送。
     */
    fun broadcastSnapshot() {
        if (!running) return
        synchronized(snapshotLock) {
            val subscribers = sessions.values.filter { it.authenticated && it.subscribed }
            if (subscribers.isEmpty()) return

            val snapshot = try {
                buildSnapshotJson()
            } catch (error: Throwable) {
                log("生成任务快照失败：${error.message}")
                return
            }

            subscribers.forEach { session ->
                if (session.lastSnapshot == snapshot) return@forEach
                session.lastSnapshot = snapshot
                sendText(session.connection, snapshot)
            }
        }
    }

    private fun buildSnapshotJson(): String {
        val tasks = host.tasks().sortedByDescending { it.createdAt }
        val payload = JsonObject().apply {
            addProperty("type", BridgeMessageType.TASK_SNAPSHOT.wire)
            add("tasks", gson.toJsonTree(tasks))
        }
        return payload.toString()
    }

    private fun startSnapshotTimer() {
        val interval = snapshotIntervalMs
        if (interval <= 0L) return
        snapshotTimer = snapshotScheduler.scheduleWithFixedDelay(
            {
                try {
                    broadcastSnapshot()
                } catch (error: Throwable) {
                    log("定时快照推送失败：${error.message}")
                }
            },
            interval,
            interval,
            TimeUnit.MILLISECONDS
        )
    }

    // -------------------------------------------------------------- 消息处理

    private inner class BridgeServer(address: InetSocketAddress) : WebSocketServer(address) {

        private val startLatch = CountDownLatch(1)

        @Volatile
        var startFailure: Throwable? = null
            private set

        @Volatile
        private var startPort: Int = address.port

        init {
            isDaemon = true
            setReuseAddr(true)
        }

        override fun onStart() {
            startPort = getPort()
            startLatch.countDown()
        }

        override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
            val peer = peerAddressOf(connection)
            sessions[connection] = ClientSession(connection, peer)
            log("扩展已连接：$peer")
        }

        override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) {
            val session = sessions.remove(connection) ?: return

            val clearedPending = synchronized(pairLock) {
                if (pendingPairConnection === connection) {
                    val pending = pendingPair
                    pendingPair = null
                    pendingPairConnection = null
                    pending
                } else {
                    null
                }
            }
            if (clearedPending != null) {
                notifyPairRequest(null)
            }
            if (session.authenticated) {
                notifyConnectionChanged(hasAuthenticatedClients())
            }
            log("扩展连接已断开：${session.peerAddress}（code=$code, remote=$remote）")
        }

        override fun onMessage(connection: WebSocket, message: String) {
            if (!running) return
            val session = sessions[connection] ?: return
            try {
                messageExecutor.execute { processMessage(session, message) }
            } catch (_: RejectedExecutionException) {
                // 桥接正在关闭，丢弃即可。
            }
        }

        override fun onError(connection: WebSocket?, ex: Exception) {
            if (connection == null) {
                startFailure = ex
                startLatch.countDown()
            }
            log("桥接网络错误：${ex.message}")
        }

        fun awaitStarted(timeoutMs: Long): Boolean = try {
            startLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        fun portNow(): Int = if (startPort != 0) startPort else getPort()
    }

    private fun processMessage(session: ClientSession, raw: String) {
        if (raw.length > BridgeProtocol.MAX_MESSAGE_CHARS) {
            sendError(session, "消息过大", code = BridgeErrorCode.BAD_REQUEST)
            session.connection.close()
            return
        }

        val parsed = try {
            JsonParser.parseString(raw)
        } catch (_: Exception) {
            sendError(session, "无效的消息格式")
            return
        }
        if (!parsed.isJsonObject) {
            sendError(session, "无效的消息结构")
            return
        }
        val data = parsed.asJsonObject

        val type = BridgeMessageType.fromWire(data.stringValue("type"))
        if (type == null) {
            sendError(session, "未知的消息类型")
            return
        }

        when (type) {
            BridgeMessageType.PAIR_REQUEST -> handlePairRequest(session, data)
            BridgeMessageType.HELLO -> handleHello(session, data)
            else -> {
                if (!session.authenticated) {
                    sendError(session, "请先完成握手认证", code = BridgeErrorCode.UNAUTHORIZED)
                    session.connection.close()
                    return
                }
                when (type) {
                    BridgeMessageType.SUBSCRIBE_TASKS -> {
                        session.subscribed = true
                        session.lastSnapshot = null
                        broadcastSnapshot()
                    }

                    BridgeMessageType.CREATE_TASK -> handleCreateTask(session, data)
                    BridgeMessageType.TASK_ACTION -> handleTaskAction(session, data)
                    // hello_ack / pair_result / task_snapshot 等只允许服务端下发，客户端误发时忽略（同桌面端）。
                    else -> log("忽略不支持的消息类型：${type.wire}")
                }
            }
        }
    }

    private fun handlePairRequest(session: ClientSession, data: JsonObject) {
        val requestId = data.stringValue("requestId")
        if (requestId.isBlank()) {
            sendError(session, "无效的配对请求")
            return
        }

        var alreadyPending = false
        synchronized(pairLock) {
            val current = pendingPair
            if (current != null && pendingPairConnection !== session.connection) {
                alreadyPending = true
            }
        }
        if (alreadyPending) {
            send(
                session.connection,
                JsonObject().apply {
                    addProperty("type", BridgeMessageType.PAIR_RESULT.wire)
                    addProperty("requestId", requestId)
                    addProperty("ok", false)
                    addProperty("message", "已有配对请求待处理")
                }
            )
            return
        }

        val request = PairRequest(
            requestId = requestId,
            peerAddress = session.peerAddress,
            extensionVersion = data.stringValue("extensionVersion"),
            clientKind = data.stringValue("clientKind")
        )
        synchronized(pairLock) {
            pendingPair = request
            pendingPairConnection = session.connection
        }
        log("收到扩展配对请求：$requestId（${session.peerAddress}）")
        notifyPairRequest(request)
    }

    private fun handleHello(session: ClientSession, data: JsonObject) {
        val requestId = data.stringValue("requestId").ifBlank { null }

        if (data.positiveIntValue("protocolVersion") != BridgeProtocol.VERSION) {
            sendError(
                session,
                "协议版本不匹配",
                requestId = requestId,
                code = BridgeErrorCode.PROTOCOL_MISMATCH
            )
            session.connection.close()
            return
        }

        val expectedToken = runCatching { host.pairToken() }.getOrDefault("")
        // expectedToken 为空说明 Host 尚未初始化令牌，任何 token 都不应放行（避免空字符串误判通过）。
        if (expectedToken.isBlank() || data.stringValue("token") != expectedToken) {
            sendError(session, "配对令牌无效", requestId = requestId, code = BridgeErrorCode.UNAUTHORIZED)
            session.connection.close()
            return
        }

        session.authenticated = true
        session.extensionVersion = data.stringValue("extensionVersion")
        session.clientKind = data.stringValue("clientKind")
        session.installType = data.stringValue("installType")
        session.lastSnapshot = null

        val ack = JsonObject().apply {
            addProperty("type", BridgeMessageType.HELLO_ACK.wire)
            addProperty("protocolVersion", BridgeProtocol.VERSION)
            addProperty("appVersion", runCatching { host.appVersion() }.getOrDefault(""))
            add("capabilities", JsonObject().apply {
                addProperty("taskSnapshots", true)
                add("taskActions", gson.toJsonTree(TaskAction.allWire))
            })
        }
        send(session.connection, ack)
        notifyConnectionChanged(true)
        log(
            "扩展握手成功：version=${session.extensionVersion}, kind=${session.clientKind}, " +
                "installType=${session.installType}"
        )
    }

    private fun handleCreateTask(session: ClientSession, data: JsonObject) {
        val requestId = data.stringValue("requestId")
        val payload = data.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject
        if (requestId.isBlank() || payload == null) {
            sendError(session, "无效的请求")
            return
        }

        val rawSource = data.stringValue("source").ifBlank { TaskSource.RESOURCE.wire }
        val source = TaskSource.fromWire(rawSource)
        if (source == null) {
            sendError(session, "未知的任务来源")
            return
        }

        val request = try {
            parseCreateTaskRequest(
                source = source,
                title = data.stringValue("title"),
                draft = data.booleanOrNull("draft"),
                payload = payload
            )
        } catch (error: BridgeRequestException) {
            sendCreateTaskResult(
                session,
                requestId,
                BridgeCreateTaskResult.rejected(error.message ?: "无效的下载请求")
            )
            return
        }

        val result = try {
            host.createTask(request)
        } catch (error: Throwable) {
            log("创建任务失败：${error.message}")
            BridgeCreateTaskResult.rejected(error.message ?: error.javaClass.simpleName)
        }
        sendCreateTaskResult(session, requestId, result)
        log("create_task [$requestId] source=${source.wire} -> ${result.status.wire}")
    }

    private fun handleTaskAction(session: ClientSession, data: JsonObject) {
        val requestId = data.stringValue("requestId")
        if (requestId.isBlank()) {
            sendError(session, "缺少 requestId")
            return
        }

        val action = TaskAction.fromWire(data.stringValue("action"))
        if (action == null) {
            sendTaskActionResult(session, requestId, ok = false, message = "不支持的操作")
            return
        }

        val taskId = data.stringValue("taskId")
        val ok = taskId.isNotBlank() && try {
            host.action(taskId, action)
        } catch (error: Throwable) {
            log("任务操作失败：${error.message}")
            false
        }

        val message = when {
            taskId.isBlank() -> "任务不存在"
            !ok -> "操作失败"
            else -> ""
        }
        sendTaskActionResult(session, requestId, ok, message)
    }

    // -------------------------------------------------------------- 报文构造

    private fun sendError(
        session: ClientSession,
        message: String,
        requestId: String? = null,
        code: BridgeErrorCode = BridgeErrorCode.BAD_REQUEST
    ) {
        val payload = JsonObject().apply {
            addProperty("type", BridgeMessageType.ERROR.wire)
            addProperty("message", message)
            addProperty("code", code.wire)
            if (!requestId.isNullOrBlank()) {
                addProperty("requestId", requestId)
            }
        }
        send(session.connection, payload)
    }

    private fun sendTaskActionResult(
        session: ClientSession,
        requestId: String,
        ok: Boolean,
        message: String = ""
    ) {
        val payload = JsonObject().apply {
            addProperty("type", BridgeMessageType.TASK_ACTION_RESULT.wire)
            addProperty("requestId", requestId)
            addProperty("ok", ok)
            if (message.isNotBlank()) {
                addProperty("message", message)
            }
        }
        send(session.connection, payload)
    }

    private fun sendCreateTaskResult(
        session: ClientSession,
        requestId: String,
        result: BridgeCreateTaskResult
    ) {
        val payload = JsonObject().apply {
            addProperty("type", BridgeMessageType.CREATE_TASK_RESULT.wire)
            addProperty("requestId", requestId)
            addProperty("status", result.status.wire)
            if (result.taskId.isNotBlank()) addProperty("taskId", result.taskId)
            if (result.message.isNotBlank()) addProperty("message", result.message)
        }
        send(session.connection, payload)
    }

    private fun send(connection: WebSocket, payload: JsonObject) {
        sendText(connection, payload.toString())
    }

    private fun sendText(connection: WebSocket, text: String) {
        try {
            if (connection.isOpen) {
                connection.send(text)
            }
        } catch (error: Throwable) {
            log("发送消息失败：${error.message}")
        }
    }

    // -------------------------------------------------------------- 解析工具

    private class BridgeRequestException(message: String) : Exception(message)

    private fun parseCreateTaskRequest(
        source: TaskSource,
        title: String,
        draft: Boolean?,
        payload: JsonObject
    ): BridgeCreateTaskRequest {
        val resources = if (source == TaskSource.RESOURCE_MERGE) {
            payload.resourcesValue("resources")
        } else {
            emptyList()
        }
        val url = when (source) {
            TaskSource.RESOURCE_MERGE -> resources.firstOrNull()?.url.orEmpty()
            else -> payload.stringValue("url")
        }
        if (url.isBlank()) {
            throw BridgeRequestException(
                if (source == TaskSource.RESOURCE_MERGE) "缺少待合并资源" else "缺少下载链接"
            )
        }

        return BridgeCreateTaskRequest(
            source = source,
            title = title,
            draft = draft,
            url = url,
            headers = payload.headersValue("headers"),
            fileName = payload.stringValue("filename").ifBlank { title },
            fileSize = payload.positiveLongValue("size"),
            canUseRange = payload.booleanValue("supportsRange"),
            pageUrl = payload.stringValue("pageUrl"),
            pageTitle = payload.stringValue("pageTitle"),
            resources = resources,
            decryptionKeys = payload.stringListValue("decryptionKeys"),
            rawPayloadJson = payload.toString()
        )
    }

    private fun JsonObject.stringValue(key: String): String {
        val element = get(key) ?: return ""
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            element.asString
        } else {
            ""
        }
    }

    private fun JsonObject.positiveIntValue(key: String): Int {
        val element = get(key) ?: return 0
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return 0
        val value = runCatching { element.asInt }.getOrNull() ?: return 0
        return if (value > 0) value else 0
    }

    private fun JsonObject.positiveLongValue(key: String): Long {
        val element = get(key) ?: return 0L
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return 0L
        val value = runCatching { element.asLong }.getOrNull() ?: return 0L
        return if (value > 0L) value else 0L
    }

    private fun JsonObject.booleanValue(key: String): Boolean {
        val element = get(key) ?: return false
        return element.isJsonPrimitive && element.asJsonPrimitive.isBoolean && element.asBoolean
    }

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        val element = get(key) ?: return null
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) {
            element.asBoolean
        } else {
            null
        }
    }

    private fun JsonObject.headersValue(key: String): Map<String, String> {
        val element = get(key) ?: return emptyMap()
        if (!element.isJsonObject) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for ((name, value) in element.asJsonObject.entrySet()) {
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                result[name] = value.asString
            }
        }
        return result
    }

    private fun JsonObject.stringListValue(key: String): List<String> {
        val element = get(key) ?: return emptyList()
        if (!element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { item ->
            if (item.isJsonPrimitive && item.asJsonPrimitive.isString) item.asString else null
        }
    }

    private fun JsonObject.resourcesValue(key: String): List<BridgeResource> {
        val element = get(key) ?: return emptyList()
        if (!element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val resource = item.asJsonObject
            val url = resource.stringValue("url")
            if (url.isBlank()) return@mapNotNull null
            BridgeResource(
                url = url,
                fileName = resource.stringValue("filename"),
                mime = resource.stringValue("mime"),
                size = resource.positiveLongValue("size"),
                canUseRange = resource.booleanValue("supportsRange"),
                headers = resource.headersValue("headers"),
                pageTitle = resource.stringValue("pageTitle")
            )
        }
    }

    // -------------------------------------------------------------- 状态回调

    private fun hasAuthenticatedClients(): Boolean = sessions.values.any { it.authenticated }

    private fun notifyPairRequest(request: PairRequest?) {
        runCatching { host.onPairRequestChanged(request) }
            .onFailure { log("onPairRequestChanged 回调异常：${it.message}") }
    }

    private fun notifyConnectionChanged(connected: Boolean) {
        runCatching { host.connectionChanged(connected) }
            .onFailure { log("connectionChanged 回调异常：${it.message}") }
    }

    private fun log(message: String) {
        runCatching { host.log(message) }
    }

    private fun peerAddressOf(connection: WebSocket): String {
        val address = connection.remoteSocketAddress ?: return ""
        val host = address.address?.hostAddress ?: address.hostString
        return "$host:${address.port}"
    }

    companion object {
        /** 监听全部网卡：扩展既可通过手机局域网 IP 连接，也可用 `adb reverse` 走 127.0.0.1。 */
        const val DEFAULT_BIND_ADDRESS: String = "0.0.0.0"

        /** 定时快照推送周期；设为 0 可关闭自动推送，仅由 [broadcastSnapshot] 主动触发。 */
        const val DEFAULT_SNAPSHOT_INTERVAL_MS: Long = 1_000L

        private const val START_TIMEOUT_MS: Long = 5_000L
        private const val MESSAGE_THREADS: Int = 2

        private fun daemonThreadFactory(prefix: String): ThreadFactory {
            val counter = AtomicInteger(0)
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}

package io.github.xiaoyouchr.ghostdownloader.miuix.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.java_websocket.client.WebSocketClient
import org.java_websocket.enums.ReadyState
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 浏览器扩展桥接的端到端测试：真实起 [BrowserBridge]（随机端口、只监听 127.0.0.1）+
 * Java-WebSocket 测试客户端 + 内存假 Host，不依赖外网、不启动下载引擎。
 *
 * 覆盖协议主链路：hello_ack / 令牌错误 / 协议版本错误 / 配对请求的同意与拒绝 /
 *subscribe_tasks 快照 / 快照增量去重 / create_task（resource、resource_merge、失败与校验）/
 * task_action / 未认证保护 / 令牌重置 / 配对连接断开清理。
 */
class BrowserBridgeTest {

    private lateinit var host: FakeBridgeHost
    private lateinit var bridge: BrowserBridge
    private val clients = mutableListOf<TestClient>()

    @Before
    fun setUp() {
        host = FakeBridgeHost()
        bridge = BrowserBridge(
            host = host,
            port = 0,
            bindAddress = "127.0.0.1",
            snapshotIntervalMs = 0
        )
        bridge.start()
        assertTrue("桥接应处于运行状态", bridge.isRunning)
        assertTrue("随机端口应绑定成功", bridge.actualPort > 0)
    }

    @After
    fun tearDown() {
        clients.forEach { client -> runCatching { client.closeBlocking() } }
        clients.clear()
        bridge.close()
    }

    // ---------------------------------------------------------------- hello

    @Test
    fun helloWithValidTokenReturnsHelloAck() {
        val client = connect()
        client.sendJson(helloJson(host.pairToken(), requestId = "hello-1"))

        val ack = client.awaitMessage { it.stringValue("type") == "hello_ack" }
        assertEquals(BridgeProtocol.VERSION, ack.intValue("protocolVersion"))
        assertEquals("4.4.3-test", ack.stringValue("appVersion"))
        val capabilities = ack.getAsJsonObject("capabilities")
        assertTrue(capabilities.booleanValue("taskSnapshots"))
        assertEquals(
            TaskAction.allWire,
            capabilities.getAsJsonArray("taskActions").map { it.asString }
        )

        awaitCondition("Host 收到连接状态回调") { host.connectionStates.contains(true) }
    }

    @Test
    fun helloWithWrongTokenIsRejectedAndClosed() {
        val client = connect()
        client.sendJson(helloJson("wrong-token"))

        val error = client.awaitMessage { it.stringValue("type") == "error" }
        assertEquals("unauthorized", error.stringValue("code"))
        assertEquals("配对令牌无效", error.stringValue("message"))
        client.awaitClosed()
        assertFalse("令牌错误不应标记为已连接", host.connectionStates.contains(true))
    }

    @Test
    fun helloWithWrongProtocolVersionIsRejected() {
        val client = connect()
        client.sendJson(helloJson(host.pairToken(), protocolVersion = 1))

        val error = client.awaitMessage { it.stringValue("type") == "error" }
        assertEquals("protocol_mismatch", error.stringValue("code"))
        assertEquals("协议版本不匹配", error.stringValue("message"))
        client.awaitClosed()
    }

    // ---------------------------------------------------------------- 配对

    @Test
    fun pairRequestApproveReturnsToken() {
        val client = connect()
        client.sendJson(pairRequestJson("pair-1"))

        awaitCondition("桥接收到待确认配对请求") { bridge.pendingPairRequest?.requestId == "pair-1" }
        val pending = bridge.pendingPairRequest!!
        assertEquals("1.2.3", pending.extensionVersion)
        assertEquals("browser_extension", pending.clientKind)
        awaitCondition("Host 收到配对请求回调") {
            host.pairRequests.any { it?.requestId == "pair-1" }
        }

        assertTrue(bridge.approvePair("pair-1"))

        val result = client.awaitMessage { it.stringValue("type") == "pair_result" }
        assertTrue(result.booleanValue("ok"))
        assertEquals(host.pairToken(), result.stringValue("token"))
        assertEquals("pair-1", result.stringValue("requestId"))
        assertNull("配对完成后待确认请求应清空", bridge.pendingPairRequest)
        awaitCondition("Host 收到配对清空回调") {
            host.pairRequests.isNotEmpty() && host.pairRequests.last() == null
        }
    }

    @Test
    fun pairRequestRejectReturnsFailureMessage() {
        val client = connect()
        client.sendJson(pairRequestJson("pair-2"))
        awaitCondition("桥接收到待确认配对请求") { bridge.pendingPairRequest?.requestId == "pair-2" }

        assertTrue(bridge.rejectPair("pair-2"))

        val result = client.awaitMessage { it.stringValue("type") == "pair_result" }
        assertFalse(result.booleanValue("ok"))
        assertEquals("已拒绝配对请求", result.stringValue("message"))
        assertFalse("拒绝时不应下发 token", result.has("token"))
        assertNull(bridge.pendingPairRequest)
    }

    @Test
    fun secondPairRequestWhilePendingIsRejected() {
        val first = connect()
        first.sendJson(pairRequestJson("pair-a"))
        awaitCondition("桥接收到第一个配对请求") { bridge.pendingPairRequest?.requestId == "pair-a" }

        val second = connect()
        second.sendJson(pairRequestJson("pair-b"))
        val result = second.awaitMessage { it.stringValue("type") == "pair_result" }
        assertFalse(result.booleanValue("ok"))
        assertEquals("已有配对请求待处理", result.stringValue("message"))
        assertEquals("pair-a", bridge.pendingPairRequest?.requestId)
    }

    @Test
    fun pendingPairRequestClearedWhenPairingClientDisconnects() {
        val client = connect()
        client.sendJson(pairRequestJson("pair-x"))
        awaitCondition("桥接收到待确认配对请求") { bridge.pendingPairRequest?.requestId == "pair-x" }

        client.closeBlocking()

        awaitCondition("配对连接断开后清空待确认请求") { bridge.pendingPairRequest == null }
        awaitCondition("Host 收到配对清空回调") {
            host.pairRequests.isNotEmpty() && host.pairRequests.last() == null
        }
    }

    // ---------------------------------------------------------------- 快照

    @Test
    fun subscribeTasksPushesSnapshotWithAlignedFields() {
        host.tasks += taskSummary(
            taskId = "1",
            name = "first.bin",
            status = "running",
            progress = 42.5,
            receivedBytes = 425L,
            fileSize = 1_000L,
            speed = 30L,
            createdAt = 100L,
            canPause = true,
            canOpenFolder = true,
            fileExt = "bin",
            packName = "pack-1"
        )
        host.tasks += taskSummary(
            taskId = "2",
            name = "second.mp4",
            status = "completed",
            progress = 100.0,
            receivedBytes = 2_000L,
            fileSize = 2_000L,
            createdAt = 200L,
            canOpenFile = true,
            canOpenFolder = true,
            fileExt = "mp4"
        )

        val client = connectAuthenticated()
        client.sendJson(subscribeJson())

        val snapshot = client.awaitMessage { it.stringValue("type") == "task_snapshot" }
        val tasks = snapshot.getAsJsonArray("tasks")
        assertEquals(2, tasks.size())

        // 与桌面端一致：按 createdAt 倒序，最新任务在前。
        val latest = tasks[0].asJsonObject
        assertEquals("2", latest.stringValue("taskId"))

        val first = tasks[1].asJsonObject
        assertEquals("1", first.stringValue("taskId"))
        assertEquals("first.bin", first.stringValue("name"))
        assertEquals("running", first.stringValue("status"))
        assertEquals(42.5, first.get("progress").asDouble, 0.001)
        assertEquals(425L, first.get("receivedBytes").asLong)
        assertEquals(1_000L, first.get("fileSize").asLong)
        assertEquals(30L, first.get("speed").asLong)
        assertEquals(100L, first.get("createdAt").asLong)
        assertTrue(first.booleanValue("canPause"))
        assertFalse(first.booleanValue("canOpenFile"))
        assertTrue(first.booleanValue("canOpenFolder"))
        assertEquals("bin", first.stringValue("fileExt"))
        assertEquals("pack-1", first.stringValue("packName"))
        assertFalse(first.booleanValue("shouldOpenWhenDone"))
    }

    @Test
    fun broadcastSnapshotPushesChangesOncePerContent() {
        val client = connectAuthenticated()
        client.sendJson(subscribeJson())
        val initial = client.awaitMessage { it.stringValue("type") == "task_snapshot" }
        assertEquals(0, initial.getAsJsonArray("tasks").size())

        host.tasks += taskSummary(taskId = "7", status = "waiting", createdAt = 10L)
        bridge.broadcastSnapshot()

        val update = client.awaitMessage { it.stringValue("type") == "task_snapshot" }
        assertEquals(1, update.getAsJsonArray("tasks").size())

        bridge.broadcastSnapshot()
        assertNull("任务内容未变化时不应重复推送", client.awaitNoMessage(400))
    }

    @Test
    fun snapshotOnlyPushedToSubscribedClients() {
        val client = connectAuthenticated()
        host.tasks += taskSummary(taskId = "8")

        bridge.broadcastSnapshot()
        assertNull("未订阅的连接不应收到快照", client.awaitNoMessage(300))
    }

    // ------------------------------------------------------------ create_task

    @Test
    fun createTaskForwardsParsedPayloadAndReturnsCreatedTaskId() {
        host.createTaskResult = BridgeCreateTaskResult.created("42")
        val client = connectAuthenticated()

        client.sendJson(
            createTaskJson(
                requestId = "req-1",
                source = "resource",
                title = "示例视频",
                payload = JsonObject().apply {
                    addProperty("url", "https://example.com/video.mp4")
                    addProperty("filename", "video.mp4")
                    addProperty("size", 12_345L)
                    addProperty("supportsRange", true)
                    add("headers", JsonObject().apply {
                        addProperty("referer", "https://example.com/")
                        addProperty("user-agent", "Ghost-Test")
                    })
                    add("decryptionKeys", JsonArray().apply { add("key-1") })
                }
            )
        )

        val result = client.awaitMessage { it.stringValue("type") == "create_task_result" }
        assertEquals("created", result.stringValue("status"))
        assertEquals("42", result.stringValue("taskId"))
        assertEquals("req-1", result.stringValue("requestId"))

        val request = host.createRequests.single()
        assertEquals(TaskSource.RESOURCE, request.source)
        assertEquals("https://example.com/video.mp4", request.url)
        assertEquals("video.mp4", request.fileName)
        assertEquals(12_345L, request.fileSize)
        assertTrue(request.canUseRange)
        assertEquals("https://example.com/", request.headers["referer"])
        assertEquals("Ghost-Test", request.headers["user-agent"])
        assertEquals(listOf("key-1"), request.decryptionKeys)
        assertEquals("示例视频", request.title)
        assertNull("扩展未携带 draft 时应为 null", request.draft)
    }

    @Test
    fun createTaskCarriesDraftFlagAndDraftedStatus() {
        host.createTaskResult = BridgeCreateTaskResult.drafted()
        val client = connectAuthenticated()

        client.sendJson(
            createTaskJson(
                requestId = "req-draft",
                source = "download",
                title = "draft.bin",
                payload = JsonObject().apply {
                    addProperty("url", "https://example.com/draft.bin")
                    addProperty("filename", "draft.bin")
                },
                draft = true
            )
        )

        val result = client.awaitMessage { it.stringValue("type") == "create_task_result" }
        assertEquals("drafted", result.stringValue("status"))
        assertEquals(true, host.createRequests.single().draft)
    }

    @Test
    fun createTaskHostRejectionBecomesRejectedStatus() {
        host.createTaskResult = BridgeCreateTaskResult.rejected("引擎不支持该链接")
        val client = connectAuthenticated()

        client.sendJson(
            createTaskJson(
                requestId = "req-2",
                source = "download",
                title = "file.bin",
                payload = JsonObject().apply {
                    addProperty("url", "https://example.com/file.bin")
                    addProperty("filename", "file.bin")
                    addProperty("size", 0L)
                    addProperty("supportsRange", false)
                }
            )
        )

        val result = client.awaitMessage { it.stringValue("type") == "create_task_result" }
        assertEquals("rejected", result.stringValue("status"))
        assertEquals("引擎不支持该链接", result.stringValue("message"))
        assertFalse(result.has("taskId"))
    }

    @Test
    fun createTaskResourceMergeCarriesResources() {
        host.createTaskResult = BridgeCreateTaskResult.created("9")
        val client = connectAuthenticated()

        val resources = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("url", "https://cdn.example.com/video.mp4")
                addProperty("filename", "video.mp4")
                addProperty("mime", "video/mp4")
                addProperty("size", 1_000L)
                addProperty("supportsRange", true)
                addProperty("pageTitle", "标题")
                add("headers", JsonObject().apply {
                    addProperty("referer", "https://example.com/")
                })
            })
            add(JsonObject().apply {
                addProperty("url", "https://cdn.example.com/audio.m4a")
                addProperty("filename", "audio.m4a")
            })
        }

        client.sendJson(
            createTaskJson(
                requestId = "req-3",
                source = "resource_merge",
                title = "合并标题",
                payload = JsonObject().apply { add("resources", resources) }
            )
        )

        client.awaitMessage { it.stringValue("type") == "create_task_result" }
        val request = host.createRequests.single()
        assertEquals(TaskSource.RESOURCE_MERGE, request.source)
        assertEquals("https://cdn.example.com/video.mp4", request.url)
        assertEquals("合并标题", request.fileName)
        assertEquals(2, request.resources.size)
        assertEquals("video.mp4", request.resources[0].fileName)
        assertEquals("video/mp4", request.resources[0].mime)
        assertEquals(1_000L, request.resources[0].size)
        assertTrue(request.resources[0].canUseRange)
        assertEquals("https://example.com/", request.resources[0].headers["referer"])
    }

    @Test
    fun createTaskWithoutUrlReturnsRejectedResult() {
        val client = connectAuthenticated()

        client.sendJson(
            createTaskJson(
                requestId = "req-4",
                source = "download",
                title = "",
                payload = JsonObject().apply { addProperty("filename", "file.bin") }
            )
        )

        val result = client.awaitMessage { it.stringValue("type") == "create_task_result" }
        assertEquals("rejected", result.stringValue("status"))
        assertEquals("缺少下载链接", result.stringValue("message"))
        assertTrue(host.createRequests.isEmpty())
    }

    @Test
    fun createTaskUnknownSourceReturnsError() {
        val client = connectAuthenticated()

        client.sendJson(
            createTaskJson(
                requestId = "req-5",
                source = "magnet",
                title = "",
                payload = JsonObject().apply { addProperty("url", "magnet:?xt=urn:btih:abc") }
            )
        )

        val error = client.awaitMessage { it.stringValue("type") == "error" }
        assertEquals("未知的任务来源", error.stringValue("message"))
        assertEquals("bad_request", error.stringValue("code"))
        assertTrue(host.createRequests.isEmpty())
    }

    // ------------------------------------------------------------ task_action

    @Test
    fun taskActionIsForwardedAndReturnsOk() {
        val client = connectAuthenticated()

        client.sendJson(taskActionJson("act-1", "task-7", "toggle_pause"))

        val result = client.awaitMessage { it.stringValue("type") == "task_action_result" }
        assertTrue(result.booleanValue("ok"))
        assertEquals("act-1", result.stringValue("requestId"))
        assertEquals("task-7" to TaskAction.TOGGLE_PAUSE, host.actions.single())
        assertFalse("成功结果不带 message", result.has("message"))
    }

    @Test
    fun taskActionFailureReturnsMessage() {
        host.actionResult = false
        val client = connectAuthenticated()

        client.sendJson(taskActionJson("act-2", "task-8", "remove"))

        val result = client.awaitMessage { it.stringValue("type") == "task_action_result" }
        assertFalse(result.booleanValue("ok"))
        assertEquals("操作失败", result.stringValue("message"))
        assertEquals("task-8" to TaskAction.REMOVE, host.actions.single())
    }

    @Test
    fun unknownTaskActionIsRejectedWithoutHostCall() {
        val client = connectAuthenticated()

        // open_when_done 由扩展端本地处理，不应发到服务端；即便发来也要按不支持处理。
        client.sendJson(taskActionJson("act-3", "task-9", "open_when_done"))

        val result = client.awaitMessage { it.stringValue("type") == "task_action_result" }
        assertFalse(result.booleanValue("ok"))
        assertEquals("不支持的操作", result.stringValue("message"))
        assertTrue(host.actions.isEmpty())
    }

    // -------------------------------------------------------------- 安全 / 异常

    @Test
    fun unauthenticatedRequestIsRejectedAndClosed() {
        val client = connect()
        client.sendJson(subscribeJson())

        val error = client.awaitMessage { it.stringValue("type") == "error" }
        assertEquals("unauthorized", error.stringValue("code"))
        assertEquals("请先完成握手认证", error.stringValue("message"))
        client.awaitClosed()
    }

    @Test
    fun invalidJsonReturnsBadRequestButKeepsConnection() {
        val client = connect()
        client.sendJson("this is not json")

        val error = client.awaitMessage { it.stringValue("type") == "error" }
        assertEquals("无效的消息格式", error.stringValue("message"))
        assertEquals("bad_request", error.stringValue("code"))

        // 报文格式错误不应断开连接，之后仍可正常握手。
        client.sendJson(helloJson(host.pairToken()))
        client.awaitMessage { it.stringValue("type") == "hello_ack" }
    }

    @Test
    fun regenerateTokenDisconnectsAuthenticatedClients() {
        val oldToken = host.pairToken()
        val oldClient = connectAuthenticated()

        val newToken = bridge.regenerateToken()
        assertNotEquals(oldToken, newToken)
        assertEquals(newToken, host.pairToken())
        oldClient.awaitClosed()

        // 旧令牌失效。
        val staleClient = connect()
        staleClient.sendJson(helloJson(oldToken))
        val error = staleClient.awaitMessage { it.stringValue("type") == "error" }
        assertEquals("unauthorized", error.stringValue("code"))
        staleClient.awaitClosed()

        // 新令牌可正常认证。
        val freshClient = connectAuthenticated()
        freshClient.sendJson(subscribeJson())
        freshClient.awaitMessage { it.stringValue("type") == "task_snapshot" }
    }

    // ---------------------------------------------------------------- helpers

    private fun connect(): TestClient {
        val client = TestClient(URI("ws://127.0.0.1:${bridge.actualPort}"))
        assertTrue(
            "无法连接桥接端口 ${bridge.actualPort}",
            client.connectBlocking(5, TimeUnit.SECONDS)
        )
        clients += client
        return client
    }

    private fun connectAuthenticated(extensionVersion: String = "1.2.3"): TestClient {
        val client = connect()
        client.sendJson(helloJson(host.pairToken(), extensionVersion = extensionVersion))
        client.awaitMessage { it.stringValue("type") == "hello_ack" }
        return client
    }
}

// ---------------------------------------------------------------- 测试替身

private class FakeBridgeHost(initialToken: String = "test-token-0001") : BrowserBridgeHost {

    @Volatile
    var token: String = initialToken

    @Volatile
    var createTaskResult: BridgeCreateTaskResult = BridgeCreateTaskResult.created("1")

    @Volatile
    var actionResult: Boolean = true

    val tasks = CopyOnWriteArrayList<BridgeTaskSummary>()
    val createRequests = CopyOnWriteArrayList<BridgeCreateTaskRequest>()
    val actions = CopyOnWriteArrayList<Pair<String, TaskAction>>()
    val pairRequests = CopyOnWriteArrayList<PairRequest?>()
    val connectionStates = CopyOnWriteArrayList<Boolean>()
    val logs = CopyOnWriteArrayList<String>()

    override fun pairToken(): String = token

    override fun regenerateToken(): String {
        token = "regenerated-${System.nanoTime()}"
        return token
    }

    override fun tasks(): List<BridgeTaskSummary> = tasks.toList()

    override fun createTask(request: BridgeCreateTaskRequest): BridgeCreateTaskResult {
        createRequests += request
        return createTaskResult
    }

    override fun action(taskId: String, action: TaskAction): Boolean {
        actions += taskId to action
        return actionResult
    }

    override fun onPairRequestChanged(request: PairRequest?) {
        pairRequests += request
    }

    override fun appVersion(): String = "4.4.3-test"

    override fun connectionChanged(connected: Boolean) {
        connectionStates += connected
    }

    override fun log(message: String) {
        logs += message
    }
}

private class TestClient(uri: URI) : WebSocketClient(uri) {

    val messages = LinkedBlockingQueue<String>()

    @Volatile
    var closed: Boolean = false
        private set

    override fun onOpen(handshakedata: ServerHandshake) {}

    override fun onMessage(message: String) {
        messages += message
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        closed = true
    }

    override fun onError(ex: Exception) {}

    fun sendJson(json: String) {
        send(json)
    }

    /** 等待第一条满足条件的 JSON 消息；超时后抛出带已收消息的诊断信息。 */
    fun awaitMessage(timeoutMs: Long = 5_000, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        val received = mutableListOf<String>()
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                throw AssertionError("等待消息超时（已收到 ${received.size} 条）：$received")
            }
            val raw = messages.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
            received += raw
            val parsed = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            if (parsed != null && predicate(parsed)) return parsed
        }
    }

    /** 在 [timeoutMs] 内不应再收到任何消息；收到则返回其原文。 */
    fun awaitNoMessage(timeoutMs: Long = 400): String? =
        messages.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun awaitClosed(timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (closed || readyState == ReadyState.CLOSED) return
            Thread.sleep(20)
        }
        throw AssertionError("连接未在 ${timeoutMs}ms 内关闭")
    }
}

// ---------------------------------------------------------------- 构造 / 断言工具

private fun helloJson(
    token: String,
    protocolVersion: Int = BridgeProtocol.VERSION,
    extensionVersion: String = "1.2.3",
    requestId: String? = null
): String = JsonObject().apply {
    addProperty("type", "hello")
    addProperty("protocolVersion", protocolVersion)
    addProperty("token", token)
    addProperty("extensionVersion", extensionVersion)
    addProperty("clientKind", "browser_extension")
    addProperty("installType", "normal")
    if (requestId != null) addProperty("requestId", requestId)
}.toString()

private fun pairRequestJson(requestId: String): String = JsonObject().apply {
    addProperty("type", "pair_request")
    addProperty("requestId", requestId)
    addProperty("protocolVersion", BridgeProtocol.VERSION)
    addProperty("extensionVersion", "1.2.3")
    addProperty("clientKind", "browser_extension")
}.toString()

private fun subscribeJson(): String = JsonObject().apply {
    addProperty("type", "subscribe_tasks")
}.toString()

private fun createTaskJson(
    requestId: String,
    source: String,
    title: String,
    payload: JsonObject,
    draft: Boolean? = null
): String = JsonObject().apply {
    addProperty("type", "create_task")
    addProperty("requestId", requestId)
    addProperty("source", source)
    addProperty("title", title)
    if (draft != null) addProperty("draft", draft)
    add("payload", payload)
}.toString()

private fun taskActionJson(requestId: String, taskId: String, action: String): String =
    JsonObject().apply {
        addProperty("type", "task_action")
        addProperty("requestId", requestId)
        addProperty("taskId", taskId)
        addProperty("action", action)
    }.toString()

private fun taskSummary(
    taskId: String,
    name: String = "$taskId.bin",
    status: String = "waiting",
    progress: Double = 0.0,
    receivedBytes: Long = 0L,
    fileSize: Long = 0L,
    speed: Long = 0L,
    createdAt: Long = 0L,
    canPause: Boolean = false,
    canOpenFile: Boolean = false,
    canOpenFolder: Boolean = false,
    fileExt: String = "",
    packName: String = ""
): BridgeTaskSummary = BridgeTaskSummary(
    taskId = taskId,
    name = name,
    status = status,
    progress = progress,
    receivedBytes = receivedBytes,
    fileSize = fileSize,
    speed = speed,
    createdAt = createdAt,
    canPause = canPause,
    canOpenFile = canOpenFile,
    canOpenFolder = canOpenFolder,
    fileExt = fileExt,
    packName = packName
)

private fun JsonObject.stringValue(key: String): String {
    val element = get(key) ?: return ""
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else ""
}

private fun JsonObject.intValue(key: String): Int {
    val element = get(key) ?: return -1
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) element.asInt else -1
}

private fun JsonObject.booleanValue(key: String): Boolean {
    val element = get(key) ?: return false
    return element.isJsonPrimitive && element.asJsonPrimitive.isBoolean && element.asBoolean
}

private fun awaitCondition(
    description: String,
    timeoutMs: Long = 5_000,
    condition: () -> Boolean
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(20)
    }
    throw AssertionError("超时等待：$description")
}

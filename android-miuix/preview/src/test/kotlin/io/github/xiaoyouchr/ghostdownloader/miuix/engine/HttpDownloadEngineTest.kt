package io.github.xiaoyouchr.ghostdownloader.miuix.engine

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.random.Random

/**
 * HTTP 引擎集成测试：本地起可配置的 HTTP 服务器，覆盖开发者文档《下载引擎》《任务系统》的关键语义：
 * 两阶段 Range 探测、三种下载模式、运行时降级、暂停/续传（.ghd）、重启恢复自动入队、
 * 永久错误、重新下载、并发下调。
 */
class HttpDownloadEngineTest {

    private lateinit var workDir: File
    private lateinit var server: TestServer
    private lateinit var payload: ByteArray
    private var engine: HttpDownloadEngine? = null

    @Before
    fun setUp() {
        payload = Random(42).nextBytes(2 * 1024 * 1024 + 12345)
        server = TestServer(payload)
        workDir = Files.createTempDirectory("gd-engine-test").toFile()
    }

    @After
    fun tearDown() {
        engine?.close()
        engine = null
        server.close()
        workDir.deleteRecursively()
    }

    private fun newEngine(
        maxConcurrent: Int = 3,
        subworkerCount: Int = 8
    ): HttpDownloadEngine {
        val created = HttpDownloadEngine(
            dirs = AppDirs(
                downloadDir = File(workDir, "downloads"),
                dataDir = File(workDir, "data")
            ),
            config = EngineConfig(
                retryDelayMs = 100L,
                readTimeoutSeconds = 5L,
                sampleIntervalMs = 100L,
                maxReassignSizeBytes = 64L * 1024L,
                speedUpEnabled = false,
                stallTimeoutMs = 5_000L
            )
        )
        created.maxConcurrent = maxConcurrent
        created.subworkerCount = subworkerCount
        engine = created
        return created
    }

    private fun awaitStatus(id: Long, status: EngineStatus, timeoutMs: Long = 30_000): TaskSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        val engine = engine ?: error("engine not created")
        while (System.currentTimeMillis() < deadline) {
            val snapshot = engine.snapshots().first { it.id == id }
            if (snapshot.status == EngineStatus.FAILED) {
                throw AssertionError("task failed: ${snapshot.error}")
            }
            if (snapshot.status == status) return snapshot
            Thread.sleep(25)
        }
        throw AssertionError("timeout waiting for $status")
    }

    private fun addTask(engine: HttpDownloadEngine, name: String = "file.bin") =
        engine.addTask(
            url = server.url,
            folder = File(workDir, "downloads").absolutePath,
            preferredName = name,
            totalBytes = server.payloadSize,
            canUseRange = server.rangeSupported
        )

    // ---------------------------------------------------------------- 探测

    @Test
    fun probeUsesOffsetOneThenFallsBackToZero() {
        server.behavior = RangeBehavior.OFFSET_ONLY
        val engine = newEngine()
        val probe = engine.probe(server.url)
        assertTrue("fallback probe should detect range support", probe.canUseRange)
        assertEquals(payload.size.toLong(), probe.totalBytes)
        assertTrue("first probe should be bytes=1-1", server.requests[0].contains("bytes=1-1"))
        assertTrue("fallback probe should be bytes=0-0", server.requests.any { it.contains("bytes=0-0") })
    }

    @Test
    fun probeReportsSingleStreamWhenRangeIgnored() {
        server.behavior = RangeBehavior.IGNORED
        val engine = newEngine()
        val probe = engine.probe(server.url)
        assertFalse(probe.canUseRange)
        assertEquals(payload.size.toLong(), probe.totalBytes)
    }

    @Test
    fun probeReportsUnknownSizeForChunkedResponse() {
        server.behavior = RangeBehavior.IGNORED_NO_LENGTH
        val engine = newEngine()
        val probe = engine.probe(server.url)
        assertFalse(probe.canUseRange)
        assertEquals(0L, probe.totalBytes)
    }

    // ---------------------------------------------------------------- 下载

    @Test
    fun downloadsFileInChunksAndCleansProgressFile() {
        server.behavior = RangeBehavior.SUPPORTED
        val engine = newEngine()
        val task = addTask(engine)
        val completed = awaitStatus(task.id, EngineStatus.COMPLETED)

        val output = File(completed.outputPath)
        assertTrue("output should exist", output.exists())
        assertArrayEquals(payload, output.readBytes())
        assertEquals(payload.size.toLong(), completed.downloadedBytes)
        assertFalse("progress file should be removed", File(completed.progressPath).exists())
        assertTrue("should have issued multiple range requests", server.requests.count { it.contains("bytes=") } >= 4)
    }

    @Test
    fun runtimeDowngradesToSingleStreamWhenServerReturns200() {
        server.behavior = RangeBehavior.DOWNGRADE_AFTER
        server.downgradeAfterRequests = 3
        server.chunkDelayMs = 5L
        val engine = newEngine()
        val task = addTask(engine)
        val completed = awaitStatus(task.id, EngineStatus.COMPLETED)

        assertArrayEquals(payload, File(completed.outputPath).readBytes())
        assertEquals(DownloadMode.STREAM, completed.mode)
        assertFalse(completed.canUseRange)
    }

    @Test
    fun singleStreamDownloadWorksWhenRangeIgnored() {
        server.behavior = RangeBehavior.IGNORED
        val engine = newEngine()
        val task = addTask(engine)
        val completed = awaitStatus(task.id, EngineStatus.COMPLETED)
        assertArrayEquals(payload, File(completed.outputPath).readBytes())
        assertEquals(DownloadMode.STREAM, completed.mode)
    }

    @Test
    fun unknownSizeDownloadWorks() {
        server.behavior = RangeBehavior.IGNORED_NO_LENGTH
        val engine = newEngine()
        val task = addTask(engine)
        val completed = awaitStatus(task.id, EngineStatus.COMPLETED)
        assertArrayEquals(payload, File(completed.outputPath).readBytes())
    }

    // ---------------------------------------------------------------- 暂停 / 续传

    @Test
    fun pauseWritesProgressRecordAndResumeCompletes() {
        server.behavior = RangeBehavior.SUPPORTED
        server.chunkDelayMs = 3L
        val engine = newEngine()
        val task = addTask(engine)

        val pauseThreshold = payload.size / 4
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (engine.snapshots().first().downloadedBytes >= pauseThreshold) break
            Thread.sleep(20)
        }
        engine.pause(task.id)
        val paused = awaitStatus(task.id, EngineStatus.PAUSED)

        val progressFile = File(paused.progressPath)
        assertTrue("progress file should exist after pause", progressFile.exists())
        val subworkers = TaskStore.readProgress(File(paused.outputPath))
        assertTrue("progress record should contain subworkers", subworkers.isNotEmpty())
        assertTrue("progress record should keep received bytes", subworkers.sumOf { it.receivedBytes } > 0L)

        val bytesAtPause = paused.downloadedBytes
        Thread.sleep(500)
        val stable = engine.snapshots().first()
        assertTrue("paused task should not keep downloading", stable.downloadedBytes <= bytesAtPause + 1024)

        engine.start(task.id)
        val completed = awaitStatus(task.id, EngineStatus.COMPLETED)
        assertArrayEquals(payload, File(completed.outputPath).readBytes())
    }

    // ---------------------------------------------------------------- 失败 / 重下 / 恢复 / 调度

    @Test
    fun permanentHttpErrorFailsTask() {
        server.behavior = RangeBehavior.FORBIDDEN_AFTER
        server.forbiddenAfterRequests = 2
        val engine = newEngine()
        val task = addTask(engine)
        val deadline = System.currentTimeMillis() + 20_000
        var failed: TaskSnapshot? = null
        while (System.currentTimeMillis() < deadline) {
            val snapshot = engine.snapshots().first()
            if (snapshot.status == EngineStatus.FAILED) {
                failed = snapshot
                break
            }
            Thread.sleep(25)
        }
        val snapshot = failed ?: throw AssertionError("task should fail on HTTP 403")
        assertTrue("error should mention 403: ${snapshot.error}", snapshot.error?.contains("403") == true)
    }

    @Test
    fun redownloadRestartsCompletedTask() {
        server.behavior = RangeBehavior.SUPPORTED
        val engine = newEngine()
        val task = addTask(engine)
        awaitStatus(task.id, EngineStatus.COMPLETED)
        Thread.sleep(50)
        engine.redownload(task.id)
        val completed = awaitStatus(task.id, EngineStatus.COMPLETED)
        assertArrayEquals(payload, File(completed.outputPath).readBytes())
    }

    @Test
    fun restoreAutoEnqueuesUnfinishedTask() {
        server.behavior = RangeBehavior.SUPPORTED
        val downloadDir = File(workDir, "downloads").apply { mkdirs() }
        val dataDir = File(workDir, "data").apply { mkdirs() }
        TaskStore(dataDir).save(
            listOf(
                PersistedTask(
                    id = 7L,
                    url = server.url,
                    name = "restored.bin",
                    folder = downloadDir.absolutePath,
                    status = EngineStatus.RUNNING,
                    totalBytes = payload.size.toLong(),
                    canUseRange = true,
                    error = null,
                    createdAt = System.currentTimeMillis(),
                    completedAt = null,
                    downloadedBytes = 0L,
                    subworkerCount = 8
                )
            )
        )

        val engine = HttpDownloadEngine(
            dirs = AppDirs(downloadDir = downloadDir, dataDir = dataDir),
            config = EngineConfig(
                retryDelayMs = 100L,
                readTimeoutSeconds = 5L,
                sampleIntervalMs = 100L,
                speedUpEnabled = false
            )
        )
        this.engine = engine
        val completed = awaitStatus(7L, EngineStatus.COMPLETED)
        assertArrayEquals(payload, File(completed.outputPath).readBytes())
    }

    @Test
    fun loweringConcurrencyCancelsExcessTaskBackToWaiting() {
        server.behavior = RangeBehavior.SUPPORTED
        server.chunkDelayMs = 80L
        val engine = newEngine(maxConcurrent = 2, subworkerCount = 2)
        addTask(engine, "a.bin")
        addTask(engine, "b.bin")
        Thread.sleep(250)

        engine.maxConcurrent = 1
        Thread.sleep(150)
        val snapshots = engine.snapshots()
        val running = snapshots.count { it.status == EngineStatus.RUNNING }
        val waiting = snapshots.count { it.status == EngineStatus.WAITING }
        assertEquals("only one task may keep running", 1, running)
        assertEquals("excess task should go back to waiting queue", 1, waiting)

        // 收尾：恢复并发并暂停全部，避免测试退出时仍在下载
        engine.maxConcurrent = 3
        engine.pauseAll()
    }

    /** 可配置的本地 HTTP 服务器（Range / 单流 / 降级 / 永久错误）。 */
    private enum class RangeBehavior {
        SUPPORTED,
        IGNORED,
        IGNORED_NO_LENGTH,
        OFFSET_ONLY,
        DOWNGRADE_AFTER,
        FORBIDDEN_AFTER
    }

    private class TestServer(private val payload: ByteArray) : AutoCloseable {

        val requests = CopyOnWriteArrayList<String>()
        val payloadSize: Long get() = payload.size.toLong()
        val rangeSupported: Boolean get() = behavior == RangeBehavior.SUPPORTED || behavior == RangeBehavior.DOWNGRADE_AFTER || behavior == RangeBehavior.FORBIDDEN_AFTER

        @Volatile
        var behavior: RangeBehavior = RangeBehavior.SUPPORTED

        @Volatile
        var downgradeAfterRequests: Int = 2

        @Volatile
        var forbiddenAfterRequests: Int = 2

        @Volatile
        var chunkDelayMs: Long = 0L

        private val executor = Executors.newFixedThreadPool(32)
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/file.bin") { exchange ->
                try {
                    val range = exchange.requestHeaders.getFirst("Range")
                    val isGet = exchange.requestMethod.equals("GET", ignoreCase = true)
                    if (isGet) {
                        requests += "GET ${range ?: "-"}"
                    } else {
                        requests += "${exchange.requestMethod} -"
                    }
                    val rangeCount = requests.count { it.startsWith("GET ") && it.contains("bytes=") }

                    if (!isGet) {
                        exchange.responseHeaders.add("Content-Length", payload.size.toString())
                        exchange.responseHeaders.add("Accept-Ranges", "bytes")
                        exchange.sendResponseHeaders(200, -1)
                        return@createContext
                    }

                    when (behavior) {
                        RangeBehavior.SUPPORTED -> respondRange(exchange, range)

                        RangeBehavior.IGNORED -> respondFull(exchange, includeLength = true)

                        RangeBehavior.IGNORED_NO_LENGTH -> respondFull(exchange, includeLength = false)

                        RangeBehavior.OFFSET_ONLY -> {
                            if (range == "bytes=1-1") respondFull(exchange, includeLength = false)
                            else respondRange(exchange, range)
                        }

                        RangeBehavior.DOWNGRADE_AFTER -> {
                            if (rangeCount > downgradeAfterRequests) respondFull(exchange, includeLength = true)
                            else respondRange(exchange, range)
                        }

                        RangeBehavior.FORBIDDEN_AFTER -> {
                            if (rangeCount > forbiddenAfterRequests) {
                                exchange.sendResponseHeaders(403, -1)
                            } else {
                                respondRange(exchange, range)
                            }
                        }
                    }
                } catch (_: Exception) {
                    runCatching { exchange.sendResponseHeaders(500, -1) }
                } finally {
                    exchange.close()
                }
            }
            server.executor = executor
            server.start()
        }

        val url: String
            get() = "http://127.0.0.1:${server.address.port}/file.bin"

        private fun respondRange(exchange: com.sun.net.httpserver.HttpExchange, range: String?) {
            if (range == null) {
                respondFull(exchange, includeLength = true)
                return
            }
            val spec = range.removePrefix("bytes=")
            val from = spec.substringBefore('-').toLongOrNull() ?: 0L
            val toPart = spec.substringAfter('-', "")
            val to = toPart.toLongOrNull() ?: (payload.size - 1).toLong()
            if (from >= payload.size) {
                exchange.responseHeaders.add("Content-Range", "bytes */${payload.size}")
                exchange.sendResponseHeaders(416, -1)
                return
            }
            val end = min(to, payload.size - 1L)
            val length = (end - from + 1).toInt()
            exchange.responseHeaders.add("Content-Range", "bytes $from-$end/${payload.size}")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.sendResponseHeaders(206, length.toLong())
            writeBody(exchange, from.toInt(), length)
        }

        private fun respondFull(exchange: com.sun.net.httpserver.HttpExchange, includeLength: Boolean) {
            exchange.responseHeaders.add("Accept-Ranges", if (includeLength) "bytes" else "none")
            if (includeLength) {
                exchange.sendResponseHeaders(200, payload.size.toLong())
            } else {
                exchange.sendResponseHeaders(200, 0L) // chunked
            }
            writeBody(exchange, 0, payload.size)
        }

        private fun writeBody(exchange: com.sun.net.httpserver.HttpExchange, offset: Int, length: Int) {
            val output = exchange.responseBody
            val bufferSize = 64 * 1024
            var position = offset
            var remaining = length
            while (remaining > 0) {
                val chunk = min(bufferSize, remaining)
                output.write(payload, position, chunk)
                position += chunk
                remaining -= chunk
                if (chunkDelayMs > 0L) Thread.sleep(chunkDelayMs)
            }
            output.flush()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}

package io.github.xiaoyouchr.ghostdownloader.miuix.engine

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * 引擎集成测试：本地起一个支持 Range 的 HTTP 服务器，覆盖
 * 多线程分块下载、暂停/继续、任务记录持久化、非法链接。
 */
class HttpDownloadEngineTest {

    private lateinit var workDir: File
    private lateinit var server: RangeServer
    private lateinit var payload: ByteArray
    private var engine: HttpDownloadEngine? = null

    @Before
    fun setUp() {
        payload = Random(42).nextBytes(3 * 1024 * 1024 + 12345)
        server = RangeServer(payload)
        workDir = Files.createTempDirectory("gd-engine-test").toFile()
    }

    @After
    fun tearDown() {
        engine?.close()
        engine = null
        server.close()
        workDir.deleteRecursively()
    }

    private fun newEngine(): HttpDownloadEngine {
        val created = HttpDownloadEngine(
            AppDirs(
                downloadDir = File(workDir, "downloads"),
                dataDir = File(workDir, "data")
            )
        )
        created.maxConcurrent = 2
        created.connectionsPerTask = 4
        engine = created
        return created
    }

    private fun awaitStatus(id: Long, status: EngineStatus, timeoutMs: Long = 60_000): TaskSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snapshot = newEngineHandle().snapshots().first { it.id == id }
            if (snapshot.status == EngineStatus.FAILED) {
                throw AssertionError("task failed: ${snapshot.error}")
            }
            if (snapshot.status == status) return snapshot
            Thread.sleep(50)
        }
        throw AssertionError("timeout waiting for $status")
    }

    private fun newEngineHandle(): HttpDownloadEngine =
        engine ?: error("engine not created")

    @Test
    fun probesUrlAndDownloadsInChunks() {
        val engine = newEngine()
        val probe = engine.probe(server.url)
        assertEquals(payload.size.toLong(), probe.totalBytes)
        assertTrue("server should support range requests", probe.resumable)
        assertNotNull(probe.fileName)

        val snapshot = engine.addTask(
            url = probe.url,
            folder = File(workDir, "downloads").absolutePath,
            preferredName = probe.fileName,
            totalBytes = probe.totalBytes,
            resumable = probe.resumable
        )
        engine.start(snapshot.id)
        val completed = awaitStatus(snapshot.id, EngineStatus.COMPLETED)

        val output = File(completed.outputPath!!)
        assertTrue("output file should exist", output.exists())
        assertArrayEquals("downloaded bytes differ", payload, output.readBytes())
        assertEquals(payload.size.toLong(), completed.downloadedBytes)
        assertTrue("completed task should not keep part path", completed.partPath == null)
    }

    @Test
    fun pauseThenResumeKeepsProgress() {
        val engine = newEngine()
        val probe = engine.probe(server.url)
        val snapshot = engine.addTask(
            url = probe.url,
            folder = File(workDir, "downloads").absolutePath,
            preferredName = probe.fileName,
            totalBytes = probe.totalBytes,
            resumable = probe.resumable
        )
        engine.start(snapshot.id)

        // 等到下载了至少 1/4 再暂停
        val pauseThreshold = payload.size / 4
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val current = engine.snapshots().first { it.id == snapshot.id }
            if (current.downloadedBytes >= pauseThreshold) break
            Thread.sleep(20)
        }
        engine.pause(snapshot.id)
        awaitStatus(snapshot.id, EngineStatus.PAUSED)

        // 取消是异步的：等待进度连续稳定（3 个采样点不增长）后再断言
        var lastBytes = -1L
        var stableSamples = 0
        val settleDeadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < settleDeadline) {
            val current = engine.snapshots().first { it.id == snapshot.id }.downloadedBytes
            if (current == lastBytes) stableSamples++ else stableSamples = 0
            lastBytes = current
            if (stableSamples >= 3) break
            Thread.sleep(100)
        }
        assertTrue("paused task should keep some bytes", lastBytes > 0L)
        val bytesAtPause = lastBytes

        Thread.sleep(800)
        val stillPaused = engine.snapshots().first { it.id == snapshot.id }
        assertEquals("paused task should not grow", bytesAtPause, stillPaused.downloadedBytes)

        engine.start(snapshot.id)
        val completed = awaitStatus(snapshot.id, EngineStatus.COMPLETED)
        assertArrayEquals(payload, File(completed.outputPath!!).readBytes())
    }

    @Test
    fun persistsTaskRecordsAcrossEngineRestart() {
        val engine = newEngine()
        val probe = engine.probe(server.url)
        val snapshot = engine.addTask(
            url = probe.url,
            folder = File(workDir, "downloads").absolutePath,
            preferredName = probe.fileName,
            totalBytes = probe.totalBytes,
            resumable = probe.resumable
        )
        engine.pause(snapshot.id)
        engine.close()
        this.engine = null

        val restarted = newEngine()
        val restored = restarted.snapshots().single()
        assertEquals(snapshot.name, restored.name)
        assertEquals(probe.totalBytes, restored.totalBytes)
        assertEquals(EngineStatus.PAUSED, restored.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpScheme() {
        newEngine().probe("magnet:?xt=urn:btih:deadbeef")
    }

    /** 支持 HEAD + Range 的本地文件服务器。 */
    private class RangeServer(private val payload: ByteArray) : AutoCloseable {

        private val executor = Executors.newFixedThreadPool(8)
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/file.bin") { exchange ->
                try {
                    val range = exchange.requestHeaders.getFirst("Range")
                    when {
                        exchange.requestMethod.equals("HEAD", ignoreCase = true) -> {
                            exchange.responseHeaders.add("Content-Length", payload.size.toString())
                            exchange.responseHeaders.add("Accept-Ranges", "bytes")
                            exchange.sendResponseHeaders(200, -1)
                        }

                        range.isNullOrBlank() -> {
                            exchange.responseHeaders.add("Content-Length", payload.size.toString())
                            exchange.responseHeaders.add("Accept-Ranges", "bytes")
                            exchange.sendResponseHeaders(200, payload.size.toLong())
                            exchange.responseBody.write(payload)
                        }

                        else -> {
                            val spec = range.removePrefix("bytes=")
                            val start = spec.substringBefore('-').toInt().coerceAtLeast(0)
                            val end = spec.substringAfter('-').toInt().coerceAtMost(payload.size - 1)
                            val length = end - start + 1
                            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                            exchange.responseHeaders.add("Accept-Ranges", "bytes")
                            exchange.sendResponseHeaders(206, length.toLong())
                            exchange.responseBody.write(payload, start, length)
                        }
                    }
                } finally {
                    exchange.close()
                }
            }
            server.executor = executor
            server.start()
        }

        val url: String
            get() = "http://127.0.0.1:${server.address.port}/file.bin"

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}

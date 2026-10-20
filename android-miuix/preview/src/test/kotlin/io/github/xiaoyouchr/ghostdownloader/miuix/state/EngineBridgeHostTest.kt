package io.github.xiaoyouchr.ghostdownloader.miuix.state

import io.github.xiaoyouchr.ghostdownloader.miuix.bridge.BridgeCreateTaskRequest
import io.github.xiaoyouchr.ghostdownloader.miuix.bridge.CreateTaskStatus
import io.github.xiaoyouchr.ghostdownloader.miuix.bridge.TaskAction
import io.github.xiaoyouchr.ghostdownloader.miuix.bridge.TaskSource
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.AppDirs
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.HttpDownloadEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** [EngineBridgeHost] 与下载引擎 / 分类规则的集成测试（不依赖网络）。 */
class EngineBridgeHostTest {

    private val workDir: File = Files.createTempDirectory("gd-bridge-host").toFile()
    private var engine: HttpDownloadEngine? = null

    @After
    fun tearDown() {
        engine?.close()
        engine = null
        workDir.deleteRecursively()
    }

    private fun newHost(settings: AppSettings = AppSettings()): EngineBridgeHost {
        val downloadEngine = HttpDownloadEngine(
            AppDirs(
                downloadDir = File(workDir, "downloads"),
                dataDir = File(workDir, "data")
            )
        )
        engine = downloadEngine
        settings.enableCategory = true
        return EngineBridgeHost(
            engine = downloadEngine,
            settings = settings,
            platformFiles = null,
            appVersionProvider = { "4.4.3" },
            tokenProvider = { "test-token" },
            tokenUpdater = { it },
            pairRequestListener = { },
            connectionListener = { }
        )
    }

    @Test
    fun createsTaskInCategoryFolderAndMapsSummary() {
        val host = newHost()
        val result = host.createTask(
            BridgeCreateTaskRequest(
                source = TaskSource.DOWNLOAD,
                url = "https://example.com/movie.mp4",
                fileName = "movie.mp4",
                fileSize = 0L,
                canUseRange = false
            )
        )
        assertEquals(CreateTaskStatus.CREATED, result.status)

        val snapshot = engine!!.snapshots().single()
        assertTrue("category folder expected: ${snapshot.folder}", snapshot.folder.replace('\\', '/').endsWith("/视频"))
        assertEquals("movie.mp4", snapshot.name)

        val summary = host.tasks().single()
        assertEquals(result.taskId, summary.taskId)
        assertEquals("movie.mp4", summary.name)
        assertTrue(summary.status in setOf("waiting", "running", "paused", "failed"))
        assertEquals(0.0, summary.progress, 0.001)
        assertTrue(summary.canPause.not())

        assertTrue(host.action(result.taskId, TaskAction.CANCEL))
        assertTrue(host.tasks().isEmpty())
    }

    @Test
    fun mapsTaskActionAndRejectsUnsupportedSources() {
        val host = newHost()
        val merge = host.createTask(
            BridgeCreateTaskRequest(
                source = TaskSource.RESOURCE_MERGE,
                resources = emptyList()
            )
        )
        assertEquals(CreateTaskStatus.REJECTED, merge.status)

        val created = host.createTask(
            BridgeCreateTaskRequest(
                source = TaskSource.RESOURCE,
                url = "https://example.com/archive.zip",
                fileName = "archive.zip"
            )
        )
        assertEquals(CreateTaskStatus.CREATED, created.status)
        val id = created.taskId
        assertTrue(host.tasks().any { it.taskId == id })
        assertTrue(host.action(id, TaskAction.REMOVE))
        assertTrue(host.tasks().none { it.taskId == id })
    }

    @Test
    fun tokenStorePersistsAcrossInstances() {
        val file = File(workDir, "bridge-token.txt")
        val first = BridgeTokenStore(file)
        first.save("token-a")
        assertEquals("token-a", first.load())
        assertEquals("token-a", BridgeTokenStore(file).load())
        assertNotNull(first.load())
    }
}

package io.github.xiaoyouchr.ghostdownloader.miuix.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.DownloadMode
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.EngineStatus
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.HttpDownloadEngine
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.NameConflictPolicy
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.ProbeResult
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.TaskSnapshot
import io.github.xiaoyouchr.ghostdownloader.miuix.model.DownloadTask
import io.github.xiaoyouchr.ghostdownloader.miuix.model.MockTasks
import io.github.xiaoyouchr.ghostdownloader.miuix.model.TaskStatus
import io.github.xiaoyouchr.ghostdownloader.miuix.platform.PlatformFiles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class TaskFilter(val label: String) {
    ALL("全部"),
    UNFINISHED("未完成"),
    COMPLETED("已完成")
}

enum class AppTab(val label: String) {
    TASKS("任务"),
    SETTINGS("设置")
}

/**
 * 应用状态。
 *
 * - 真实模式（[engine] 非空）：任务来自 [HttpDownloadEngine]，UI 每 500ms 调 [onTick] 拉取快照并同步设置；
 * - 演示模式（[demo] = true）：使用 [MockTasks] 模拟数据，仅用于截图 / 无引擎预览。
 */
class AppState(
    val engine: HttpDownloadEngine? = null,
    val platformFiles: PlatformFiles? = null,
    val demo: Boolean = engine == null
) {
    val settings = AppSettings()

    var currentTab by mutableStateOf(AppTab.TASKS)
        private set

    var taskFilter by mutableStateOf(TaskFilter.ALL)
        private set

    var searchQuery by mutableStateOf("")
        private set

    var searchVisible by mutableStateOf(false)
        private set

    var showAddTaskSheet by mutableStateOf(false)
        private set

    var showAboutDialog by mutableStateOf(false)
        private set

    // 新建任务（Task Draft）状态
    var draftUrl by mutableStateOf("")
    var draftName by mutableStateOf("")
    var draftFolder by mutableStateOf("")
    var draftCategory by mutableStateOf<String?>(null)
    var draftParsed by mutableStateOf(false)
        private set
    var draftProbe by mutableStateOf<ProbeResult?>(null)
        private set
    var draftError by mutableStateOf<String?>(null)
        private set
    var draftBusy by mutableStateOf(false)
        private set

    val tasks = mutableStateListOf<DownloadTask>()

    init {
        engine?.let {
            settings.downloadFolder = it.defaultFolder()
            settings.maxTaskNum = it.maxConcurrent
            settings.preBlockNum = it.subworkerCount
            refreshFromEngine()
        }
        if (demo) tasks.addAll(MockTasks.tasks)
        if (draftFolder.isBlank()) draftFolder = settings.downloadFolder
    }

    /** Compose 定时调用：真实模式拉取引擎快照并同步设置，演示模式推进模拟进度。 */
    fun onTick() {
        if (demo) {
            advanceDemo(TICK_SECONDS)
            return
        }
        engine?.let {
            if (it.maxConcurrent != settings.maxTaskNum) it.maxConcurrent = settings.maxTaskNum
            val subworkers = settings.preBlockNum.coerceIn(1, SUBWORKER_LIMIT)
            if (it.subworkerCount != subworkers) it.subworkerCount = subworkers
            if (it.autoSpeedUp != settings.autoSpeedUp) it.autoSpeedUp = settings.autoSpeedUp
            if (it.preserveLastModified != settings.preserveLastModified) {
                it.preserveLastModified = settings.preserveLastModified
            }
        }
        refreshFromEngine()
    }

    // ------------------------------------------------------------------ 导航

    fun selectTab(tab: AppTab) {
        currentTab = tab
    }

    fun selectFilter(filter: TaskFilter) {
        taskFilter = filter
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
    }

    fun toggleSearch() {
        searchVisible = !searchVisible
        if (!searchVisible) searchQuery = ""
    }

    fun openAddTask() {
        draftUrl = ""
        draftName = ""
        draftFolder = settings.downloadFolder
        draftCategory = null
        draftParsed = false
        draftProbe = null
        draftError = null
        draftBusy = false
        showAddTaskSheet = true
    }

    fun closeAddTask() {
        showAddTaskSheet = false
    }

    fun openAbout() {
        showAboutDialog = true
    }

    fun closeAbout() {
        showAboutDialog = false
    }

    // -------------------------------------------------------------- 新建任务

    /** 解析用户粘贴的链接（真实引擎做两阶段 Range 探测）。 */
    suspend fun parseDraft() {
        val url = draftUrl.trim()
        if (url.isBlank()) {
            draftError = "请先粘贴下载链接"
            return
        }
        val engine = engine
        if (engine == null) {
            parseDraftDemo(url)
            return
        }
        draftBusy = true
        draftError = null
        try {
            val result = withContext(Dispatchers.IO) { engine.probe(url) }
            draftProbe = result
            draftName = result.fileName
            draftCategory = categoryFor(result.fileName)
            draftParsed = true
        } catch (error: Throwable) {
            draftParsed = false
            draftProbe = null
            draftError = error.message ?: "链接解析失败"
        } finally {
            draftBusy = false
        }
    }

    /** 确认下载：创建 Task（引擎会立即入队调度）。 */
    fun confirmDraft() {
        val engine = engine
        val probe = draftProbe
        if (engine == null || probe == null) {
            confirmDraftDemo()
            return
        }
        val folder = draftFolder.ifBlank { settings.downloadFolder }
        runCatching {
            engine.addTask(
                url = probe.url,
                folder = folder,
                preferredName = draftName.ifBlank { probe.fileName },
                totalBytes = probe.totalBytes,
                canUseRange = probe.canUseRange,
                policy = if (settings.nameConflict == NameConflictOption.OVERWRITE) {
                    NameConflictPolicy.OVERWRITE
                } else {
                    NameConflictPolicy.KEEP_BOTH
                }
            )
            showAddTaskSheet = false
            refreshFromEngine()
        }.onFailure { error ->
            draftError = error.message ?: "创建任务失败"
        }
    }

    private fun parseDraftDemo(url: String) {
        val fileName = url.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" }
        draftName = fileName
        draftCategory = categoryFor(fileName)
        draftParsed = true
        draftProbe = ProbeResult(url, fileName, 0L, canUseRange = false, contentType = null, lastModified = null)
    }

    // -------------------------------------------------------------- 任务操作

    fun toggleTask(id: Long) {
        if (demo) {
            toggleDemoTask(id)
            return
        }
        val task = tasks.firstOrNull { it.id == id } ?: return
        when (task.status) {
            TaskStatus.RUNNING, TaskStatus.WAITING -> engine?.pause(id)
            TaskStatus.PAUSED, TaskStatus.FAILED -> engine?.start(id)
            TaskStatus.COMPLETED -> engine?.redownload(id)
        }
        refreshFromEngine()
    }

    /** 重新下载（已完成任务）：删除成品与进度文件后重新开始。 */
    fun redownloadTask(id: Long) {
        if (demo) {
            val index = tasks.indexOfFirst { it.id == id }
            if (index >= 0) {
                tasks[index] = tasks[index].copy(
                    status = TaskStatus.RUNNING,
                    downloadedBytes = 0L,
                    error = null
                )
            }
            return
        }
        engine?.redownload(id)
        refreshFromEngine()
    }

    fun retryTask(id: Long) {
        if (demo) {
            val index = tasks.indexOfFirst { it.id == id }
            if (index >= 0) {
                tasks[index] = tasks[index].copy(status = TaskStatus.WAITING, error = null, downloadedBytes = 0L)
            }
            return
        }
        engine?.start(id)
        refreshFromEngine()
    }

    /** 删除任务，保留已下载的成品文件。 */
    fun removeTask(id: Long) {
        if (demo) {
            tasks.removeAll { it.id == id }
            return
        }
        engine?.remove(id, deleteFiles = false)
        refreshFromEngine()
    }

    /** 删除任务，并删除已下载的成品文件。 */
    fun removeTaskWithFiles(id: Long) {
        if (demo) {
            tasks.removeAll { it.id == id }
            return
        }
        engine?.remove(id, deleteFiles = true)
        refreshFromEngine()
    }

    fun pauseAll() {
        if (demo) {
            for (index in tasks.indices) {
                val task = tasks[index]
                if (task.status == TaskStatus.RUNNING) {
                    tasks[index] = task.copy(status = TaskStatus.PAUSED, speedBytes = 0L, uploadSpeedBytes = 0L)
                }
            }
            return
        }
        engine?.pauseAll()
        refreshFromEngine()
    }

    fun startAll() {
        if (demo) {
            for (index in tasks.indices) {
                val task = tasks[index]
                if (task.status == TaskStatus.PAUSED || task.status == TaskStatus.FAILED) {
                    tasks[index] = task.copy(
                        status = TaskStatus.RUNNING,
                        speedBytes = Random.nextLong(1L * 1024 * 1024, 12L * 1024 * 1024)
                    )
                }
            }
            return
        }
        engine?.startAll()
        refreshFromEngine()
    }

    fun openFile(task: DownloadTask): Boolean {
        val path = task.outputPath ?: return false
        return platformFiles?.openFile(path) ?: false
    }

    fun openFolder(task: DownloadTask): Boolean {
        return platformFiles?.openFolder(task.folder) ?: false
    }

    // ---------------------------------------------------------------- 查询

    val totalSpeed: Long
        get() = tasks.filter { it.status == TaskStatus.RUNNING }.sumOf { it.speedBytes }

    /** 未完成：等待 / 下载中 / 暂停 / 失败（失败仍需用户处理）。 */
    val unfinishedTasks: List<DownloadTask>
        get() = tasks.filter { it.status != TaskStatus.COMPLETED }

    /** 已完成：只有真正下载完成的 Task。 */
    val completedTasks: List<DownloadTask>
        get() = tasks.filter { it.status == TaskStatus.COMPLETED }

    fun visibleTasks(filter: TaskFilter): List<DownloadTask> {
        if (searchQuery.isNotBlank()) {
            return tasks.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        return when (filter) {
            TaskFilter.ALL -> tasks
            TaskFilter.UNFINISHED -> unfinishedTasks
            TaskFilter.COMPLETED -> completedTasks
        }
    }

    // -------------------------------------------------------------- 内部实现

    private fun refreshFromEngine() {
        val snapshots = engine?.snapshots() ?: return
        tasks.clear()
        snapshots.forEach { tasks.add(it.toDownloadTask()) }
    }

    private fun TaskSnapshot.toDownloadTask() = DownloadTask(
        id = id,
        name = name,
        folder = folder,
        category = null,
        status = when (status) {
            EngineStatus.WAITING -> TaskStatus.WAITING
            EngineStatus.RUNNING -> TaskStatus.RUNNING
            EngineStatus.PAUSED -> TaskStatus.PAUSED
            EngineStatus.COMPLETED -> TaskStatus.COMPLETED
            EngineStatus.FAILED -> TaskStatus.FAILED
        },
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        speedBytes = speedBytes,
        connections = subworkers,
        maxConnections = maxSubworkers,
        isTorrent = false,
        error = error,
        completedAt = completedAt?.let(::formatCompletedAt),
        fileCount = 1,
        url = url,
        outputPath = outputPath,
        progressPath = progressPath,
        modeLabel = mode.label,
        detail = detail
    )

    private fun formatCompletedAt(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

    private fun categoryFor(fileName: String): String? = when {
        fileName.endsWith(".apk", true) || fileName.endsWith(".exe", true) || fileName.endsWith(".dmg", true) -> "软件"
        fileName.endsWith(".iso", true) -> "镜像"
        fileName.endsWith(".zip", true) || fileName.endsWith(".7z", true) || fileName.endsWith(".tar", true) -> "压缩包"
        fileName.endsWith(".mp4", true) || fileName.endsWith(".mkv", true) || fileName.endsWith(".webm", true) -> "视频"
        fileName.endsWith(".mp3", true) || fileName.endsWith(".flac", true) -> "音频"
        else -> null
    }

    // ---------------------------------------------------------------- 演示模式

    private fun toggleDemoTask(id: Long) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return
        val task = tasks[index]
        tasks[index] = when (task.status) {
            TaskStatus.RUNNING -> task.copy(status = TaskStatus.PAUSED, speedBytes = 0L, uploadSpeedBytes = 0L)
            TaskStatus.PAUSED, TaskStatus.FAILED -> task.copy(
                status = TaskStatus.RUNNING,
                error = null,
                speedBytes = Random.nextLong(1L * 1024 * 1024, 12L * 1024 * 1024)
            )
            else -> task
        }
    }

    private fun confirmDraftDemo() {
        val name = draftName.ifBlank { "download.bin" }
        tasks.add(
            0,
            DownloadTask(
                id = 1000L + tasks.size,
                name = name,
                folder = draftFolder.ifBlank { settings.downloadFolder },
                category = draftCategory,
                status = TaskStatus.RUNNING,
                downloadedBytes = 0L,
                totalBytes = Random.nextLong(180L * 1024 * 1024, 2L * 1024 * 1024 * 1024),
                speedBytes = Random.nextLong(1L * 1024 * 1024, 18L * 1024 * 1024),
                connections = Random.nextInt(1, 9),
                maxConnections = 8,
                fileCount = 1,
                modeLabel = DownloadMode.RANGE.label
            )
        )
        showAddTaskSheet = false
    }

    private fun advanceDemo(deltaSeconds: Float) {
        val runningCount = tasks.count { it.status == TaskStatus.RUNNING }
        var slots = max(0, settings.maxTaskNum - runningCount)
        for (index in tasks.indices) {
            val task = tasks[index]
            when (task.status) {
                TaskStatus.RUNNING -> {
                    val maxConnections = task.maxConnections.coerceAtLeast(1)
                    val targetSpeed = maxConnections * Random.nextLong(450L * 1024, 1L * 1024 * 1024)
                    val drifted = task.speedBytes + (targetSpeed - task.speedBytes) / 6
                    val speed = drifted.coerceIn(80L * 1024, 32L * 1024 * 1024)
                    val downloaded = min(
                        task.totalBytes.coerceAtLeast(1L),
                        task.downloadedBytes + (speed * deltaSeconds).toLong()
                    )
                    val upload = if (task.isTorrent) speed / 24 else 0L
                    tasks[index] = task.copy(
                        speedBytes = speed,
                        uploadSpeedBytes = upload,
                        downloadedBytes = downloaded,
                        connections = Random.nextInt(1, maxConnections + 1),
                        shareRatio = if (task.isTorrent) max(0.0, task.shareRatio + deltaSeconds / 240.0) else 0.0
                    )
                }

                TaskStatus.WAITING -> if (slots > 0) {
                    slots--
                    tasks[index] = task.copy(
                        status = TaskStatus.RUNNING,
                        speedBytes = Random.nextLong(1L * 1024 * 1024, 6L * 1024 * 1024),
                        totalBytes = if (task.totalBytes > 0L) task.totalBytes else Random.nextLong(
                            120L * 1024 * 1024,
                            900L * 1024 * 1024
                        ),
                        maxConnections = if (task.maxConnections > 0) task.maxConnections else 8
                    )
                }

                else -> Unit
            }
        }
    }

    companion object {
        private const val TICK_SECONDS = 0.5f
        private const val SUBWORKER_LIMIT = 16
    }
}

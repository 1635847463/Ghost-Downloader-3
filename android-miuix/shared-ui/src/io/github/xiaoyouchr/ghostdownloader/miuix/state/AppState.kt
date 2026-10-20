package io.github.xiaoyouchr.ghostdownloader.miuix.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xiaoyouchr.ghostdownloader.miuix.model.DownloadTask
import io.github.xiaoyouchr.ghostdownloader.miuix.model.MockTasks
import io.github.xiaoyouchr.ghostdownloader.miuix.model.TaskStatus
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
 * 演示用应用状态。没有后端引擎，用定时 tick 模拟下载进度与速度波动，
 * 方便在 Desktop 上截图、在真机上演示 Miuix 观感。
 */
class AppState {
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

    var draftUrl by mutableStateOf("")
    var draftName by mutableStateOf("")
    var draftFolder by mutableStateOf(settings.downloadFolder)
    var draftCategory by mutableStateOf<String?>(null)
    var draftParsed by mutableStateOf(false)
        private set

    private var nextId = 100L

    val tasks = mutableStateListOf<DownloadTask>().apply { addAll(MockTasks.tasks) }

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

    fun parseDraft() {
        val url = draftUrl.trim()
        if (url.isEmpty()) return
        val fileName = url.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" }
        draftName = fileName
        draftCategory = when {
            fileName.endsWith(".apk") -> "软件"
            fileName.endsWith(".iso") -> "软件"
            fileName.endsWith(".zip") || fileName.endsWith(".7z") -> "压缩包"
            fileName.endsWith(".mp4") || fileName.endsWith(".mkv") -> "视频"
            else -> null
        }
        draftParsed = true
    }

    fun confirmDraft() {
        val name = draftName.ifBlank { "download.bin" }
        tasks.add(
            0,
            DownloadTask(
                id = nextId++,
                name = name,
                folder = draftFolder.ifBlank { settings.downloadFolder },
                category = draftCategory,
                status = TaskStatus.RUNNING,
                downloadedBytes = 0L,
                totalBytes = Random.nextLong(180L * 1024 * 1024, 2L * 1024 * 1024 * 1024),
                speedBytes = Random.nextLong(1L * 1024 * 1024, 18L * 1024 * 1024),
                connections = Random.nextInt(1, 9),
                maxConnections = 8,
                fileCount = 1
            )
        )
        showAddTaskSheet = false
    }

    fun toggleTask(id: Long) {
        updateTask(id) { task ->
            when (task.status) {
                TaskStatus.RUNNING -> task.copy(
                    status = TaskStatus.PAUSED,
                    speedBytes = 0L,
                    uploadSpeedBytes = 0L
                )

                TaskStatus.PAUSED, TaskStatus.FAILED -> task.copy(
                    status = TaskStatus.RUNNING,
                    error = null,
                    speedBytes = Random.nextLong(1L * 1024 * 1024, 12L * 1024 * 1024)
                )

                else -> task
            }
        }
    }

    fun removeTask(id: Long) {
        tasks.removeAll { it.id == id }
    }

    fun retryTask(id: Long) {
        updateTask(id) { task ->
            task.copy(status = TaskStatus.WAITING, error = null, downloadedBytes = 0L)
        }
    }

    fun pauseAll() {
        for (index in tasks.indices) {
            val task = tasks[index]
            if (task.status == TaskStatus.RUNNING) {
                tasks[index] = task.copy(status = TaskStatus.PAUSED, speedBytes = 0L, uploadSpeedBytes = 0L)
            }
        }
    }

    fun startAll() {
        for (index in tasks.indices) {
            val task = tasks[index]
            if (task.status == TaskStatus.PAUSED || task.status == TaskStatus.FAILED) {
                tasks[index] = task.copy(
                    status = TaskStatus.RUNNING,
                    speedBytes = Random.nextLong(1L * 1024 * 1024, 12L * 1024 * 1024)
                )
            }
        }
    }

    private fun updateTask(id: Long, transform: (DownloadTask) -> DownloadTask) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) tasks[index] = transform(tasks[index])
    }

    /** 定时调用：模拟速度波动、进度增长，以及等待任务在并发额度空出后自动开始。 */
    fun advance(deltaSeconds: Float) {
        val runningCount = tasks.count { it.status == TaskStatus.RUNNING }
        var slots = max(0, settings.maxTaskNum - runningCount)
        for (index in tasks.indices) {
            val task = tasks[index]
            when (task.status) {
                TaskStatus.RUNNING -> {
                    val maxConnections = task.maxConnections.coerceAtLeast(1)
                    val targetSpeed = maxConnections *
                        Random.nextLong(450L * 1024, 1L * 1024 * 1024)
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
}

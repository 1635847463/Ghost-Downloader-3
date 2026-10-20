package io.github.xiaoyouchr.ghostdownloader.miuix.model

/**
 * Task 的五种状态，与桌面端 CONTEXT.md 的术语保持一致：
 * WAITING / RUNNING / PAUSED / COMPLETED / FAILED。
 */
enum class TaskStatus(val label: String) {
    WAITING("等待中"),
    RUNNING("下载中"),
    PAUSED("已暂停"),
    COMPLETED("已完成"),
    FAILED("失败");

    val isFinished: Boolean
        get() = this == COMPLETED || this == FAILED
}

/** 下载任务。字段命名沿用项目术语：Name / Output Folder / Task Record / Side File。 */
data class DownloadTask(
    val id: Long,
    val name: String,
    val folder: String,
    val category: String? = null,
    val status: TaskStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytes: Long = 0L,
    val uploadSpeedBytes: Long = 0L,
    val connections: Int = 0,
    val maxConnections: Int = 0,
    val shareRatio: Double = 0.0,
    val fileCount: Int = 1,
    val isTorrent: Boolean = false,
    val error: String? = null,
    val completedAt: String? = null,
    val url: String = "",
    val outputPath: String? = null
) {
    val progress: Float
        get() = when {
            status == TaskStatus.COMPLETED -> 1f
            totalBytes <= 0L -> 0f
            else -> (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    val percent: Int
        get() = (progress * 100f).toInt().coerceIn(0, 100)

    val isActive: Boolean
        get() = status == TaskStatus.RUNNING
}

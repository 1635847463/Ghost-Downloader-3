package io.github.xiaoyouchr.ghostdownloader.miuix.engine

import java.io.File

/** 下载任务状态，与 UI 层的 [io.github.xiaoyouchr.ghostdownloader.miuix.model.TaskStatus] 一一对应。 */
enum class EngineStatus {
    WAITING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED
}

/** 链接探测结果：文件名、总大小、是否支持 Range 断点续传。 */
data class ProbeResult(
    val url: String,
    val fileName: String,
    val totalBytes: Long,
    val resumable: Boolean,
    val contentType: String?
)

/** 引擎对外暴露的不可变任务快照，UI 轮询它刷新界面。 */
data class TaskSnapshot(
    val id: Long,
    val url: String,
    val name: String,
    val folder: String,
    val status: EngineStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytes: Long,
    val connections: Int,
    val maxConnections: Int,
    val error: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val partPath: String?,
    val outputPath: String?,
    val resumable: Boolean
)

/** 同名文件处理策略。 */
enum class NameConflictPolicy {
    KEEP_BOTH,
    OVERWRITE
}

/** 应用目录：下载目录 + 数据目录（任务记录）。 */
data class AppDirs(
    val downloadDir: File,
    val dataDir: File
)

/** 分块状态。`end` 为开区间；`downloaded` 是当前块已落盘的字节数（用于断点续传）。 */
internal data class ChunkState(
    val start: Long,
    val end: Long,
    var downloaded: Long = 0L
) {
    val size: Long get() = end - start
    val isDone: Boolean get() = downloaded >= size
    fun nextOffset(): Long = start + downloaded
}

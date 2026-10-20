package io.github.xiaoyouchr.ghostdownloader.miuix.engine

import java.io.File

/** 下载任务状态，与 UI 层的 TaskStatus 一一对应。 */
enum class EngineStatus {
    WAITING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED
}

/** HTTP 下载模式，对应开发者文档《下载引擎》里的三种探测结果。 */
enum class DownloadMode(val label: String) {
    /** 206 + content-range：等分 N 段，多线程并行，支持断点续传。 */
    RANGE("分片下载"),

    /** 200 + content-length：单线程，重试时从头下载（截断文件）。 */
    STREAM("单线程"),

    /** 200 且无 content-length：单线程，重试时从当前位置继续。 */
    UNKNOWN("未知大小")
}

/** 与上游 `SpecialFileSize` 对齐的哨兵值（-1 = 不支持 Range，0 = 未知大小）。 */
object SpecialFileSize {
    const val NOT_SUPPORTED = -1L
    const val UNKNOWN = 0L
}

/** 链接探测结果。`totalBytes == 0` 表示大小未知。 */
data class ProbeResult(
    val url: String,
    val fileName: String,
    val totalBytes: Long,
    val canUseRange: Boolean,
    val contentType: String?,
    val lastModified: String?
)

/** 引擎对外暴露的不可变任务快照，UI 轮询它刷新界面。 */
data class TaskSnapshot(
    val id: Long,
    val url: String,
    val name: String,
    val folder: String,
    val category: String?,
    val status: EngineStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytes: Long,
    val subworkers: Int,
    val maxSubworkers: Int,
    val mode: DownloadMode,
    val detail: String?,
    val error: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val outputPath: String,
    val progressPath: String,
    val canUseRange: Boolean
)

/** 同名文件处理策略。 */
enum class NameConflictPolicy {
    KEEP_BOTH,
    OVERWRITE
}

/** 应用目录：下载目录 + 数据目录（任务列表 JSONL）。 */
data class AppDirs(
    val downloadDir: File,
    val dataDir: File
)

/** 引擎可调参数：测试可缩短间隔，生产用默认值（与上游一致）。 */
data class EngineConfig(
    val retryDelayMs: Long = 5_000L,
    val readTimeoutSeconds: Long = 30L,
    val sampleIntervalMs: Long = 1_000L,
    val maxReassignSizeBytes: Long = 512L * 1024L,
    val speedUpEnabled: Boolean = true,
    val stallTimeoutMs: Long = 120_000L
)

/**
 * 分片传输单元（上游术语 Subworker，避免 chunk / worker / thread）。
 *
 * [end] 为闭区间上界；特殊值见 [SpecialFileSize]。
 * `receivedBytes` 记录已经落盘的字节数，`position = start + receivedBytes` 是下一次请求的起点。
 */
internal data class Subworker(
    val index: Int,
    val start: Long,
    var end: Long,
    var receivedBytes: Long = 0L
) {
    val position: Long get() = start + receivedBytes

    val isDone: Boolean
        get() = when (end) {
            SpecialFileSize.NOT_SUPPORTED, SpecialFileSize.UNKNOWN -> false
            else -> position > end
        }

    /** 剩余字节数；特殊模式返回 [Long.MAX_VALUE]（由单流 worker 自行结束）。 */
    val remaining: Long
        get() = when (end) {
            SpecialFileSize.NOT_SUPPORTED, SpecialFileSize.UNKNOWN -> Long.MAX_VALUE
            else -> (end - position + 1).coerceAtLeast(0L)
        }

    fun cloneState(): Subworker = Subworker(index, start, end, receivedBytes)
}

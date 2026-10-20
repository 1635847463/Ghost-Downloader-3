package io.github.xiaoyouchr.ghostdownloader.miuix.engine

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 任务记录持久化：`任务记录.tsv`，一行一个 Task。
 *
 * 不引入 JSON 依赖：字段用制表符分隔，字符串字段用 URL 编码，分块状态用
 * `start,end,downloaded;...` 表示。文件损坏时忽略坏行，不阻塞启动。
 */
internal class TaskStore(dataDir: File) {

    private val file = File(dataDir, "tasks.tsv")

    fun load(): List<PersistedTask> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines(StandardCharsets.UTF_8)
                .filter { it.isNotBlank() }
                .mapNotNull { decodeLine(it) }
        }.getOrElse { emptyList() }
    }

    fun save(tasks: Collection<PersistedTask>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(tasks.joinToString("\n") { encodeLine(it) }, StandardCharsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private fun encodeLine(task: PersistedTask): String {
        val chunks = task.chunks.joinToString(";") { "${it.start},${it.end},${it.downloaded}" }
        return listOf(
            task.id.toString(),
            encode(task.url),
            encode(task.name),
            encode(task.folder),
            task.status.name,
            task.totalBytes.toString(),
            task.resumable.toString(),
            encode(task.error ?: ""),
            task.createdAt.toString(),
            (task.completedAt ?: 0L).toString(),
            task.downloadedBytes.toString(),
            task.maxConnections.toString(),
            chunks
        ).joinToString("\t")
    }

    private fun decodeLine(line: String): PersistedTask? {
        val parts = line.split("\t")
        if (parts.size < 13) return null
        return runCatching {
            val chunks = parts[12].split(";")
                .filter { it.isNotBlank() }
                .mapNotNull { chunk ->
                    val values = chunk.split(",")
                    if (values.size != 3) null
                    else ChunkState(
                        start = values[0].toLong(),
                        end = values[1].toLong(),
                        downloaded = values[2].toLong()
                    )
                }
            PersistedTask(
                id = parts[0].toLong(),
                url = decode(parts[1]),
                name = decode(parts[2]),
                folder = decode(parts[3]),
                status = runCatching { EngineStatus.valueOf(parts[4]) }.getOrDefault(EngineStatus.PAUSED),
                totalBytes = parts[5].toLong(),
                resumable = parts[6].toBoolean(),
                error = decode(parts[7]).ifBlank { null },
                createdAt = parts[8].toLong(),
                completedAt = parts[9].toLong().takeIf { it > 0L },
                downloadedBytes = parts[10].toLong(),
                maxConnections = parts[11].toIntOrNull()?.coerceIn(1, 32) ?: 8,
                chunks = chunks
            )
        }.getOrNull()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}

/** 持久化用的中间结构（与内存里的 TaskRecord 解耦）。 */
internal data class PersistedTask(
    val id: Long,
    val url: String,
    val name: String,
    val folder: String,
    val status: EngineStatus,
    val totalBytes: Long,
    val resumable: Boolean,
    val error: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val downloadedBytes: Long,
    val maxConnections: Int,
    val chunks: List<ChunkState>
)

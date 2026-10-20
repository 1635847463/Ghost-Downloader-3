package io.github.xiaoyouchr.ghostdownloader.miuix.engine

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * 任务持久化：
 *
 * - 任务列表：`tasks.jsonl`（每行一个 JSON 对象，对应上游的 JSONL 设计），写入走临时文件 + 原子重命名；
 * - 分片进度：`{outputPath}.ghd`，与上游一致的二进制格式 —— 每个分片 24 字节小端 `<QQQ>`：
 *   `start, position, end`（position = start + receivedBytes）。
 */
internal class TaskStore(dataDir: File) {

    private val file = File(dataDir, "tasks.jsonl")

    fun load(): List<PersistedTask> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines(StandardCharsets.UTF_8)
                .filter { it.isNotBlank() }
                .mapNotNull(::decodeLine)
        }.getOrElse { emptyList() }
    }

    fun save(tasks: Collection<PersistedTask>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            val text = tasks.joinToString("\n", postfix = if (tasks.isEmpty()) "" else "\n") { encodeLine(it) }
            tmp.writeText(text, StandardCharsets.UTF_8)
            atomicMove(tmp, file)
        }
    }

    private fun encodeLine(task: PersistedTask): String = buildString {
        append("{\"type\":\"HttpTask\"")
        append(",\"id\":").append(task.id)
        append(",\"url\":\"").append(b64(task.url)).append('"')
        append(",\"name\":\"").append(b64(task.name)).append('"')
        append(",\"folder\":\"").append(b64(task.folder)).append('"')
        append(",\"status\":\"").append(task.status.name).append('"')
        append(",\"totalBytes\":").append(task.totalBytes)
        append(",\"canUseRange\":").append(task.canUseRange)
        append(",\"error\":\"").append(b64(task.error ?: "")).append('"')
        append(",\"createdAt\":").append(task.createdAt)
        append(",\"completedAt\":").append(task.completedAt ?: 0L)
        append(",\"downloadedBytes\":").append(task.downloadedBytes)
        append(",\"subworkerCount\":").append(task.subworkerCount)
        append(",\"headers\":\"").append(b64(encodeHeaders(task.headers))).append('"')
        append(",\"category\":\"").append(b64(task.category ?: "")).append('"')
        append('}')
    }

    private fun decodeLine(line: String): PersistedTask? = runCatching {
        PersistedTask(
            id = number(line, "id") ?: return null,
            url = text(line, "url")?.let(::unb64) ?: return null,
            name = text(line, "name")?.let(::unb64) ?: return null,
            folder = text(line, "folder")?.let(::unb64) ?: return null,
            status = runCatching { EngineStatus.valueOf(text(line, "status") ?: "") }
                .getOrDefault(EngineStatus.PAUSED),
            totalBytes = number(line, "totalBytes") ?: 0L,
            canUseRange = bool(line, "canUseRange") ?: false,
            error = text(line, "error")?.let(::unb64)?.ifBlank { null },
            createdAt = number(line, "createdAt") ?: 0L,
            completedAt = number(line, "completedAt")?.takeIf { it > 0L },
            downloadedBytes = number(line, "downloadedBytes") ?: 0L,
            subworkerCount = (number(line, "subworkerCount") ?: 8L).toInt().coerceIn(1, 16),
            headers = text(line, "headers")?.let(::unb64)?.let(::decodeHeaders) ?: emptyMap(),
            category = text(line, "category")?.let(::unb64)?.ifBlank { null }
        )
    }.getOrNull()

    private fun encodeHeaders(headers: Map<String, String>): String =
        headers.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    private fun decodeHeaders(value: String): Map<String, String> = value.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val index = line.indexOf('\t')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
        }
        .toMap()

    private fun text(line: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(line)?.groupValues?.getOrNull(1)

    private fun number(line: String, key: String): Long? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun bool(line: String, key: String): Boolean? =
        Regex("\"$key\"\\s*:\\s*(true|false)").find(line)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()

    companion object {
        /** 分片进度文件后缀（上游：Part Path = `{最终路径}.ghd`）。 */
        const val PROGRESS_SUFFIX = ".ghd"

        fun progressFile(output: File): File = File(output.parentFile, output.name + PROGRESS_SUFFIX)

        /** 每行 24 字节小端 start/position/end。 */
        fun writeProgress(output: File, subworkers: List<Subworker>) {
            runCatching {
                val target = progressFile(output)
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".tmp")
                FileOutputStream(tmp).use { stream ->
                    for (subworker in subworkers) {
                        writeLongLE(stream, subworker.start)
                        writeLongLE(stream, subworker.position)
                        writeLongLE(stream, subworker.end)
                    }
                    stream.flush()
                }
                atomicMove(tmp, target)
            }
        }

        fun readProgress(output: File): List<Subworker> {
            val target = progressFile(output)
            if (!target.isFile) return emptyList()
            return runCatching {
                val bytes = target.readBytes()
                val result = ArrayList<Subworker>(bytes.size / 24)
                var index = 0
                var offset = 0
                while (offset + 24 <= bytes.size) {
                    val start = readLongLE(bytes, offset)
                    val position = readLongLE(bytes, offset + 8)
                    val end = readLongLE(bytes, offset + 16)
                    if (position < start) return emptyList()
                    result += Subworker(
                        index = index++,
                        start = start,
                        end = end,
                        receivedBytes = position - start
                    )
                    offset += 24
                }
                result
            }.getOrElse { emptyList() }
        }

        fun deleteProgress(output: File) {
            runCatching { progressFile(output).delete() }
        }

        private fun writeLongLE(stream: OutputStream, value: Long) {
            for (byteIndex in 0 until 8) {
                stream.write(((value ushr (8 * byteIndex)) and 0xFF).toInt())
            }
        }

        private fun readLongLE(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (byteIndex in 0 until 8) {
                value = value or ((bytes[offset + byteIndex].toLong() and 0xFF) shl (8 * byteIndex))
            }
            return value
        }

        private fun atomicMove(source: File, target: File) {
            runCatching {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }.onFailure {
                runCatching {
                    Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }.onFailure { source.renameTo(target) }
            }
        }
    }

    private fun b64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun unb64(value: String): String =
        runCatching { String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8) }
            .getOrDefault(value)
}

/** 持久化用的任务记录。 */
internal data class PersistedTask(
    val id: Long,
    val url: String,
    val name: String,
    val folder: String,
    val status: EngineStatus,
    val totalBytes: Long,
    val canUseRange: Boolean,
    val error: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val downloadedBytes: Long,
    val subworkerCount: Int,
    val headers: Map<String, String> = emptyMap(),
    val category: String? = null
)

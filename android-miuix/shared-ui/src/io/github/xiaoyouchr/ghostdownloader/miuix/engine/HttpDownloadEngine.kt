package io.github.xiaoyouchr.ghostdownloader.miuix.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * 内置下载引擎：HTTP/HTTPS 多线程分块 + Range 断点续传 + 暂停/继续 + 任务持久化。
 *
 * 说明：上游 Ghost Downloader 的业务引擎是 Python（依赖 wreq / libtorrent 的安卓交叉编译轮子），
 * 在不具备私有 Android 仓库与这些 native wheel 的情况下无法直接嵌入；这里用 Kotlin/OkHttp
 * 实现了同一套核心下载语义（Task Record / Part Path / 断点续传 / 并发上限 / 同名冲突），
 * 让 Miuix UI 成为可真正下载的 App。
 *
 * 线程模型：所有可变状态由 [lock] 保护；网络与写盘在 [scope] 的 IO 线程上执行；
 * UI 通过 [snapshots] 轮询，不直接持有引擎内部对象。
 */
class HttpDownloadEngine(private val dirs: AppDirs) {

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = TaskStore(dirs.dataDir)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
            )
        }
        .build()

    private val records = LinkedHashMap<Long, TaskRecord>()
    private var nextId = 1L
    private var closed = false

    /** 同时进行的 Task 数上限，由设置页的「最大任务数」驱动。 */
    var maxConcurrent: Int = 3
        set(value) {
            synchronized(lock) {
                field = value.coerceIn(1, 10)
                dispatchLocked()
            }
        }

    /** 单个 Task 的分块连接数，由设置页的「预分配线程数」驱动（引擎内上限 16）。 */
    var connectionsPerTask: Int = 8
        set(value) {
            synchronized(lock) { field = value.coerceIn(1, 16) }
        }

    init {
        dirs.downloadDir.mkdirs()
        dirs.dataDir.mkdirs()
        loadPersistedLocked()
        startSampler()
    }

    // ---------------------------------------------------------------- 对外 API

    /** 探测链接：解析文件名、大小、是否支持 Range。阻塞调用，请在 IO 线程使用。 */
    fun probe(url: String): ProbeResult {
        val target = url.trim()
        val scheme = target.substringBefore("://", "").lowercase()
        require(scheme == "http" || scheme == "https") {
            "当前内置引擎仅支持 HTTP/HTTPS 链接（磁力/BT 需要上游 Python 引擎）"
        }
        val head = runCatching {
            client.newCall(Request.Builder().url(target).head().build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val length = response.body?.contentLength()?.takeIf { it > 0L }
                    ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                    ?: 0L
                ProbeResult(
                    url = target,
                    fileName = resolveFileName(target, response.header("Content-Disposition")),
                    totalBytes = length,
                    resumable = response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true,
                    contentType = response.header("Content-Type")
                )
            }
        }
        head.getOrNull()?.let { return it }

        // 有些服务器禁用 HEAD，用 `Range: bytes=0-0` 兜底探测大小与续传能力。
        return client.newCall(
            Request.Builder().url(target).header("Range", "bytes=0-0").build()
        ).execute().use { response ->
            if (response.code == 416) {
                return@use ProbeResult(
                    url = target,
                    fileName = resolveFileName(target, response.header("Content-Disposition")),
                    totalBytes = 0L,
                    resumable = false,
                    contentType = response.header("Content-Type")
                )
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val contentRange = response.header("Content-Range")
            val total = contentRange?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0L }
                ?: response.body?.contentLength()?.takeIf { it > 0L }
                ?: 0L
            ProbeResult(
                url = target,
                fileName = resolveFileName(target, response.header("Content-Disposition")),
                totalBytes = total,
                resumable = response.code == 206 && contentRange != null,
                contentType = response.header("Content-Type")
            )
        }
    }

    /** 新建 Task（进入等待队列）。同名冲突在这里处理。 */
    fun addTask(
        url: String,
        folder: String,
        preferredName: String,
        totalBytes: Long,
        resumable: Boolean,
        policy: NameConflictPolicy = NameConflictPolicy.KEEP_BOTH
    ): TaskSnapshot = synchronized(lock) {
        val dir = File(folder.ifBlank { dirs.downloadDir.absolutePath })
        dir.mkdirs()

        var name = sanitizeFileName(preferredName.ifBlank { resolveFileName(url, null) })
        val output = File(dir, name)
        when {
            output.exists() && policy == NameConflictPolicy.OVERWRITE -> output.delete()
            output.exists() -> name = uniqueName(dir, name)
        }
        val part = File(dir, "$name.ghd")
        if (part.exists()) part.delete()

        val record = TaskRecord(
            id = nextId++,
            url = url,
            name = name,
            folder = dir.absolutePath,
            status = EngineStatus.WAITING,
            totalBytes = totalBytes.coerceAtLeast(0L),
            resumable = resumable,
            createdAt = System.currentTimeMillis(),
            maxConnections = connectionsPerTask
        )
        records[record.id] = record
        persistLocked()
        snapshotOf(record)
    }

    /** 开始 / 继续。若并发已满则停留在 WAITING，由 [dispatchLocked] 自动调度。 */
    fun start(id: Long) = synchronized(lock) {
        val record = records[id] ?: return
        if (record.status == EngineStatus.COMPLETED) return
        record.error = null
        record.status = EngineStatus.WAITING
        persistLocked()
        dispatchLocked()
    }

    fun pause(id: Long) = synchronized(lock) {
        val record = records[id] ?: return
        if (record.status == EngineStatus.RUNNING || record.status == EngineStatus.WAITING) {
            record.status = EngineStatus.PAUSED
            record.speed = 0L
            record.activeCalls.forEach { it.cancel() }
            record.job?.cancel()
            record.job = null
            persistLocked()
            dispatchLocked()
        }
    }

    /** 删除 Task。未完成的 Part 文件总会删除；已完成的成品文件只在 [deleteFiles] 时删除。 */
    fun remove(id: Long, deleteFiles: Boolean) = synchronized(lock) {
        val record = records.remove(id) ?: return
        record.activeCalls.forEach { it.cancel() }
        record.job?.cancel()
        record.job = null
        record.partFile().delete()
        if (deleteFiles) record.outputFile().delete()
        persistLocked()
        dispatchLocked()
    }

    fun pauseAll() = synchronized(lock) {
        records.values.forEach { record ->
            if (record.status == EngineStatus.RUNNING || record.status == EngineStatus.WAITING) {
                record.status = EngineStatus.PAUSED
                record.speed = 0L
                record.activeCalls.forEach { it.cancel() }
                record.job?.cancel()
                record.job = null
            }
        }
        persistLocked()
        dispatchLocked()
    }

    fun startAll() = synchronized(lock) {
        records.values.forEach { record ->
            if (record.status == EngineStatus.PAUSED || record.status == EngineStatus.FAILED) {
                record.error = null
                record.status = EngineStatus.WAITING
            }
        }
        persistLocked()
        dispatchLocked()
    }

    fun snapshots(): List<TaskSnapshot> = synchronized(lock) {
        records.values.map(::snapshotOf).sortedBy { it.createdAt }
    }

    fun defaultFolder(): String = dirs.downloadDir.absolutePath

    fun close() {
        synchronized(lock) {
            records.values.forEach {
                it.activeCalls.forEach { call -> call.cancel() }
                it.job?.cancel()
            }
            persistLocked()
            closed = true
        }
        scope.cancel()
    }

    // ------------------------------------------------------------ 调度与执行

    private fun dispatchLocked() {
        if (closed) return
        var slots = maxConcurrent - records.values.count { it.status == EngineStatus.RUNNING }
        if (slots <= 0) return
        for (record in records.values) {
            if (slots <= 0) break
            if (record.status != EngineStatus.WAITING) continue
            record.status = EngineStatus.RUNNING
            record.lastSampleBytes = record.downloaded.get()
            record.job = scope.launch { runTask(record) }
            slots--
        }
        persistLocked()
    }

    private suspend fun runTask(record: TaskRecord) {
        try {
            val folder = File(record.folder)
            folder.mkdirs()
            val part = record.partFile()
            part.parentFile?.mkdirs()
            if (!part.exists()) part.createNewFile()

            synchronized(lock) {
                if (record.totalBytes > 0L && record.resumable && record.chunks.isEmpty()) {
                    buildChunksLocked(record)
                }
            }

            if (record.totalBytes > 0L && record.resumable) {
                coroutineScope {
                    record.chunks.forEach { chunk ->
                        launch { downloadChunk(record, chunk) }
                    }
                }
            } else {
                downloadSingleStream(record)
            }
            finishTask(record)
        } catch (cancelled: CancellationException) {
            // pause()/remove() 主动取消，状态已由调用方设置
            throw cancelled
        } catch (error: Throwable) {
            failTask(record, error)
        }
    }

    private suspend fun downloadChunk(record: TaskRecord, chunk: ChunkState) {
        var attempts = 0
        while (!chunk.isDone) {
            currentCoroutineContext().ensureActive()
            val rangeStart = chunk.nextOffset()
            val request = Request.Builder()
                .url(record.url)
                .header("Range", "bytes=$rangeStart-${chunk.end - 1}")
                .build()
            val call = client.newCall(request)
            record.activeCalls.add(call)
            try {
                call.execute().use { response ->
                    if (response.code == 416) {
                        chunk.downloaded = chunk.size
                        return
                    }
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    if (response.code != 206) throw IOException("服务器不支持断点续传")
                    val input = response.body?.byteStream() ?: throw IOException("响应内容为空")
                    input.use {
                        RandomAccessFile(record.partFile(), "rw").use { file ->
                            file.seek(rangeStart)
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (!chunk.isDone) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                val write = min(read.toLong(), chunk.size - chunk.downloaded).toInt()
                                if (write <= 0) break
                                file.write(buffer, 0, write)
                                chunk.downloaded += write
                                record.downloaded.addAndGet(write.toLong())
                            }
                        }
                    }
                }
                attempts = 0
            } catch (error: IOException) {
                attempts++
                if (attempts >= MAX_RETRIES) throw error
                delay(RETRY_DELAY_MS * attempts)
            } finally {
                record.activeCalls.remove(call)
            }
        }
    }

    private suspend fun downloadSingleStream(record: TaskRecord) {
        record.downloaded.set(0L)
        record.partFile().delete()
        val request = Request.Builder().url(record.url).build()
        val call = client.newCall(request)
        record.activeCalls.add(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("响应内容为空")
                val length = body.contentLength()
                if (length > 0L) record.totalBytes = length
                body.byteStream().use { input ->
                    RandomAccessFile(record.partFile(), "rw").use { file ->
                        file.setLength(0L)
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            file.write(buffer, 0, read)
                            record.downloaded.addAndGet(read.toLong())
                        }
                    }
                }
            }
        } finally {
            record.activeCalls.remove(call)
        }
    }

    private fun buildChunksLocked(record: TaskRecord) {
        val total = record.totalBytes
        val maxChunks = record.maxConnections.coerceIn(1, 16)
        val count = min(maxChunks, max(1, (total / MIN_CHUNK_BYTES).toInt()))
        val chunkSize = total / count
        val chunks = ArrayList<ChunkState>(count)
        var start = 0L
        repeat(count) { index ->
            val end = if (index == count - 1) total else start + chunkSize
            chunks += ChunkState(start = start, end = end)
            start = end
        }
        record.chunks.clear()
        record.chunks.addAll(chunks)
    }

    private fun finishTask(record: TaskRecord) = synchronized(lock) {
        if (record.status == EngineStatus.COMPLETED) return
        val part = record.partFile()
        val output = record.outputFile()
        if (output.exists()) output.delete()
        val renamed = part.renameTo(output)
        if (renamed) {
            record.status = EngineStatus.COMPLETED
            record.completedAt = System.currentTimeMillis()
            record.error = null
        } else {
            record.status = EngineStatus.FAILED
            record.error = "下载完成但无法重命名文件"
        }
        record.speed = 0L
        record.job = null
        persistLocked()
        dispatchLocked()
    }

    private fun failTask(record: TaskRecord, error: Throwable) = synchronized(lock) {
        if (record.status == EngineStatus.PAUSED) return
        record.status = EngineStatus.FAILED
        record.error = friendlyError(error)
        record.speed = 0L
        record.job = null
        persistLocked()
        dispatchLocked()
    }

    private fun friendlyError(error: Throwable): String = when {
        error is IOException && !error.message.isNullOrBlank() -> "网络错误：${error.message}"
        !error.message.isNullOrBlank() -> error.message!!
        else -> "下载失败"
    }

    // ------------------------------------------------------------ 内部工具

    private fun snapshotOf(record: TaskRecord): TaskSnapshot = TaskSnapshot(
        id = record.id,
        url = record.url,
        name = record.name,
        folder = record.folder,
        status = record.status,
        downloadedBytes = record.downloaded.get(),
        totalBytes = record.totalBytes,
        speedBytes = if (record.status == EngineStatus.RUNNING) record.speed else 0L,
        connections = if (record.status == EngineStatus.RUNNING) {
            record.chunks.count { !it.isDone }.coerceAtLeast(1).coerceAtMost(record.maxConnections)
        } else {
            0
        },
        maxConnections = record.maxConnections,
        error = record.error,
        createdAt = record.createdAt,
        completedAt = record.completedAt,
        partPath = if (record.status == EngineStatus.COMPLETED) null else record.partFile().absolutePath,
        outputPath = record.outputFile().absolutePath,
        resumable = record.resumable
    )

    private fun persistLocked() {
        if (closed) return
        val persisted = records.values.map { record ->
            PersistedTask(
                id = record.id,
                url = record.url,
                name = record.name,
                folder = record.folder,
                status = record.status,
                totalBytes = record.totalBytes,
                resumable = record.resumable,
                error = record.error,
                createdAt = record.createdAt,
                completedAt = record.completedAt,
                downloadedBytes = record.downloaded.get(),
                maxConnections = record.maxConnections,
                chunks = record.chunks.map { ChunkState(it.start, it.end, it.downloaded) }
            )
        }
        store.save(persisted)
    }

    private fun loadPersistedLocked() {
        store.load().forEach { persisted ->
            val chunks = persisted.chunks.toMutableList()
            val downloaded = if (chunks.isNotEmpty()) chunks.sumOf { it.downloaded } else persisted.downloadedBytes
            val part = File(persisted.folder, "${persisted.name}.ghd")
            val output = File(persisted.folder, persisted.name)
            val restoredStatus = when (persisted.status) {
                EngineStatus.RUNNING, EngineStatus.WAITING -> EngineStatus.PAUSED
                else -> persisted.status
            }
            val record = TaskRecord(
                id = persisted.id,
                url = persisted.url,
                name = persisted.name,
                folder = persisted.folder,
                status = if (restoredStatus == EngineStatus.COMPLETED || restoredStatus == EngineStatus.FAILED) {
                    restoredStatus
                } else {
                    EngineStatus.PAUSED
                },
                totalBytes = persisted.totalBytes,
                resumable = persisted.resumable,
                createdAt = persisted.createdAt,
                maxConnections = persisted.maxConnections,
                error = persisted.error
            )
            record.completedAt = persisted.completedAt
            if (restoredStatus != EngineStatus.COMPLETED) {
                if (part.exists()) {
                    record.chunks.addAll(chunks)
                    record.downloaded.set(if (chunks.isNotEmpty()) downloaded else 0L)
                }
            }
            records[record.id] = record
            nextId = max(nextId, record.id + 1)
        }
    }

    private fun startSampler() {
        scope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                synchronized(lock) {
                    records.values.forEach { record ->
                        val now = record.downloaded.get()
                        val delta = now - record.lastSampleBytes
                        record.lastSampleBytes = now
                        record.speed = if (record.status == EngineStatus.RUNNING) {
                            max(0L, (delta * 1000.0 / SAMPLE_INTERVAL_MS).toLong())
                        } else {
                            0L
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val USER_AGENT = "GhostDownloader-Miuix/4.4.3 (Android; Kotlin)"
        private const val BUFFER_SIZE = 64 * 1024
        private const val MIN_CHUNK_BYTES = 1L * 1024 * 1024
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 800L
        private const val SAMPLE_INTERVAL_MS = 500L

        internal fun sanitizeFileName(name: String): String {
            val cleaned = name
                .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
                .trim()
                .trimStart('.')
                .ifBlank { "download.bin" }
            return cleaned.take(180)
        }

        internal fun uniqueName(folder: File, name: String): String {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var index = 1
            while (true) {
                val candidate = "$base ($index)$ext"
                if (!File(folder, candidate).exists() && !File(folder, "$candidate.ghd").exists()) {
                    return candidate
                }
                index++
            }
        }

        internal fun resolveFileName(url: String, contentDisposition: String?): String {
            contentDisposition?.let { header ->
                val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
                    .find(header)?.groupValues?.getOrNull(1)
                if (!encoded.isNullOrBlank()) {
                    return sanitizeFileName(URLDecoder.decode(encoded, "UTF-8"))
                }
                val plain = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                    .find(header)?.groupValues?.getOrNull(1)
                if (!plain.isNullOrBlank()) return sanitizeFileName(plain)
            }
            val path = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
            val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
            return sanitizeFileName(decoded)
        }
    }
}

/** 引擎内部的 Task Record。除 [job] / [chunks] 外的可变字段只在 [HttpDownloadEngine] 的锁内修改。 */
internal class TaskRecord(
    val id: Long,
    val url: String,
    val name: String,
    val folder: String,
    var status: EngineStatus,
    @Volatile var totalBytes: Long,
    val resumable: Boolean,
    val createdAt: Long,
    var maxConnections: Int,
    var error: String? = null
) {
    var completedAt: Long? = null
    var job: Job? = null
    val chunks: MutableList<ChunkState> = mutableListOf()
    val activeCalls: MutableList<Call> = CopyOnWriteArrayList()
    val downloaded = AtomicLong(0L)

    @Volatile
    var speed: Long = 0L

    @Volatile
    var lastSampleBytes: Long = 0L

    fun partFile(): File = File(folder, "$name.ghd")
    fun outputFile(): File = File(folder, name)
}

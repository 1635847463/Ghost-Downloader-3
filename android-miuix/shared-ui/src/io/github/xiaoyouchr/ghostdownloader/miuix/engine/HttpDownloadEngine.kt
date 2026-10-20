package io.github.xiaoyouchr.ghostdownloader.miuix.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 内置 HTTP/HTTPS 下载引擎，语义对齐开发者文档《下载引擎》《任务系统》与上游
 * `features/http_pack/task.py`：
 *
 * 1. Range 探测：先 `bytes=1-1` 做偏移探测，失败回退 `bytes=0-0`，206/416 的 content-range 取总大小；
 * 2. 三种模式：206 分片 / 200+content-length 单线程（重试从头）/ 200 无 content-length 单线程（重试续写）；
 * 3. 分片完成后接管「剩余量最大」的分片后半段（Subworker 拆分），尾部不空等；
 * 4. 运行时降级：分片过程中服务器返回 200 → 截断文件、删进度、改单流重下；
 * 5. 自动加速：每秒采样，5 个样本稳定后拆 4 个分片，5 秒后按速度增益/并行增益 ≥ 80% 决定继续或永久停止；
 * 6. 进度文件 `{output}.ghd`（上游二进制格式，24 字节小端 start/position/end），每秒落盘；
 * 7. 任务列表 `tasks.jsonl`（JSONL + 200ms 防抖 + 临时文件原子重命名），重启后自动恢复等待/运行中的任务；
 * 8. 调度：有序等待队列 + 最大并发；降低并发时把多余的可暂停任务取消回等待队列；
 * 9. 临时错误 5 秒后重试（界面上显示重试次数），永久错误（400/401/403/404/405/410/451）直接失败。
 */
class HttpDownloadEngine(
    private val dirs: AppDirs,
    private val config: EngineConfig = EngineConfig()
) {

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = TaskStore(dirs.dataDir)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
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
    private val waitingQueue = ArrayDeque<Long>()
    private var nextId = 1L
    private var closed = false
    private var dirty = false
    private var saverJob: Job? = null

    /** 同时进行的 Task 数上限，由设置页的「最大任务数」驱动。 */
    var maxConcurrent: Int = 3
        set(value) {
            synchronized(lock) {
                field = value.coerceIn(1, 10)
                enforceConcurrencyLocked()
                dispatchLocked()
            }
        }

    /** 每个 Task 的初始 Subworker 数，由设置页的「预分配线程数」驱动。 */
    var subworkerCount: Int = 8
        set(value) {
            synchronized(lock) {
                field = value.coerceIn(1, 16)
                markDirty()
            }
        }

    /** 自动加速开关，由设置页的「自动提速」驱动。 */
    var autoSpeedUp: Boolean = config.speedUpEnabled

    /** 完成后是否恢复服务器 Last-Modified 时间，由设置页的「保留文件修改时间」驱动。 */
    var preserveLastModified: Boolean = false

    init {
        dirs.downloadDir.mkdirs()
        dirs.dataDir.mkdirs()
        loadPersistedLocked()
        startSaver()
        synchronized(lock) { dispatchLocked() }
    }

    // ---------------------------------------------------------------- 探测

    /** 两阶段 Range 探测（对齐文档：偏移 1 探测 + 偏移 0 回退）。 */
    fun probe(url: String): ProbeResult {
        val target = url.trim()
        val scheme = target.substringBefore("://", "").lowercase()
        require(scheme == "http" || scheme == "https") {
            "当前内置引擎仅支持 HTTP/HTTPS 链接（磁力/BT 需要上游 Python 引擎）"
        }

        var fileSize = 0L
        var canUseRange = false
        var finalUrl = target
        var headers: Headers? = null

        // ① 偏移探测：bytes=1-1，避免 bytes=0-0 无法区分「支持 Range」和「忽略 Range」。
        val offset = requestProbe(target, "bytes=1-1")
        if (offset.status != 200 && offset.status != 206 && offset.status != 416) {
            throw PermanentDownloadException(offset.status)
        }
        finalUrl = offset.finalUrl
        headers = offset.headers
        fileSize = rangeTotal(offset.headers)
        canUseRange = offset.status == 206 && offset.headers["Content-Range"] != null
        if (!canUseRange) {
            fileSize = bodyLength(offset.headers)
        }

        // ② 回退探测：服务端返回 200 且长度未知 / 只有 1 字节时，再试 bytes=0-0。
        if (offset.status == 200 && (fileSize == 0L || fileSize == 1L)) {
            val fallback = runCatching { requestProbe(target, "bytes=0-0") }.getOrNull()
            if (fallback != null) {
                val fallbackRange = fallback.status == 206 && fallback.headers["Content-Range"] != null
                if (fallbackRange) {
                    fileSize = rangeTotal(fallback.headers)
                    canUseRange = true
                    headers = fallback.headers
                    finalUrl = fallback.finalUrl
                } else if (fileSize == 0L) {
                    fileSize = bodyLength(fallback.headers)
                    if (fileSize == 0L && fallback.status == 416) {
                        fileSize = rangeTotal(fallback.headers)
                    }
                }
            }
        }

        val finalHeaders = headers
        return ProbeResult(
            url = finalUrl,
            fileName = resolveFileName(finalUrl, finalHeaders?.get("Content-Disposition")),
            totalBytes = fileSize,
            canUseRange = canUseRange,
            contentType = finalHeaders?.get("Content-Type"),
            lastModified = finalHeaders?.get("Last-Modified")
        )
    }

    private data class ProbeResponse(val status: Int, val headers: Headers, val finalUrl: String)

    private fun requestProbe(
        url: String,
        range: String?,
        headers: Map<String, String> = emptyMap()
    ): ProbeResponse {
        val builder = Request.Builder().url(url).header("Accept-Encoding", "identity")
        for ((key, value) in headers) builder.header(key, value)
        if (range != null) builder.header("Range", range)
        client.newCall(builder.build()).execute().use { response ->
            return ProbeResponse(
                status = response.code,
                headers = response.headers,
                finalUrl = response.request.url.toString()
            )
        }
    }

    // ---------------------------------------------------------------- 任务 API

    /** 新建 Task（进入等待队列并立即尝试调度）。同名冲突检查内存任务 / 磁盘成品 / 磁盘进度文件。 */
    fun addTask(
        url: String,
        folder: String,
        preferredName: String,
        totalBytes: Long,
        canUseRange: Boolean,
        policy: NameConflictPolicy = NameConflictPolicy.KEEP_BOTH,
        headers: Map<String, String> = emptyMap(),
        subworkerCount: Int = 0,
        category: String? = null
    ): TaskSnapshot = synchronized(lock) {
        val dir = File(folder.ifBlank { dirs.downloadDir.absolutePath })
        dir.mkdirs()

        var name = sanitizeFileName(preferredName.ifBlank { resolveFileName(url, null) })
        name = resolveNameLocked(dir, name, policy)
        val output = File(dir, name)
        if (!output.exists()) runCatching { output.createNewFile() }

        val record = TaskRecord(
            id = nextId++,
            url = url,
            name = name,
            folder = dir.absolutePath,
            status = EngineStatus.WAITING,
            totalBytes = totalBytes.coerceAtLeast(0L),
            canUseRange = canUseRange,
            createdAt = System.currentTimeMillis(),
            subworkerCount = if (subworkerCount > 0) subworkerCount.coerceIn(1, 16) else this.subworkerCount,
            headers = headers,
            category = category
        )
        records[record.id] = record
        waitingQueue.addLast(record.id)
        markDirty()
        dispatchLocked()
        snapshotOf(record)
    }

    /** 开始 / 继续。并发已满时停留在等待队列。 */
    fun start(id: Long) = synchronized(lock) {
        val record = records[id] ?: return
        if (record.status == EngineStatus.RUNNING || record.status == EngineStatus.COMPLETED) return
        record.error = null
        record.detail = null
        record.status = EngineStatus.WAITING
        enqueueLocked(record.id)
        markDirty()
        dispatchLocked()
    }

    fun pause(id: Long) = synchronized(lock) {
        val record = records[id] ?: return
        pauseLocked(record)
        markDirty()
        dispatchLocked()
    }

    /** 重新下载：删除成品与进度文件，重置所有分片后重新入队（文档《任务系统》操作链路）。 */
    fun redownload(id: Long) = synchronized(lock) {
        val record = records[id] ?: return
        pauseLocked(record)
        cancelWorkersLocked(record)
        TaskStore.deleteProgress(record.outputFile())
        record.outputFile().delete()
        runCatching { record.outputFile().createNewFile() }
        record.subworkers.clear()
        record.downloaded.set(0L)
        record.error = null
        record.detail = null
        record.completedAt = null
        record.isAccelerated = false
        record.speedHistory.clear()
        record.accelCheckAt = 0L
        record.effectiveUrl = record.url
        record.status = EngineStatus.WAITING
        enqueueLocked(record.id)
        markDirty()
        dispatchLocked()
    }

    /** 删除 Task。未完成的进度文件总会删除；成品只在 [deleteFiles] 时删除。 */
    fun remove(id: Long, deleteFiles: Boolean) = synchronized(lock) {
        val record = records.remove(id) ?: return
        pauseLocked(record)
        cancelWorkersLocked(record)
        waitingQueue.remove(id)
        TaskStore.deleteProgress(record.outputFile())
        if (deleteFiles) record.outputFile().delete()
        markDirty()
        dispatchLocked()
    }

    fun pauseAll() = synchronized(lock) {
        records.values.forEach { pauseLocked(it) }
        markDirty()
        dispatchLocked()
    }

    fun startAll() = synchronized(lock) {
        records.values.forEach { record ->
            if (record.status == EngineStatus.PAUSED || record.status == EngineStatus.FAILED) {
                record.error = null
                record.detail = null
                record.status = EngineStatus.WAITING
                enqueueLocked(record.id)
            }
        }
        markDirty()
        dispatchLocked()
    }

    fun snapshots(): List<TaskSnapshot> = synchronized(lock) {
        records.values.map(::snapshotOf).sortedBy { it.createdAt }
    }

    fun defaultFolder(): String = dirs.downloadDir.absolutePath

    /** 进程退出：全部任务标记为已暂停 → 写盘 → 关闭（对齐文档《架构总览》退出流程）。 */
    fun close() {
        synchronized(lock) {
            records.values.forEach { record ->
                if (record.status == EngineStatus.RUNNING || record.status == EngineStatus.WAITING) {
                    pauseLocked(record)
                    record.status = EngineStatus.PAUSED
                }
            }
            persistLocked()
            closed = true
        }
        saverJob?.cancel()
        scope.cancel()
    }

    // ------------------------------------------------------------ 调度与生命周期

    private fun enqueueLocked(id: Long) {
        if (id !in waitingQueue) waitingQueue.addLast(id)
    }

    private fun dispatchLocked() {
        if (closed) return
        if (waitingQueue.isEmpty()) return
        var slots = maxConcurrent - records.values.count { it.status == EngineStatus.RUNNING }
        while (slots > 0 && waitingQueue.isNotEmpty()) {
            val id = waitingQueue.removeFirst()
            val record = records[id] ?: continue
            if (record.status != EngineStatus.WAITING) continue
            startLocked(record)
            slots--
        }
    }

    private fun startLocked(record: TaskRecord) {
        record.status = EngineStatus.RUNNING
        record.error = null
        record.detail = null
        record.failure = null
        record.lastSampleBytes = record.downloaded.get()
        record.speedHistory.clear()
        record.isAccelerated = !autoSpeedUp
        record.accelCheckAt = 0L
        record.job = scope.launch { runTask(record) }
        markDirty()
    }

    /** 降低最大并发时，把超出的可暂停任务取消回等待队列（文档《任务系统》调度规则）。 */
    private fun enforceConcurrencyLocked() {
        while (records.values.count { it.status == EngineStatus.RUNNING } > maxConcurrent) {
            val victim = records.values.reversed().firstOrNull {
                it.status == EngineStatus.RUNNING && it.canUseRange
            } ?: return
            pauseLocked(victim)
            victim.status = EngineStatus.WAITING
            victim.detail = "并发数下调，等待调度"
            enqueueLocked(victim.id)
        }
    }

    private fun pauseLocked(record: TaskRecord) {
        if (record.status == EngineStatus.RUNNING || record.status == EngineStatus.WAITING) {
            record.status = EngineStatus.PAUSED
            record.speed = 0L
            record.detail = null
            waitingQueue.remove(record.id)
            record.activeCalls.forEach { runCatching { it.cancel() } }
            record.workers.forEach { it.cancel() }
            record.workers.clear()
            record.job?.cancel()
            record.job = null
            if (record.canUseRange) {
                TaskStore.writeProgress(record.outputFile(), record.subworkers)
            }
        }
    }

    private fun cancelWorkersLocked(record: TaskRecord) {
        record.activeCalls.forEach { runCatching { it.cancel() } }
        record.workers.forEach { it.cancel() }
        record.workers.clear()
        record.job?.cancel()
        record.job = null
    }

    // ------------------------------------------------------------ 任务执行

    private suspend fun runTask(record: TaskRecord) {
        while (true) {
            try {
                runAttempt(record)
                completeTask(record)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rangeNotSupported: RangeNotSupportedException) {
                if (!record.canUseRange) {
                    failTask(record, rangeNotSupported)
                    return
                }
                downgradeToSingleThread(record)
                // 继续循环：重新以单流模式执行
            } catch (permanent: PermanentDownloadException) {
                failTask(record, permanent)
                return
            } catch (fatal: FatalIOException) {
                failTask(record, fatal)
                return
            } catch (error: Throwable) {
                failTask(record, error)
                return
            }
        }
    }

    private suspend fun runAttempt(record: TaskRecord) {
        File(record.folder).mkdirs()

        // 轻量探测：只为跟随重定向拿到最终 URL，并刷新可能变化的大小（文档 run() 的 probeHeaders）。
        val runProbe = runCatching { requestProbe(record.url, "bytes=0-0", record.headers) }.getOrNull()
        if (runProbe != null) {
            record.effectiveUrl = runProbe.finalUrl
            record.lastModified = runProbe.headers["Last-Modified"] ?: record.lastModified
            val total = rangeTotal(runProbe.headers).takeIf { it > 0L }
                ?: bodyLength(runProbe.headers).takeIf { it > 0L }
                ?: 0L
            if (total > 0L && record.totalBytes > 0L && total != record.totalBytes) {
                record.totalBytes = total
                TaskStore.deleteProgress(record.outputFile())
                record.subworkers.clear()
                record.downloaded.set(0L)
            } else if (total > 0L && record.totalBytes == 0L) {
                record.totalBytes = total
            }
        } else {
            record.effectiveUrl = record.url
        }

        var restored = false
        if (record.canUseRange) {
            val loaded = TaskStore.readProgress(record.outputFile())
            if (loaded.isNotEmpty()) {
                record.subworkers.clear()
                record.subworkers.addAll(loaded)
                restored = true
            }
        }
        if (!restored) {
            if (!record.canUseRange) TaskStore.deleteProgress(record.outputFile())
            record.subworkers.clear()
            record.subworkers.addAll(buildSubworkers(record))
        } else if (record.totalBytes > 0L) {
            val target = min(record.subworkerCount.toLong(), record.totalBytes).toInt()
            while (record.subworkers.size < target) {
                if (splitSlowestLocked(record, respectMinReassign = false) == null) break
            }
        }
        record.downloaded.set(record.subworkers.sumOf { it.receivedBytes })
        record.progressAt = System.currentTimeMillis()

        // 预分配 / 截断输出文件
        RandomAccessFile(record.outputFile(), "rw").use { file ->
            if (!record.canUseRange) {
                file.setLength(0L)
            } else if (!restored && record.totalBytes > 0L) {
                runCatching { file.setLength(record.totalBytes) }
            }
        }

        record.failure = null
        record.detail = null
        record.workers.clear()
        synchronized(record.taskLock) {
            for (subworker in record.subworkers.toList()) {
                launchWorker(record, subworker)
            }
        }
        val supervisor = scope.launch { supervise(record) }
        val streamMode = !record.canUseRange || record.totalBytes == 0L
        try {
            while (true) {
                delay(WORKER_POLL_INTERVAL_MS)
                record.failure?.let { throw it }
                val finished = synchronized(record.taskLock) {
                    if (streamMode) {
                        // 单流模式没有可判定的分片区间，以 worker 全部退出为完成信号
                        record.workers.isEmpty()
                    } else {
                        record.subworkers.all { it.isDone }
                    }
                }
                if (finished) break
                if (!streamMode) {
                    synchronized(record.taskLock) {
                        if (!record.workers.any { it.isActive }) {
                            // 防御：还有未完成分片却没有活跃 worker，补发。
                            record.subworkers.filter { !it.isDone }.forEach { launchWorker(record, it) }
                        }
                    }
                }
            }
        } finally {
            // 先等待监管协程退出，避免它在 completeTask/failTask 之后写回旧进度
            supervisor.cancelAndJoin()
            record.activeCalls.forEach { runCatching { it.cancel() } }
            record.workers.forEach { it.cancel() }
            record.workers.clear()
            if (record.canUseRange) {
                TaskStore.writeProgress(record.outputFile(), record.subworkers)
            }
        }
    }

    private fun launchWorker(record: TaskRecord, subworker: Subworker) {
        var job: Job? = null
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runSubworker(record, subworker)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                record.failure = failure
                record.activeCalls.forEach { runCatching { it.cancel() } }
            } finally {
                job?.let { record.workers.remove(it) }
            }
        }
        record.workers.add(job)
        job.start()
    }

    private suspend fun runSubworker(record: TaskRecord, initial: Subworker) {
        var subworker = initial
        while (true) {
            when {
                !record.canUseRange -> {
                    downloadNoRange(record)
                    return
                }

                subworker.end == SpecialFileSize.UNKNOWN -> {
                    downloadUnknownSize(record, subworker)
                    return
                }

                subworker.end == SpecialFileSize.NOT_SUPPORTED -> {
                    downloadNoRange(record)
                    return
                }

                else -> {
                    downloadRange(record, subworker)
                    if (record.failure != null) return
                    val next = synchronized(record.taskLock) {
                        reassignSubworkerLocked(record)
                    } ?: return
                    subworker = next
                }
            }
        }
    }

    /** 带 Task 自定义请求头（Referer/Origin/Cookie 等）的请求构造器。 */
    private fun recordRequest(record: TaskRecord): Request.Builder {
        val builder = Request.Builder().url(record.effectiveUrl)
        for ((key, value) in record.headers) builder.header(key, value)
        return builder
    }

    /** 206 分片下载：每次请求 `bytes=position-end`，边下边写，断线 5 秒后从当前位置重试。 */
    private suspend fun downloadRange(record: TaskRecord, subworker: Subworker) {
        var attempt = 0
        while (subworker.position <= subworker.end) {
            currentCoroutineContext().ensureActive()
            val from = subworker.position
            val to = subworker.end
            val call = client.newCall(
                recordRequest(record)
                    .header("Range", "bytes=$from-$to")
                    .header("Accept-Encoding", "identity")
                    .build()
            )
            record.activeCalls.add(call)
            try {
                call.execute().use { response ->
                    val status = response.code
                    if (status in PERMANENT_STATUS || response.header("cf-mitigated") != null) {
                        throw PermanentDownloadException(status)
                    }
                    if (status == 200) throw RangeNotSupportedException()
                    if (status != 206) throw IOException("服务器拒绝了范围请求，状态码：$status")
                    val input = response.body?.byteStream() ?: throw IOException("响应内容为空")
                    input.use {
                        RandomAccessFile(record.outputFile(), "rw").use { file ->
                            file.seek(subworker.position)
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (subworker.position <= subworker.end) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                val remaining = subworker.end - subworker.position + 1
                                val write = min(read.toLong(), remaining).toInt()
                                if (write <= 0) break
                                writeBytes(record, file, subworker, buffer, write)
                            }
                        }
                    }
                    if (subworker.position > subworker.end) {
                        subworker.receivedBytes = subworker.end - subworker.start + 1
                    }
                }
                attempt = 0
                record.detail = null
            } catch (permanent: PermanentDownloadException) {
                throw permanent
            } catch (range: RangeNotSupportedException) {
                throw range
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: IOException) {
                if (isFatalIo(fatal)) throw FatalIOException(fatal)
                attempt++
                record.detail = "网络中断，${config.retryDelayMs / 1000} 秒后重试（第 $attempt 次）"
                delay(config.retryDelayMs)
            } finally {
                record.activeCalls.remove(call)
            }
        }
    }

    /** 200 且无 content-length：open-ended Range，重试时从当前位置继续。 */
    private suspend fun downloadUnknownSize(record: TaskRecord, subworker: Subworker) {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val from = subworker.position
            val call = client.newCall(
                recordRequest(record)
                    .header("Range", "bytes=$from-")
                    .header("Accept-Encoding", "identity")
                    .build()
            )
            record.activeCalls.add(call)
            try {
                call.execute().use { response ->
                    val status = response.code
                    if (status in PERMANENT_STATUS || response.header("cf-mitigated") != null) {
                        throw PermanentDownloadException(status)
                    }
                    if (status == 200) throw RangeNotSupportedException()
                    if (status != 206 && status != 416) {
                        throw IOException("服务器拒绝了范围请求，状态码：$status")
                    }
                    if (status == 416) return
                    val input = response.body?.byteStream() ?: throw IOException("响应内容为空")
                    input.use {
                        RandomAccessFile(record.outputFile(), "rw").use { file ->
                            file.seek(subworker.position)
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                writeBytes(record, file, subworker, buffer, read)
                            }
                        }
                    }
                    attempt = 0
                    record.detail = null
                    return
                }
            } catch (permanent: PermanentDownloadException) {
                throw permanent
            } catch (range: RangeNotSupportedException) {
                throw range
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: IOException) {
                if (isFatalIo(fatal)) throw FatalIOException(fatal)
                attempt++
                record.detail = "网络中断，${config.retryDelayMs / 1000} 秒后重试（第 $attempt 次）"
                delay(config.retryDelayMs)
            } finally {
                record.activeCalls.remove(call)
            }
        }
    }

    /** 200 + content-length：单线程，重试时截断文件从头下载（上游 SpecialFileSize.NOT_SUPPORTED）。 */
    private suspend fun downloadNoRange(record: TaskRecord) {
        val subworker = record.subworkers.firstOrNull { it.end == SpecialFileSize.NOT_SUPPORTED }
            ?: Subworker(0, 0L, SpecialFileSize.NOT_SUPPORTED).also { record.subworkers.add(it) }
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            RandomAccessFile(record.outputFile(), "rw").use { it.setLength(0L) }
            subworker.receivedBytes = 0L
            record.downloaded.set(0L)
            val call = client.newCall(
                recordRequest(record)
                    .header("Accept-Encoding", "identity")
                    .build()
            )
            record.activeCalls.add(call)
            try {
                call.execute().use { response ->
                    val status = response.code
                    if (status in PERMANENT_STATUS || response.header("cf-mitigated") != null) {
                        throw PermanentDownloadException(status)
                    }
                    if (status != 200) throw IOException("服务器返回了异常状态码：$status")
                    val length = bodyLength(response.headers)
                    if (length > 0L) record.totalBytes = length
                    val input = response.body?.byteStream() ?: throw IOException("响应内容为空")
                    input.use {
                        RandomAccessFile(record.outputFile(), "rw").use { file ->
                            file.setLength(0L)
                            file.seek(0L)
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                file.write(buffer, 0, read)
                                subworker.receivedBytes += read
                                record.downloaded.addAndGet(read.toLong())
                                record.progressAt = System.currentTimeMillis()
                            }
                            file.setLength(subworker.receivedBytes)
                        }
                    }
                    attempt = 0
                    record.detail = null
                    return
                }
            } catch (permanent: PermanentDownloadException) {
                throw permanent
            } catch (range: RangeNotSupportedException) {
                throw range
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: IOException) {
                if (isFatalIo(fatal)) throw FatalIOException(fatal)
                attempt++
                record.detail = "网络中断，${config.retryDelayMs / 1000} 秒后重试（第 $attempt 次）"
                delay(config.retryDelayMs)
            } finally {
                record.activeCalls.remove(call)
            }
        }
    }

    private fun writeBytes(
        record: TaskRecord,
        file: RandomAccessFile,
        subworker: Subworker,
        buffer: ByteArray,
        length: Int
    ) {
        file.write(buffer, 0, length)
        subworker.receivedBytes += length
        record.downloaded.addAndGet(length.toLong())
        record.progressAt = System.currentTimeMillis()
    }

    // ------------------------------------------------------------ 分片拆分 / 接管

    private fun buildSubworkers(record: TaskRecord): List<Subworker> {
        if (!record.canUseRange) {
            return listOf(Subworker(0, 0L, SpecialFileSize.NOT_SUPPORTED))
        }
        if (record.totalBytes == 0L) {
            return listOf(Subworker(0, 0L, SpecialFileSize.UNKNOWN))
        }
        val count = min(record.subworkerCount.toLong(), record.totalBytes).toInt().coerceAtLeast(1)
        val chunkSize = record.totalBytes / count
        val result = ArrayList<Subworker>(count)
        var start = 0L
        for (index in 0 until count - 1) {
            val end = start + chunkSize - 1
            result += Subworker(index, start, end)
            start = end + 1
        }
        result += Subworker(count - 1, start, record.totalBytes - 1)
        return result
    }

    /** 拆剩余量最大的分片，后半段给新 Subworker（与上游 `_splitSlowest` 一致）。 */
    private fun splitSlowestLocked(record: TaskRecord, respectMinReassign: Boolean): Subworker? {
        val slowest = record.subworkers.maxByOrNull { it.remaining } ?: return null
        val remaining = slowest.remaining
        if (remaining < 2L) return null
        if (respectMinReassign && remaining < config.maxReassignSizeBytes) return null

        val base = remaining / 2
        val remainder = remaining % 2
        val oldEnd = slowest.end
        slowest.end = slowest.position + base + remainder - 1
        val next = Subworker(record.subworkers.size, slowest.end + 1, oldEnd)
        record.subworkers.add(record.subworkers.indexOf(slowest) + 1, next)
        return next
    }

    /**
     * 分片完成后接管：把剩余量最大的分片后半段交给当前 worker（尾部不空等）。
     * 只返回新 Subworker，由调用方 worker 继续下载；这里不能再 launchWorker，否则同一个
     * Subworker 会被两个 worker 同时写（位置交错 → 数据空洞）。
     */
    private fun reassignSubworkerLocked(record: TaskRecord): Subworker? {
        if (record.totalBytes <= 0L) return null
        return splitSlowestLocked(record, respectMinReassign = true)
    }

    // ------------------------------------------------------------ 监管 / 加速 / 进度

    private suspend fun supervise(record: TaskRecord) {
        var lastReceived = record.downloaded.get()
        var lastStallCancelAt = 0L
        while (currentCoroutineContext().isActive) {
            delay(config.sampleIntervalMs)
            if (record.status != EngineStatus.RUNNING) break
            if (record.canUseRange) {
                TaskStore.writeProgress(record.outputFile(), record.subworkers)
            }
            val received = synchronized(record.taskLock) {
                record.subworkers.sumOf { it.receivedBytes }
            }
            val speed = max(0L, received - lastReceived)
            lastReceived = received
            record.downloaded.set(received)
            record.speed = speed
            autoSpeedUp(record, speed)
            if (speed == 0L && record.canUseRange &&
                System.currentTimeMillis() - record.progressAt > config.stallTimeoutMs &&
                System.currentTimeMillis() - lastStallCancelAt > config.stallTimeoutMs
            ) {
                // 卡住看门狗：长时间无字节进展时取消当前连接，让 worker 走 5 秒重试
                lastStallCancelAt = System.currentTimeMillis()
                record.detail = "长时间无进展，正在重新连接…"
                record.activeCalls.forEach { runCatching { it.cancel() } }
            }
        }
    }

    /** 自动加速（对齐上游 `_autoSpeedUp`）：稳定采样 → 拆 4 片 → 5 秒后比较增益。 */
    private fun autoSpeedUp(record: TaskRecord, speed: Long) {
        if (!autoSpeedUp || record.isAccelerated || !record.canUseRange || record.totalBytes <= 0L) return

        synchronized(record.taskLock) {
            record.speedHistory.addLast(speed)
            while (record.speedHistory.size > 5) record.speedHistory.removeFirst()
            if (record.speedHistory.size < 5) return
            val avg = record.speedHistory.average()
            if (avg <= 0.0) return
            val maxDeviation = record.speedHistory.maxOf { abs(it - avg) / avg }
            if (maxDeviation > 0.15) return

            if (record.accelCheckAt == 0L) {
                record.accelInitialWorkers = record.subworkers.size
                record.accelInitialSpeed = avg
                record.accelCheckAt = System.currentTimeMillis()
                repeat(4) {
                    val next = splitSlowestLocked(record, respectMinReassign = true) ?: return@repeat
                    launchWorker(record, next)
                }
                record.detail = "自动加速中…"
            } else {
                if (System.currentTimeMillis() - record.accelCheckAt <= ACCEL_COMPARE_DELAY_MS) return
                val workerRatio = (record.subworkers.size - record.accelInitialWorkers).toDouble() /
                    record.accelInitialWorkers.coerceAtLeast(1)
                val speedRatio = if (record.accelInitialSpeed > 0.0) {
                    (avg - record.accelInitialSpeed) / record.accelInitialSpeed
                } else {
                    0.0
                }
                if (workerRatio <= 0.0) {
                    record.isAccelerated = true
                } else if (speedRatio < 0.8 * workerRatio) {
                    // 带宽已到上限，永久停止加速
                    record.isAccelerated = true
                } else {
                    record.accelCheckAt = 0L
                    record.speedHistory.clear()
                }
            }
        }
    }

    // ------------------------------------------------------------ 完成 / 失败 / 降级

    private fun completeTask(record: TaskRecord) = synchronized(lock) {
        record.status = EngineStatus.COMPLETED
        record.completedAt = System.currentTimeMillis()
        record.error = null
        record.detail = null
        record.speed = 0L
        record.subworkers.clear()
        if (record.totalBytes > 0L) {
            record.downloaded.set(record.totalBytes)
        }
        TaskStore.deleteProgress(record.outputFile())
        applyLastModified(record)
        markDirty()
        dispatchLocked()
    }

    private fun failTask(record: TaskRecord, error: Throwable) = synchronized(lock) {
        if (record.status == EngineStatus.PAUSED || record.status == EngineStatus.WAITING) return
        record.status = EngineStatus.FAILED
        record.speed = 0L
        record.error = when (error) {
            is PermanentDownloadException -> "服务器返回了错误（${error.status}）"
            is FatalIOException -> "磁盘空间不足或写入失败"
            is RangeNotSupportedException -> "服务器不支持范围请求"
            else -> error.message ?: error.javaClass.simpleName
        }
        record.detail = null
        record.activeCalls.forEach { runCatching { it.cancel() } }
        record.workers.forEach { it.cancel() }
        record.workers.clear()
        record.job = null
        // 失败步骤从头开始：清空进度与已写数据
        record.subworkers.clear()
        record.downloaded.set(0L)
        TaskStore.deleteProgress(record.outputFile())
        runCatching { RandomAccessFile(record.outputFile(), "rw").use { it.setLength(0L) } }
        markDirty()
        dispatchLocked()
    }

    private fun downgradeToSingleThread(record: TaskRecord) = synchronized(record.taskLock) {
        record.canUseRange = false
        record.subworkers.clear()
        record.subworkers.add(Subworker(0, 0L, SpecialFileSize.NOT_SUPPORTED))
        record.downloaded.set(0L)
        record.workers.forEach { it.cancel() }
        record.workers.clear()
        record.speedHistory.clear()
        record.accelCheckAt = 0L
        record.isAccelerated = true
        TaskStore.deleteProgress(record.outputFile())
        runCatching { RandomAccessFile(record.outputFile(), "rw").use { it.setLength(0L) } }
        record.detail = "服务器不再支持分片，已降级为单线程"
    }

    private fun applyLastModified(record: TaskRecord) {
        if (!preserveLastModified) return
        val lastModified = record.lastModified ?: return
        runCatching {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            val time = format.parse(lastModified)?.time ?: return
            record.outputFile().setLastModified(time)
        }
    }

    // ------------------------------------------------------------ 持久化

    private fun markDirty() {
        dirty = true
    }

    private fun startSaver() {
        saverJob = scope.launch {
            while (isActive) {
                delay(PERSIST_DEBOUNCE_MS)
                if (dirty) {
                    dirty = false
                    synchronized(lock) { persistLocked() }
                }
            }
        }
    }

    private fun persistLocked() {
        val tasks = records.values.map { record ->
            PersistedTask(
                id = record.id,
                url = record.url,
                name = record.name,
                folder = record.folder,
                status = record.status,
                totalBytes = record.totalBytes,
                canUseRange = record.canUseRange,
                error = record.error,
                createdAt = record.createdAt,
                completedAt = record.completedAt,
                downloadedBytes = record.downloaded.get(),
                subworkerCount = record.subworkerCount,
                headers = record.headers,
                category = record.category
            )
        }
        store.save(tasks)
        records.values
            .filter { it.canUseRange && it.status != EngineStatus.COMPLETED }
            .forEach { TaskStore.writeProgress(it.outputFile(), it.subworkers) }
    }

    private fun loadPersistedLocked() {
        store.load().forEach { persisted ->
            val output = File(persisted.folder, persisted.name)
            val record = TaskRecord(
                id = persisted.id,
                url = persisted.url,
                name = persisted.name,
                folder = persisted.folder,
                status = persisted.status,
                totalBytes = persisted.totalBytes,
                canUseRange = persisted.canUseRange,
                createdAt = persisted.createdAt,
                subworkerCount = persisted.subworkerCount,
                headers = persisted.headers,
                category = persisted.category
            )
            record.completedAt = persisted.completedAt
            record.error = persisted.error
            val restoredSubworkers = if (persisted.canUseRange) TaskStore.readProgress(output) else emptyList()
            record.subworkers.addAll(restoredSubworkers)
            record.downloaded.set(
                if (restoredSubworkers.isNotEmpty()) restoredSubworkers.sumOf { it.receivedBytes }
                else persisted.downloadedBytes
            )
            // 恢复语义：等待中和运行中的任务自动入队；失败和已暂停保持原状（文档《架构总览》）。
            when (persisted.status) {
                EngineStatus.WAITING, EngineStatus.RUNNING -> {
                    record.status = EngineStatus.WAITING
                    waitingQueue.addLast(record.id)
                }

                else -> Unit
            }
            records[record.id] = record
            nextId = max(nextId, record.id + 1)
        }
    }

    // ------------------------------------------------------------ 快照与工具

    private fun snapshotOf(record: TaskRecord): TaskSnapshot {
        val total = record.totalBytes
        val mode = when {
            record.canUseRange && total > 0L -> DownloadMode.RANGE
            total > 0L -> DownloadMode.STREAM
            else -> DownloadMode.UNKNOWN
        }
        val subworkerTotal = synchronized(record.taskLock) {
            record.subworkers.count { !it.isDone }
        }
        return TaskSnapshot(
            id = record.id,
            url = record.url,
            name = record.name,
            folder = record.folder,
            category = record.category,
            status = record.status,
            downloadedBytes = record.downloaded.get(),
            totalBytes = total,
            speedBytes = if (record.status == EngineStatus.RUNNING) record.speed else 0L,
            subworkers = if (record.status == EngineStatus.RUNNING) subworkerTotal.coerceAtLeast(1) else 0,
            maxSubworkers = record.subworkerCount,
            mode = mode,
            detail = record.detail,
            error = record.error,
            createdAt = record.createdAt,
            completedAt = record.completedAt,
            outputPath = record.outputFile().absolutePath,
            progressPath = TaskStore.progressFile(record.outputFile()).absolutePath,
            canUseRange = record.canUseRange
        )
    }

    private fun resolveNameLocked(dir: File, name: String, policy: NameConflictPolicy): String {
        val output = File(dir, name)
        val progress = TaskStore.progressFile(output)
        val inMemory = records.values.any { it.folder == dir.absolutePath && it.name == name }
        if (!output.exists() && !progress.exists() && !inMemory) return name
        if (policy == NameConflictPolicy.OVERWRITE) {
            runCatching { output.delete() }
            TaskStore.deleteProgress(output)
            return name
        }
        return uniqueName(dir, name)
    }

    private fun rangeTotal(headers: Headers): Long {
        val contentRange = headers["Content-Range"] ?: return SpecialFileSize.UNKNOWN
        val total = contentRange.substringAfterLast('/', "").trim()
        if (total.isEmpty() || total == "*") return SpecialFileSize.UNKNOWN
        return total.toLongOrNull()?.takeIf { it > 0L } ?: SpecialFileSize.UNKNOWN
    }

    private fun bodyLength(headers: Headers): Long {
        val value = headers["Content-Length"]?.trim() ?: return SpecialFileSize.UNKNOWN
        return value.toLongOrNull()?.takeIf { it > 0L } ?: SpecialFileSize.UNKNOWN
    }

    private fun isFatalIo(error: IOException): Boolean {
        val message = error.message ?: return false
        val lower = message.lowercase(Locale.US)
        return lower.contains("no space left") ||
            lower.contains("disk quota") ||
            lower.contains("read-only file system") ||
            lower.contains("i/o error") ||
            lower.contains("permission denied")
    }

    companion object {
        private const val USER_AGENT = "GhostDownloader-Miuix/4.4.3 (Android; Kotlin)"
        private const val BUFFER_SIZE = 64 * 1024
        private const val WORKER_POLL_INTERVAL_MS = 100L
        private const val PERSIST_DEBOUNCE_MS = 200L
        private const val ACCEL_COMPARE_DELAY_MS = 5_000L

        /** 永久错误：直接失败，不做重试（文档 + 上游 PERMANENT_STATUS）。 */
        private val PERMANENT_STATUS = setOf(400, 401, 403, 404, 405, 410, 451)

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
                val output = File(folder, candidate)
                if (!output.exists() && !TaskStore.progressFile(output).exists()) return candidate
                index++
            }
        }

        internal fun resolveFileName(url: String, contentDisposition: String?): String {
            contentDisposition?.let { header ->
                val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
                    .find(header)?.groupValues?.getOrNull(1)
                if (!encoded.isNullOrBlank()) {
                    return sanitizeFileName(java.net.URLDecoder.decode(encoded, "UTF-8"))
                }
                val plain = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                    .find(header)?.groupValues?.getOrNull(1)
                if (!plain.isNullOrBlank()) return sanitizeFileName(plain)
            }
            val path = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
            val decoded = runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
            return sanitizeFileName(decoded)
        }
    }
}

/** 上游的 PermanentDownloadError：HTTP 永久错误。 */
internal class PermanentDownloadException(val status: Int) : Exception("HTTP $status")

/** 分片过程中服务器返回 200，需要降级为单流下载。 */
internal class RangeNotSupportedException : Exception("服务器不支持范围请求")

/** 致命 I/O（磁盘满、只读、配额）——不重试。 */
internal class FatalIOException(cause: IOException) : Exception(cause.message, cause)

/** 引擎内部 Task Record。线程规则见 [HttpDownloadEngine] 的类注释。 */
internal class TaskRecord(
    val id: Long,
    val url: String,
    val name: String,
    val folder: String,
    @Volatile var status: EngineStatus,
    @Volatile var totalBytes: Long,
    @Volatile var canUseRange: Boolean,
    val createdAt: Long,
    var subworkerCount: Int,
    val headers: Map<String, String> = emptyMap(),
    val category: String? = null
) {
    @Volatile
    var completedAt: Long? = null

    @Volatile
    var error: String? = null

    @Volatile
    var detail: String? = null

    @Volatile
    var effectiveUrl: String = url

    @Volatile
    var lastModified: String? = null

    @Volatile
    var job: Job? = null

    @Volatile
    var failure: Throwable? = null

    @Volatile
    var speed: Long = 0L

    @Volatile
    var lastSampleBytes: Long = 0L

    @Volatile
    var progressAt: Long = System.currentTimeMillis()

    val subworkers: MutableList<Subworker> = mutableListOf()
    val activeCalls: MutableList<Call> = CopyOnWriteArrayList()
    val workers: MutableList<Job> = CopyOnWriteArrayList()
    val downloaded = AtomicLong(0L)
    val speedHistory = ArrayDeque<Long>()
    val taskLock = Any()

    @Volatile
    var isAccelerated: Boolean = false

    @Volatile
    var accelCheckAt: Long = 0L

    @Volatile
    var accelInitialWorkers: Int = 0

    @Volatile
    var accelInitialSpeed: Double = 0.0

    fun outputFile(): File = File(folder, name)
}

package io.github.xiaoyouchr.ghostdownloader.miuix.state

import io.github.xiaoyouchr.ghostdownloader.miuix.bridge.BridgeCreateTaskRequest
import io.github.xiaoyouchr.ghostdownloader.miuix.bridge.BridgeCreateTaskResult
import io.github.xiaoyouchr.ghostdownloader.miuix.bridge.BridgeTaskSummary
import io.github.xiaoyouchr.ghostdownloader.miuix.bridge.BrowserBridgeHost
import io.github.xiaoyouchr.ghostdownloader.miuix.bridge.PairRequest
import io.github.xiaoyouchr.ghostdownloader.miuix.bridge.TaskAction
import io.github.xiaoyouchr.ghostdownloader.miuix.bridge.TaskSource
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.EngineStatus
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.HttpDownloadEngine
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.TaskSnapshot
import io.github.xiaoyouchr.ghostdownloader.miuix.platform.PlatformFiles
import io.github.xiaoyouchr.ghostdownloader.miuix.resolver.CategoryRules
import java.io.File
import kotlin.math.round

/**
 * 把 [HttpDownloadEngine] 适配成浏览器扩展桥接所需的 [BrowserBridgeHost]。
 *
 * 线程约定：`tasks()` / `createTask()` / `action()` 在桥接工作线程调用，
 * 引擎本身线程安全；`onPairRequestChanged` / `connectionChanged` 只做状态回写，快速返回。
 */
class EngineBridgeHost(
    private val engine: HttpDownloadEngine,
    private val settings: AppSettings,
    private val platformFiles: PlatformFiles?,
    private val appVersionProvider: () -> String,
    private val tokenProvider: () -> String,
    private val tokenUpdater: (String) -> String,
    private val pairRequestListener: (PairRequest?) -> Unit,
    private val connectionListener: (Boolean) -> Unit
) : BrowserBridgeHost {

    override fun pairToken(): String = tokenProvider()

    override fun regenerateToken(): String = tokenUpdater(generateBridgeToken())

    override fun tasks(): List<BridgeTaskSummary> = engine.snapshots().map { it.toSummary() }

    override fun createTask(request: BridgeCreateTaskRequest): BridgeCreateTaskResult {
        if (request.source == TaskSource.RESOURCE_MERGE) {
            return BridgeCreateTaskResult.rejected("安卓端暂不支持音视频合流（需要 FFmpeg 运行时）")
        }
        if (request.source == TaskSource.PAGE_MEDIA) {
            return BridgeCreateTaskResult.rejected("页面媒体解析暂不支持（需要 yt-dlp 运行时）")
        }
        val url = request.url.ifBlank { request.resources.firstOrNull()?.url.orEmpty() }
        if (url.isBlank()) {
            return BridgeCreateTaskResult.rejected("缺少下载地址")
        }
        val name = request.fileName.ifBlank { request.title }
        val category = CategoryRules.detect(name)
        var folder = engine.defaultFolder()
        if (settings.enableCategory && category != null) {
            folder = File(folder, CategoryRules.folderName(category)).absolutePath
        }
        return runCatching {
            val snapshot = engine.addTask(
                url = url,
                folder = folder,
                preferredName = name,
                totalBytes = request.fileSize.coerceAtLeast(0L),
                canUseRange = request.canUseRange,
                headers = request.headers,
                subworkerCount = settings.preBlockNum,
                category = category
            )
            BridgeCreateTaskResult.created(snapshot.id.toString())
        }.getOrElse { error ->
            BridgeCreateTaskResult.rejected(error.message ?: "创建任务失败")
        }
    }

    override fun action(taskId: String, action: TaskAction): Boolean {
        val id = taskId.toLongOrNull() ?: return false
        val snapshot = engine.snapshots().firstOrNull { it.id == id } ?: return false
        return when (action) {
            TaskAction.TOGGLE_PAUSE -> when (snapshot.status) {
                EngineStatus.RUNNING, EngineStatus.WAITING -> {
                    engine.pause(id)
                    true
                }

                EngineStatus.PAUSED, EngineStatus.FAILED -> {
                    engine.start(id)
                    true
                }

                else -> false
            }

            TaskAction.CANCEL -> {
                engine.remove(id, deleteFiles = true)
                true
            }

            TaskAction.REMOVE -> {
                engine.remove(id, deleteFiles = false)
                true
            }

            TaskAction.REDOWNLOAD -> {
                engine.redownload(id)
                true
            }

            TaskAction.OPEN_FILE -> platformFiles?.openFile(snapshot.outputPath) ?: false

            TaskAction.OPEN_FOLDER -> platformFiles?.openFolder(snapshot.folder) ?: false
        }
    }

    override fun onPairRequestChanged(request: PairRequest?) {
        pairRequestListener(request)
    }

    override fun connectionChanged(connected: Boolean) {
        connectionListener(connected)
    }

    override fun appVersion(): String = appVersionProvider()

    private fun TaskSnapshot.toSummary(): BridgeTaskSummary = BridgeTaskSummary(
        taskId = id.toString(),
        name = name,
        status = when (status) {
            EngineStatus.WAITING -> "waiting"
            EngineStatus.RUNNING -> "running"
            EngineStatus.PAUSED -> "paused"
            EngineStatus.COMPLETED -> "completed"
            EngineStatus.FAILED -> "failed"
        },
        progress = if (totalBytes > 0L) {
            round(downloadedBytes * 10000.0 / totalBytes) / 100.0
        } else {
            0.0
        },
        receivedBytes = downloadedBytes,
        fileSize = totalBytes,
        speed = speedBytes,
        createdAt = createdAt,
        canPause = canUseRange,
        canOpenFile = status == EngineStatus.COMPLETED,
        canOpenFolder = status == EngineStatus.COMPLETED,
        fileExt = name.substringAfterLast('.', ""),
        packName = "http"
    )

    companion object {
        /** 与桌面端 `token_urlsafe(16)` 等价：16 字节随机数的 URL-safe Base64。 */
        fun generateBridgeToken(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

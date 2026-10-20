package io.github.xiaoyouchr.ghostdownloader.miuix

import io.github.xiaoyouchr.ghostdownloader.miuix.engine.AppDirs
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.EngineStatus
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.HttpDownloadEngine
import java.io.File

/**
 * 下载引擎冒烟测试（Desktop / JVM）：
 *
 * ```
 * gradlew :preview:engineSmoke -Purl=https://example.com/file.zip
 * ```
 *
 * 无 GUI 地走完「两阶段 Range 探测 → 入队 → 分片/单流下载 → 校验落盘」全流程。
 */
fun main(args: Array<String>) {
    val url = args.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: "https://raw.githubusercontent.com/XiaoYouChR/Ghost-Downloader-3/main/README.md"
    val root = File(System.getProperty("java.io.tmpdir"), "gd-miuix-smoke-${System.currentTimeMillis()}")
    val downloadDir = File(root, "downloads")
    val engine = HttpDownloadEngine(AppDirs(downloadDir = downloadDir, dataDir = File(root, "data")))
    try {
        val probe = engine.probe(url)
        println(
            "probe: name=${probe.fileName} size=${probe.totalBytes} " +
                "range=${probe.canUseRange} type=${probe.contentType} url=${probe.url}"
        )
        val task = engine.addTask(
            url = probe.url,
            folder = downloadDir.absolutePath,
            preferredName = probe.fileName,
            totalBytes = probe.totalBytes,
            canUseRange = probe.canUseRange
        )

        val deadline = System.currentTimeMillis() + SMOKE_TIMEOUT_MS
        var lastPrinted = -1L
        while (System.currentTimeMillis() < deadline) {
            val snapshot = engine.snapshots().first { it.id == task.id }
            if (snapshot.downloadedBytes != lastPrinted) {
                println(
                    "progress: ${snapshot.downloadedBytes}/${snapshot.totalBytes} " +
                        "speed=${snapshot.speedBytes}B/s mode=${snapshot.mode} subworkers=${snapshot.subworkers} " +
                        "detail=${snapshot.detail ?: "-"}"
                )
                lastPrinted = snapshot.downloadedBytes
            }
            when (snapshot.status) {
                EngineStatus.COMPLETED -> {
                    val output = File(snapshot.outputPath)
                    println("completed: ${output.absolutePath} (${output.length()} bytes)")
                    println("SMOKE OK")
                    return
                }

                EngineStatus.FAILED -> {
                    System.err.println("SMOKE FAILED: ${snapshot.error}")
                    kotlin.system.exitProcess(1)
                }

                else -> Thread.sleep(300)
            }
        }
        System.err.println("SMOKE FAILED: timeout")
        kotlin.system.exitProcess(1)
    } finally {
        engine.close()
        root.deleteRecursively()
    }
}

private const val SMOKE_TIMEOUT_MS = 120_000L

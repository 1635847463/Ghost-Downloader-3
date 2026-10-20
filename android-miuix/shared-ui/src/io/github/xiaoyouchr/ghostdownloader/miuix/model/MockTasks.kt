package io.github.xiaoyouchr.ghostdownloader.miuix.model

/** 演示用任务数据：数值对齐上游 README 里的 Android 截图，便于对比 Material 与 Miuix 观感。 */
object MockTasks {
    private const val DOWNLOAD_DIR = "/storage/emulated/0/Download"

    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB

    val tasks: List<DownloadTask> = listOf(
        DownloadTask(
            id = 1L,
            name = "Big Buck Bunny",
            folder = DOWNLOAD_DIR,
            category = "视频",
            status = TaskStatus.RUNNING,
            downloadedBytes = (406.4 * MB).toLong(),
            totalBytes = (1.2 * GB).toLong(),
            speedBytes = (29.2 * MB).toLong(),
            uploadSpeedBytes = (842.0 * 1024).toLong(),
            connections = 1,
            maxConnections = 6,
            shareRatio = 0.0,
            fileCount = 4,
            isTorrent = true
        ),
        DownloadTask(
            id = 2L,
            name = "ubuntu-26.04-desktop-amd64.iso",
            folder = DOWNLOAD_DIR,
            category = "软件",
            status = TaskStatus.RUNNING,
            downloadedBytes = (482.9 * MB).toLong(),
            totalBytes = (6.1 * GB).toLong(),
            speedBytes = (2.9 * MB).toLong(),
            connections = 4,
            maxConnections = 16,
            fileCount = 1
        ),
        DownloadTask(
            id = 3L,
            name = "blender-5.0.1-macos-arm64.dmg",
            folder = DOWNLOAD_DIR,
            category = "软件",
            status = TaskStatus.PAUSED,
            downloadedBytes = (256.1 * MB).toLong(),
            totalBytes = (304.1 * MB).toLong(),
            connections = 0,
            maxConnections = 16,
            fileCount = 1
        ),
        DownloadTask(
            id = 4L,
            name = "Ghost-Downloader-v4.4.3-Android-arm64-v8a.apk",
            folder = DOWNLOAD_DIR,
            status = TaskStatus.WAITING,
            totalBytes = 0L,
            fileCount = 1
        ),
        DownloadTask(
            id = 5L,
            name = "Ghost-Downloader-v4.4.3-Windows-x86_64.zip",
            folder = DOWNLOAD_DIR,
            category = "软件",
            status = TaskStatus.COMPLETED,
            downloadedBytes = (47.7 * MB).toLong(),
            totalBytes = (47.7 * MB).toLong(),
            fileCount = 1,
            completedAt = "2026-10-18 22:19"
        ),
        DownloadTask(
            id = 6L,
            name = "miuix-android-demo.zip",
            folder = DOWNLOAD_DIR,
            category = "压缩包",
            status = TaskStatus.COMPLETED,
            downloadedBytes = (12.4 * MB).toLong(),
            totalBytes = (12.4 * MB).toLong(),
            fileCount = 1,
            completedAt = "2026-10-18 21:04"
        ),
        DownloadTask(
            id = 7L,
            name = "Ghost-Downloader-v4.4.2-macOS-arm64.dmg",
            folder = DOWNLOAD_DIR,
            status = TaskStatus.FAILED,
            downloadedBytes = (18.2 * MB).toLong(),
            totalBytes = (69.2 * MB).toLong(),
            error = "连接超时，请检查网络后重试",
            fileCount = 1
        )
    )
}

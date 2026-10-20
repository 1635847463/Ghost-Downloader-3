package io.github.xiaoyouchr.ghostdownloader.miuix

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.AppDirs
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.HttpDownloadEngine
import io.github.xiaoyouchr.ghostdownloader.miuix.platform.DesktopPlatformFiles
import java.awt.Dimension
import java.io.File

/**
 * Desktop 入口：用于预览 Android 端 Miuix 界面，同时可直接验证内置下载引擎
 * （下载目录 `~/Downloads/GhostDownloader`，任务记录 `~/.ghostdownloader`）。
 *
 * 需要稳定截图时用 `gradlew :preview:screenshot`（无窗口渲染，不受 DPI/窗口装饰影响）。
 */
fun main() {
    val home = System.getProperty("user.home")
    val engine = HttpDownloadEngine(
        AppDirs(
            downloadDir = File(home, "Downloads/GhostDownloader"),
            dataDir = File(home, ".ghostdownloader")
        )
    )
    val platformFiles = DesktopPlatformFiles()
    try {
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Ghost Downloader · Miuix Preview",
                state = rememberWindowState(size = DpSize(420.dp, 900.dp))
            ) {
                window.minimumSize = Dimension(320, 640)
                App(engine = engine, platformFiles = platformFiles, bridgeTokenFile = File(home, ".ghostdownloader/bridge-token.txt"))
            }
        }
    } finally {
        engine.close()
    }
}

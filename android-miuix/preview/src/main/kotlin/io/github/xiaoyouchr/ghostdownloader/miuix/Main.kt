package io.github.xiaoyouchr.ghostdownloader.miuix

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

/**
 * Desktop 入口仅用于预览 Android 端的 Miuix 界面（窗口按手机比例 420x900）。
 * 真正的交付形态是同一份 shared-ui 源码打出的 Android APK。
 * 需要稳定截图时用 `gradlew :preview:screenshot`（无窗口渲染，不受 DPI/窗口装饰影响）。
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Ghost Downloader · Miuix Preview",
        state = rememberWindowState(size = DpSize(420.dp, 900.dp))
    ) {
        window.minimumSize = Dimension(320, 640)
        App()
    }
}

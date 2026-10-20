package io.github.xiaoyouchr.ghostdownloader.miuix

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.HttpDownloadEngine
import io.github.xiaoyouchr.ghostdownloader.miuix.platform.PlatformFiles
import io.github.xiaoyouchr.ghostdownloader.miuix.state.AppState
import io.github.xiaoyouchr.ghostdownloader.miuix.state.ThemeModeOption
import io.github.xiaoyouchr.ghostdownloader.miuix.theme.GhostMiuixTheme
import io.github.xiaoyouchr.ghostdownloader.miuix.ui.AppShell
import java.io.File
import kotlinx.coroutines.delay

/**
 * 应用根组件：Android 与 Desktop 预览共用。
 *
 * [engine] 为内置下载引擎（HTTP/HTTPS 多线程 + 断点续传），[platformFiles] 负责打开文件。
 * [bridgeTokenFile] 用于持久化浏览器扩展配对令牌；[onDarkModeChange] 同步系统状态栏颜色。
 */
@Composable
fun App(
    engine: HttpDownloadEngine? = null,
    platformFiles: PlatformFiles? = null,
    bridgeTokenFile: File? = null,
    onDarkModeChange: ((Boolean) -> Unit)? = null
) {
    val state = remember(engine) {
        AppState(engine = engine, platformFiles = platformFiles, bridgeTokenFile = bridgeTokenFile)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_INTERVAL_MS)
            state.onTick()
        }
    }

    // 浏览器扩展 WebSocket 桥接随组合生命周期启停
    DisposableEffect(state) {
        state.syncBridge()
        onDispose { state.close() }
    }

    val settings = state.settings
    val isDark = when (settings.themeMode) {
        ThemeModeOption.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeModeOption.LIGHT -> false
        ThemeModeOption.DARK -> true
    }
    val currentOnDarkModeChange by rememberUpdatedState(onDarkModeChange)
    LaunchedEffect(isDark) {
        currentOnDarkModeChange?.invoke(isDark)
    }

    GhostMiuixTheme(themeMode = settings.themeMode) {
        AppShell(state)
    }
}

private const val TICK_INTERVAL_MS = 500L

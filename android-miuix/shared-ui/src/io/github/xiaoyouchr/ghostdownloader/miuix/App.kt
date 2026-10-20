package io.github.xiaoyouchr.ghostdownloader.miuix

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.xiaoyouchr.ghostdownloader.miuix.state.AppState
import io.github.xiaoyouchr.ghostdownloader.miuix.state.ThemeModeOption
import io.github.xiaoyouchr.ghostdownloader.miuix.theme.GhostMiuixTheme
import io.github.xiaoyouchr.ghostdownloader.miuix.ui.AppShell
import kotlinx.coroutines.delay

/**
 * 应用根组件：Android 与 Desktop 预览共用。
 *
 * 数据层为演示用的 [AppState]（模拟下载进度），UI 全部由 Miuix 组件构成。
 * [onDarkModeChange] 供 Android 端同步系统状态栏 / 导航栏图标颜色。
 */
@Composable
fun App(onDarkModeChange: ((Boolean) -> Unit)? = null) {
    val state = remember { AppState() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            state.advance(deltaSeconds = 1f)
        }
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

    GhostMiuixTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor
    ) {
        AppShell(state)
    }
}

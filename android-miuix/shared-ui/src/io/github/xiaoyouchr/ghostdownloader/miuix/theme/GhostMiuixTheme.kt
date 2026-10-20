package io.github.xiaoyouchr.ghostdownloader.miuix.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.xiaoyouchr.ghostdownloader.miuix.state.ThemeModeOption
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 主题：仅保留浅色 / 深色 / 跟随系统（按要求移除 Monet 动态取色）。
 * 使用 Miuix 自带的 HyperOS 色板。
 */
@Composable
fun GhostMiuixTheme(
    themeMode: ThemeModeOption,
    content: @Composable () -> Unit
) {
    val mode = when (themeMode) {
        ThemeModeOption.FOLLOW_SYSTEM -> ColorSchemeMode.System
        ThemeModeOption.LIGHT -> ColorSchemeMode.Light
        ThemeModeOption.DARK -> ColorSchemeMode.Dark
    }
    val controller = remember(mode) { ThemeController(colorSchemeMode = mode) }
    MiuixTheme(controller = controller, content = content)
}

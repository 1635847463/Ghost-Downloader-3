package io.github.xiaoyouchr.ghostdownloader.miuix.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.github.xiaoyouchr.ghostdownloader.miuix.state.ThemeModeOption
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Ghost Downloader 的品牌色（与桌面端默认强调色一致）。 */
val GhostKeyColor = Color(0xFF3482FF)

/**
 * 把「主题模式 + 动态取色」映射到 Miuix 的 [ColorSchemeMode]。
 *
 * - 动态取色开启：Android 12+ 用 Monet 取系统壁纸色（MonetSystem/MonetLight/MonetDark）
 * - 动态取色关闭：使用 Miuix 自带的 HyperOS 浅色 / 深色色板
 */
@Composable
fun GhostMiuixTheme(
    themeMode: ThemeModeOption,
    dynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    val mode = when (themeMode) {
        ThemeModeOption.FOLLOW_SYSTEM -> if (dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
        ThemeModeOption.LIGHT -> if (dynamicColor) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
        ThemeModeOption.DARK -> if (dynamicColor) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
    }
    val controller = remember(mode) {
        ThemeController(
            colorSchemeMode = mode,
            keyColor = GhostKeyColor
        )
    }
    MiuixTheme(controller = controller, content = content)
}

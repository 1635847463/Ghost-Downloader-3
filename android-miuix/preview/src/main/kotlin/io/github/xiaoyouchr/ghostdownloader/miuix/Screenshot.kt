package io.github.xiaoyouchr.ghostdownloader.miuix

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.xiaoyouchr.ghostdownloader.miuix.state.AppState
import io.github.xiaoyouchr.ghostdownloader.miuix.state.AppTab
import io.github.xiaoyouchr.ghostdownloader.miuix.state.TaskFilter
import io.github.xiaoyouchr.ghostdownloader.miuix.state.ThemeModeOption
import io.github.xiaoyouchr.ghostdownloader.miuix.theme.GhostMiuixTheme
import io.github.xiaoyouchr.ghostdownloader.miuix.ui.AppShell
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * 无窗口渲染 Miuix 界面截图（供 README / 验收使用）。
 *
 * 使用 Compose Desktop 的 [ImageComposeScene]，按 420x900dp、density=2 输出 840x1800 PNG；
 * 会推进若干帧时间，让 Overlay 弹层 / 开关等动画进入稳定状态。
 */
fun main() {
    val outDir = File("screenshots").apply { mkdirs() }
    val width = 420 * 2
    val height = 900 * 2

    fun render(
        name: String,
        themeMode: ThemeModeOption,
        prepare: (AppState) -> Unit = {}
    ) {
        val state = AppState(demo = true)
        prepare(state)
        val scene = ImageComposeScene(
            width = width,
            height = height,
            density = Density(2f)
        ) {
            GhostMiuixTheme(themeMode = themeMode, dynamicColor = false) {
                AppShell(state)
            }
        }
        try {
            // 推进 0 -> 1.2s，覆盖 Overlay 的入场动画
            scene.render(0L)
            scene.render(100_000_000L)
            scene.render(300_000_000L)
            scene.render(600_000_000L)
            val image = scene.render(1_200_000_000L)
            val data = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("encodeToData returned null for $name")
            File(outDir, "$name.png").writeBytes(data.bytes)
            println("written: ${File(outDir, "$name.png").absolutePath}")
        } finally {
            scene.close()
        }
    }

    render("tasks-dark", ThemeModeOption.DARK)
    render("tasks-light", ThemeModeOption.LIGHT)
    render("tasks-completed-dark", ThemeModeOption.DARK) { it.selectFilter(TaskFilter.COMPLETED) }
    render("settings-dark", ThemeModeOption.DARK) { it.selectTab(AppTab.SETTINGS) }
    render("settings-light", ThemeModeOption.LIGHT) { it.selectTab(AppTab.SETTINGS) }
    render("add-task-dark", ThemeModeOption.DARK) { it.openAddTask() }
    render("add-task-light", ThemeModeOption.LIGHT) { it.openAddTask() }
    render("about-dark", ThemeModeOption.DARK) { it.openAbout() }

    println("screenshots written to ${outDir.absolutePath}")
}

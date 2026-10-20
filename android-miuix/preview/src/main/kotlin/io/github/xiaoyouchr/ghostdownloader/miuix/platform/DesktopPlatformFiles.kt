package io.github.xiaoyouchr.ghostdownloader.miuix.platform

import java.awt.Desktop
import java.io.File

/** Desktop 端文件操作：直接调用系统默认程序打开。 */
class DesktopPlatformFiles : PlatformFiles {

    override fun openFile(path: String): Boolean = runCatching {
        val file = File(path)
        if (!file.exists() || !Desktop.isDesktopSupported()) return false
        Desktop.getDesktop().open(file)
        true
    }.getOrDefault(false)

    override fun openFolder(path: String): Boolean = runCatching {
        val folder = File(path)
        if (!folder.exists() || !Desktop.isDesktopSupported()) return false
        Desktop.getDesktop().open(folder)
        true
    }.getOrDefault(false)
}

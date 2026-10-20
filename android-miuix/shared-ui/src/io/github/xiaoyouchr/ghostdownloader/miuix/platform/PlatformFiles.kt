package io.github.xiaoyouchr.ghostdownloader.miuix.platform

/**
 * 平台相关的文件操作：Android 通过 FileProvider + Intent，Desktop 通过 java.awt.Desktop。
 * 由各端入口实现并注入 UI。
 */
interface PlatformFiles {
    /** 用系统默认应用打开下载完成的文件。 */
    fun openFile(path: String): Boolean

    /** 在系统文件管理器里打开下载目录。 */
    fun openFolder(path: String): Boolean
}

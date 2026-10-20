package io.github.xiaoyouchr.ghostdownloader.miuix.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeModeOption(val label: String) {
    FOLLOW_SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

enum class NameConflictOption(val label: String) {
    KEEP_BOTH("保留两者"),
    OVERWRITE("覆盖"),
    ASK("询问")
}

/** 设置页的数据层。默认值与上游 app/config/cfg.py 保持一致，便于后续接真实配置。 */
class AppSettings {
    var themeMode by mutableStateOf(ThemeModeOption.FOLLOW_SYSTEM)
    var dynamicColor by mutableStateOf(true)
    var language by mutableStateOf("简体中文")

    var downloadFolder by mutableStateOf("/storage/emulated/0/Download")
    var maxTaskNum by mutableStateOf(3)
    var preBlockNum by mutableStateOf(8)
    var autoSpeedUp by mutableStateOf(true)
    var preserveLastModified by mutableStateOf(false)
    var verifySsl by mutableStateOf(false)
    var nameConflict by mutableStateOf(NameConflictOption.KEEP_BOTH)
    var maxReassignSizeKb by mutableStateOf(512)
    var useSystemDns by mutableStateOf(true)

    var enableCategory by mutableStateOf(false)
    var categoryCount by mutableStateOf(4)

    var browserExtensionEnabled by mutableStateOf(true)
    var browserExtensionPort by mutableStateOf(14370)
    var pairToken by mutableStateOf("GD3-8F2C-4E71")

    var autoCheckUpdate by mutableStateOf(true)
    var appVersion by mutableStateOf("4.4.3")
}

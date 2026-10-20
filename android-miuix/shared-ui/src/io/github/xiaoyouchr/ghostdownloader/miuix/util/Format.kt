package io.github.xiaoyouchr.ghostdownloader.miuix.util

import kotlin.math.roundToLong

private val sizeUnits = listOf("KB", "MB", "GB", "TB")

private fun oneDecimal(value: Double): String {
    val scaled = (value * 10.0).roundToLong()
    return "${scaled / 10}.${scaled % 10}"
}

/** 与桌面端 toReadableSize 的 1024 进制一致，移动端保留一位小数以对齐卡片宽度。 */
fun formatSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    var value = bytes / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < sizeUnits.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return "${oneDecimal(value)} ${sizeUnits[unitIndex]}"
}

/** 下载速度，例如 29.2 MB/s。 */
fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0L) "0 B/s" else "${formatSize(bytesPerSecond)}/s"

/** 进度条的尺寸文案，例如 406.4 MB/1.2 GB。 */
fun formatSizeProgress(downloaded: Long, total: Long): String =
    if (total <= 0L) "${formatSize(downloaded)}/--" else "${formatSize(downloaded)}/${formatSize(total)}"

/** 剩余时间，与桌面端 toReadableTime 保持一致。 */
fun formatRemainingTime(seconds: Long): String {
    if (seconds <= 0L) return "--"
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m${seconds % 60}s"
        else -> "${seconds / 3600}h${(seconds % 3600) / 60}m${seconds % 60}s"
    }
}

fun formatPercent(percent: Int): String = "$percent%"

fun formatShareRatio(ratio: Double): String {
    val scaled = (ratio * 10.0).roundToLong()
    return "${scaled / 10}.${scaled % 10}%"
}

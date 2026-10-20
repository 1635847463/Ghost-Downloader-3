package io.github.xiaoyouchr.ghostdownloader.miuix.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaoyouchr.ghostdownloader.miuix.state.AppState
import io.github.xiaoyouchr.ghostdownloader.miuix.state.NameConflictOption
import io.github.xiaoyouchr.ghostdownloader.miuix.state.ThemeModeOption
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页：Material3 的 ListItem / Switch / Slider 换成
 * Miuix 的 BasicComponent / ArrowPreference / SwitchPreference / SliderPreference。
 */
@Composable
fun SettingsScreen(state: AppState, contentPadding: PaddingValues) {
    val settings = state.settings
    var themeDialog by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf(false) }
    var conflictDialog by remember { mutableStateOf(false) }
    var tokenDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 5.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp
        )
    ) {
        item(key = "appearance") {
            SmallTitle(text = "外观与个性化")
            SettingsCard {
                ArrowPreference(
                    title = "主题模式",
                    summary = "跟随系统或固定浅色 / 深色",
                    endActions = {
                        Text(
                            text = settings.themeMode.label,
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    },
                    onClick = { themeDialog = true }
                )
                SwitchPreference(
                    title = "动态取色",
                    summary = "由壁纸生成 Miuix 配色（Android 12+ Monet）",
                    checked = settings.dynamicColor,
                    onCheckedChange = { settings.dynamicColor = it }
                )
                ArrowPreference(
                    title = "语言",
                    endActions = {
                        Text(
                            text = settings.language,
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    },
                    onClick = { languageDialog = true }
                )
            }
        }

        item(key = "download") {
            SmallTitle(text = "综合下载设置")
            SettingsCard {
                ArrowPreference(
                    title = "下载路径",
                    summary = settings.downloadFolder
                )
                SliderPreference(
                    title = "最大任务数",
                    summary = "最多能同时进行的任务数量",
                    value = settings.maxTaskNum.toFloat(),
                    valueRange = 1f..10f,
                    steps = 8,
                    valueText = "${settings.maxTaskNum}",
                    onValueChange = { settings.maxTaskNum = it.roundToInt() }
                )
                SliderPreference(
                    title = "预分配线程数",
                    summary = "每个任务的分片连接数（引擎上限 16）",
                    value = settings.preBlockNum.toFloat(),
                    valueRange = 1f..16f,
                    steps = 14,
                    valueText = "${settings.preBlockNum}",
                    onValueChange = { settings.preBlockNum = it.roundToInt() }
                )
                SwitchPreference(
                    title = "自动提速",
                    summary = "实时检测各线程效率并自动增加线程数",
                    checked = settings.autoSpeedUp,
                    onCheckedChange = { settings.autoSpeedUp = it }
                )
                SliderPreference(
                    title = "最小再分配大小",
                    value = settings.maxReassignSizeKb.toFloat(),
                    valueRange = 64f..1024f,
                    steps = 15,
                    valueText = "${settings.maxReassignSizeKb} KB",
                    onValueChange = { settings.maxReassignSizeKb = it.roundToInt() }
                )
                SwitchPreference(
                    title = "保留文件修改时间",
                    summary = "使用服务器提供的 Last-Modified 值",
                    checked = settings.preserveLastModified,
                    onCheckedChange = { settings.preserveLastModified = it }
                )
                SwitchPreference(
                    title = "下载时验证 SSL 证书",
                    summary = "文件无法下载时，可尝试关闭该选项",
                    checked = settings.verifySsl,
                    onCheckedChange = { settings.verifySsl = it }
                )
                ArrowPreference(
                    title = "同名文件",
                    summary = "下载目录中已有同名文件时的处理方式",
                    endActions = {
                        Text(
                            text = settings.nameConflict.label,
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    },
                    onClick = { conflictDialog = true }
                )
                SwitchPreference(
                    title = "使用系统 DNS",
                    summary = "兼容 TUN、VPN 和代理等网络环境",
                    checked = settings.useSystemDns,
                    onCheckedChange = { settings.useSystemDns = it }
                )
            }
        }

        item(key = "category") {
            SmallTitle(text = "下载分类")
            SettingsCard {
                SwitchPreference(
                    title = "启用下载分类",
                    summary = "根据扩展名将下载任务归类，便于筛选",
                    checked = settings.enableCategory,
                    onCheckedChange = { settings.enableCategory = it }
                )
                ArrowPreference(
                    title = "管理分类规则",
                    summary = "当前共 ${settings.categoryCount} 个分类"
                )
            }
        }

        item(key = "browser") {
            SmallTitle(text = "浏览器扩展")
            SettingsCard {
                SwitchPreference(
                    title = "启用浏览器扩展",
                    summary = "接收浏览器扩展发来的链接和文件",
                    checked = settings.browserExtensionEnabled,
                    onCheckedChange = { settings.browserExtensionEnabled = it }
                )
                ArrowPreference(
                    title = "服务端口",
                    endActions = {
                        Text(
                            text = "${settings.browserExtensionPort}",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                )
                ArrowPreference(
                    title = "配对令牌",
                    summary = "点击查看，用于浏览器扩展配对",
                    onClick = { tokenDialog = true }
                )
            }
        }

        item(key = "software") {
            SmallTitle(text = "应用")
            SettingsCard {
                SwitchPreference(
                    title = "自动检查更新",
                    summary = "启动时检查新版本",
                    checked = settings.autoCheckUpdate,
                    onCheckedChange = { settings.autoCheckUpdate = it }
                )
                ArrowPreference(
                    title = "关于",
                    summary = "Ghost Downloader ${settings.appVersion}",
                    onClick = state::openAbout
                )
            }
        }
    }

    OptionPickerDialog(
        show = themeDialog,
        title = "主题模式",
        options = ThemeModeOption.entries.map { it.label },
        selectedIndex = settings.themeMode.ordinal,
        onSelect = { settings.themeMode = ThemeModeOption.entries[it] },
        onDismiss = { themeDialog = false }
    )

    OptionPickerDialog(
        show = languageDialog,
        title = "语言",
        options = listOf("简体中文", "繁體中文", "English"),
        selectedIndex = 0,
        onSelect = { },
        onDismiss = { languageDialog = false }
    )

    OptionPickerDialog(
        show = conflictDialog,
        title = "同名文件",
        options = NameConflictOption.entries.map { it.label },
        selectedIndex = settings.nameConflict.ordinal,
        onSelect = { settings.nameConflict = NameConflictOption.entries[it] },
        onDismiss = { conflictDialog = false }
    )

    OverlayDialog(
        show = tokenDialog,
        title = "配对令牌",
        summary = "在浏览器扩展里填入该令牌即可与本机配对",
        onDismissRequest = { tokenDialog = false }
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                text = settings.pairToken,
                fontSize = 22.sp,
                color = MiuixTheme.colorScheme.primary
            )
            Text(
                text = "令牌仅保存在本机",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp)
    ) {
        content()
    }
}

@Composable
private fun OptionPickerDialog(
    show: Boolean,
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            options.forEachIndexed { index, option ->
                BasicComponent(
                    title = option,
                    endActions = {
                        if (index == selectedIndex) {
                            Icon(
                                imageVector = MiuixIcons.Ok,
                                contentDescription = "已选择",
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        onSelect(index)
                        onDismiss()
                    }
                )
            }
        }
    }
}

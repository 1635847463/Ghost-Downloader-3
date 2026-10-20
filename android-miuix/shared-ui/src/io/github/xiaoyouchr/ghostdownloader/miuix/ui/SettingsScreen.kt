package io.github.xiaoyouchr.ghostdownloader.miuix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
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
    var portDialog by remember { mutableStateOf(false) }
    var portInput by remember { mutableStateOf("") }

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
                    summary = "按扩展名自动保存到 视频 / 音频 / 软件 / 压缩包 / 图片 / 文档 子目录",
                    checked = settings.enableCategory,
                    onCheckedChange = { settings.enableCategory = it }
                )
                ArrowPreference(
                    title = "分类规则",
                    summary = "视频 · 音频 · 软件 · 压缩包 · 图片 · 文档（内置 6 类）"
                )
            }
        }

        item(key = "browser") {
            SmallTitle(text = "浏览器扩展")
            SettingsCard {
                SwitchPreference(
                    title = "启用浏览器扩展",
                    summary = "接收浏览器扩展发来的链接和文件（WebSocket 桥接）",
                    checked = settings.browserExtensionEnabled,
                    onCheckedChange = { settings.browserExtensionEnabled = it }
                )
                ArrowPreference(
                    title = "服务端口",
                    summary = "扩展连接地址 ws://<手机IP>:${settings.browserExtensionPort}（或 adb reverse）",
                    endActions = {
                        Text(
                            text = "${settings.browserExtensionPort}",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    },
                    onClick = {
                        portInput = "${settings.browserExtensionPort}"
                        portDialog = true
                    }
                )
                ArrowPreference(
                    title = "配对令牌",
                    summary = "扩展首次连接需要输入该令牌",
                    onClick = { tokenDialog = true }
                )
                BasicComponent(
                    title = "连接状态",
                    summary = when {
                        !settings.browserExtensionEnabled -> "已关闭"
                        state.bridgeError != null -> "启动失败：${state.bridgeError}"
                        state.bridgeConnected -> "扩展已连接 · 端口 ${state.bridgePort}"
                        state.bridgePort > 0 -> "等待扩展连接 · 端口 ${state.bridgePort}"
                        else -> "未启动"
                    }
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = "重新生成",
                    onClick = { state.regeneratePairToken() },
                    modifier = Modifier.width(104.dp)
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { tokenDialog = false },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.width(88.dp)
                ) {
                    Text("关闭", fontSize = 15.sp)
                }
            }
        }
    }

    OverlayDialog(
        show = portDialog,
        title = "服务端口",
        summary = "浏览器扩展通过 WebSocket 连接该端口（1024-65535）",
        onDismissRequest = { portDialog = false }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            TextField(
                value = portInput,
                onValueChange = { portInput = it.filter { ch -> ch.isDigit() } },
                label = "端口",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = "取消",
                    onClick = { portDialog = false },
                    modifier = Modifier.width(78.dp)
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        portInput.toIntOrNull()?.takeIf { it in 1024..65535 }?.let {
                            settings.browserExtensionPort = it
                            portDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.width(88.dp)
                ) {
                    Text("确定", fontSize = 15.sp)
                }
            }
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

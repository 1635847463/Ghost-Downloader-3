package io.github.xiaoyouchr.ghostdownloader.miuix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaoyouchr.ghostdownloader.miuix.state.AppState
import io.github.xiaoyouchr.ghostdownloader.miuix.util.formatSize
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 新建任务抽屉（加高版）：
 * 链接 → 可调选项（保存路径 / 分片线程数 / 分类）→ 解析 → 展示解析出的文件 → 确认下载。
 * 解析支持普通 HTTP(S)、哔哩哔哩、YouTube；磁力/BT/eD2k 会给出运行时缺失提示。
 */
@Composable
fun AddTaskSheet(state: AppState) {
    val scope = rememberCoroutineScope()
    var folderEditing by remember { mutableStateOf(false) }
    var folderInput by remember { mutableStateOf("") }

    OverlayBottomSheet(
        show = state.showAddTaskSheet,
        title = "新建任务",
        onDismissRequest = state::closeAddTask
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 460.dp)
                .padding(horizontal = 16.dp)
        ) {
            TextField(
                value = state.draftUrl,
                onValueChange = { state.draftUrl = it },
                label = "粘贴下载链接（HTTP / Bilibili / YouTube）",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            state.draftError?.let { message ->
                Text(
                    text = message,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(10.dp))
            ArrowPreference(
                title = "保存路径",
                summary = state.draftFolder,
                insideMargin = PaddingValues(horizontal = 0.dp, vertical = 10.dp),
                onClick = {
                    folderInput = state.draftFolder
                    folderEditing = true
                }
            )
            SliderPreference(
                title = "分片线程数",
                summary = "该任务使用的并发连接数（1-16）",
                value = state.draftSubworkerCount.toFloat(),
                valueRange = 1f..16f,
                steps = 14,
                valueText = "${state.draftSubworkerCount}",
                onValueChange = { state.updateDraftSubworkerCount(it.roundToInt()) }
            )
            BasicComponent(
                title = "分类",
                summary = state.draftCategory ?: "未识别（分类关闭或扩展名未知）",
                insideMargin = PaddingValues(horizontal = 0.dp, vertical = 10.dp)
            )

            if (state.draftParsed) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(14.dp)
                ) {
                    Text(
                        text = state.draftName,
                        fontSize = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            val probe = state.draftProbe
                            if (probe != null && probe.totalBytes > 0L) {
                                append(formatSize(probe.totalBytes))
                                append(" · ")
                            }
                            append(
                                when {
                                    probe == null -> "已解析"
                                    probe.canUseRange -> "分片下载（支持断点续传）"
                                    probe.totalBytes > 0L -> "单线程下载"
                                    else -> "大小未知，单线程下载"
                                }
                            )
                            state.draftCategory?.let { append(" · $it") }
                        },
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    state.draftNote?.let { note ->
                        Text(
                            text = note,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            } else if (!state.draftBusy) {
                Text(
                    text = "支持 HTTP / HTTPS、哔哩哔哩、YouTube 链接；磁力/BT/eD2k 需要额外运行时。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            // 按钮改为固定小宽度、右对齐、间距加大
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    text = "取消",
                    onClick = state::closeAddTask,
                    modifier = Modifier.width(78.dp)
                )
                Spacer(Modifier.width(18.dp))
                Button(
                    onClick = {
                        if (state.draftParsed) {
                            state.confirmDraft()
                        } else {
                            scope.launch { state.parseDraft() }
                        }
                    },
                    enabled = !state.draftBusy,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.width(138.dp)
                ) {
                    Text(
                        text = when {
                            state.draftBusy -> "解析中…"
                            state.draftParsed -> "开始下载"
                            else -> "解析链接"
                        },
                        fontSize = 15.sp
                    )
                }
            }
        }
    }

    if (folderEditing) {
        OverlayDialog(
            show = folderEditing,
            title = "保存路径",
            summary = "下载文件将保存到该目录（分类开启时会再建分类子目录）",
            onDismissRequest = { folderEditing = false }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                TextField(
                    value = folderInput,
                    onValueChange = { folderInput = it },
                    label = "目录绝对路径",
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
                        onClick = { folderEditing = false },
                        modifier = Modifier.width(78.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            state.updateDraftFolder(folderInput)
                            folderEditing = false
                        },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.width(96.dp)
                    ) {
                        Text("确定", fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

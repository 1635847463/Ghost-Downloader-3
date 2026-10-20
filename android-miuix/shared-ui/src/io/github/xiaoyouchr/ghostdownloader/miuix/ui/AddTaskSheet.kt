package io.github.xiaoyouchr.ghostdownloader.miuix.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaoyouchr.ghostdownloader.miuix.state.AppState
import io.github.xiaoyouchr.ghostdownloader.miuix.util.formatSize
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 新建任务：粘贴链接 → 引擎探测（文件名 / 大小 / 是否支持断点续传）→ 创建 Task 并开始下载。
 */
@Composable
fun AddTaskSheet(state: AppState) {
    val scope = rememberCoroutineScope()

    OverlayBottomSheet(
        show = state.showAddTaskSheet,
        title = "新建任务",
        onDismissRequest = state::closeAddTask
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            TextField(
                value = state.draftUrl,
                onValueChange = { state.draftUrl = it },
                label = "粘贴下载链接（HTTP / HTTPS）",
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

            if (state.draftParsed) {
                val probe = state.draftProbe
                Text(
                    text = buildString {
                        append("已识别任务")
                        if (probe != null && probe.totalBytes > 0L) {
                            append(" · ")
                            append(formatSize(probe.totalBytes))
                        }
                        if (probe != null && probe.resumable) {
                            append(" · 支持断点续传")
                        }
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                TextField(
                    value = state.draftName,
                    onValueChange = { state.draftName = it },
                    label = "文件名",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ArrowPreference(
                    title = "保存到",
                    summary = state.draftFolder,
                    insideMargin = PaddingValues(horizontal = 0.dp, vertical = 12.dp)
                )
                state.draftCategory?.let {
                    ArrowPreference(
                        title = "分类",
                        endActions = {
                            Text(text = it, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    )
                }
            } else {
                Text(
                    text = "支持 HTTP / HTTPS 链接，自动多线程分块下载；暂停后可继续（服务器支持 Range 时）。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                TextButton(
                    text = "取消",
                    onClick = state::closeAddTask,
                    modifier = Modifier.weight(1f)
                )
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
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = when {
                            state.draftBusy -> "解析中…"
                            state.draftParsed -> "开始下载"
                            else -> "解析链接"
                        }
                    )
                }
            }
        }
    }
}

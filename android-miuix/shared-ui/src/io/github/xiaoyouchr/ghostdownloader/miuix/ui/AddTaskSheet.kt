package io.github.xiaoyouchr.ghostdownloader.miuix.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaoyouchr.ghostdownloader.miuix.state.AppState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 新建任务：对应桌面端的 Task Draft 流程，用 Miuix BottomSheet + TextField 还原。
 */
@Composable
fun AddTaskSheet(state: AppState) {
    OverlayBottomSheet(
        show = state.showAddTaskSheet,
        title = "新建任务",
        onDismissRequest = state::closeAddTask
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            TextField(
                value = state.draftUrl,
                onValueChange = { state.draftUrl = it },
                label = "粘贴下载链接（HTTP / 磁力 / FTP …）",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (state.draftParsed) {
                Text(
                    text = "已识别到任务",
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
                            state.parseDraft()
                        }
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = if (state.draftParsed) "开始下载" else "解析链接")
                }
            }
        }
    }
}

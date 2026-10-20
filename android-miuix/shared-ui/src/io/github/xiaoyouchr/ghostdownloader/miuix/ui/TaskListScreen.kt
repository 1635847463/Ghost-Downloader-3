package io.github.xiaoyouchr.ghostdownloader.miuix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaoyouchr.ghostdownloader.miuix.model.DownloadTask
import io.github.xiaoyouchr.ghostdownloader.miuix.model.TaskStatus
import io.github.xiaoyouchr.ghostdownloader.miuix.state.AppState
import io.github.xiaoyouchr.ghostdownloader.miuix.state.TaskFilter
import io.github.xiaoyouchr.ghostdownloader.miuix.util.formatSizeProgress
import io.github.xiaoyouchr.ghostdownloader.miuix.util.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 任务列表页：数据来自内置下载引擎（或演示数据），
 * 支持暂停/继续、重试、打开文件、删除任务（可选删文件）。
 */
@Composable
fun TaskListScreen(
    state: AppState,
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope
) {
    val visible = state.visibleTasks(state.taskFilter)
    val unfinished = visible.filter { it.status != TaskStatus.COMPLETED }
    val completed = visible.filter { it.status == TaskStatus.COMPLETED }
    var actionTask by remember { mutableStateOf<DownloadTask?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 5.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp
        )
    ) {
        item(key = "filter") {
            TabRow(
                tabs = TaskFilter.entries.map { it.label },
                selectedTabIndex = state.taskFilter.ordinal,
                onTabSelected = { index -> state.selectFilter(TaskFilter.entries[index]) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        if (unfinished.isNotEmpty()) {
            item(key = "unfinished-title") {
                SmallTitle(text = "未完成  ${unfinished.size}")
            }
            items(items = unfinished, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onToggle = {
                        state.toggleTask(task.id)
                        scope.launch {
                            val text = when (task.status) {
                                TaskStatus.RUNNING -> "已暂停「${task.name}」"
                                TaskStatus.FAILED -> "正在重试「${task.name}」"
                                else -> "已开始「${task.name}」"
                            }
                            snackbarHostState.showSnackbar(text)
                        }
                    },
                    onMore = { actionTask = task },
                    onOpen = {
                        if (!state.openFile(task)) {
                            scope.launch { snackbarHostState.showSnackbar("下载存储在 ${task.folder}") }
                        }
                    }
                )
            }
        }

        if (completed.isNotEmpty()) {
            item(key = "completed-title") {
                SmallTitle(text = "已完成  ${completed.size}")
            }
            items(items = completed, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onToggle = {
                        state.redownloadTask(task.id)
                        scope.launch { snackbarHostState.showSnackbar("已重新下载「${task.name}」") }
                    },
                    onMore = { actionTask = task },
                    onOpen = {
                        if (!state.openFile(task)) {
                            scope.launch { snackbarHostState.showSnackbar("下载存储在 ${task.folder}") }
                        }
                    }
                )
            }
        }

        if (visible.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            state.searchQuery.isNotBlank() -> "没有匹配「${state.searchQuery}」的任务"
                            state.taskFilter == TaskFilter.COMPLETED -> "暂无已完成的任务"
                            else -> "暂无任务，点右下角 + 新建"
                        },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }

    OverlayBottomSheet(
        show = actionTask != null,
        title = actionTask?.name,
        onDismissRequest = { actionTask = null }
    ) {
        val task = actionTask ?: return@OverlayBottomSheet

        if (task.status == TaskStatus.COMPLETED) {
            BasicComponent(
                title = "打开文件",
                summary = task.outputPath,
                onClick = {
                    if (!state.openFile(task)) {
                        scope.launch { snackbarHostState.showSnackbar("无法打开文件，路径：${task.folder}") }
                    }
                    actionTask = null
                }
            )
            BasicComponent(
                title = "打开所在文件夹",
                onClick = {
                    if (!state.openFolder(task)) {
                        scope.launch { snackbarHostState.showSnackbar("下载存储在 ${task.folder}") }
                    }
                    actionTask = null
                }
            )
            BasicComponent(
                title = "重新下载",
                summary = "删除成品与进度文件后从头开始",
                onClick = {
                    state.redownloadTask(task.id)
                    actionTask = null
                    scope.launch { snackbarHostState.showSnackbar("已重新下载「${task.name}」") }
                }
            )
        }

        if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.WAITING) {
            BasicComponent(
                title = "暂停",
                summary = "保留已下载的分块，稍后可继续",
                onClick = {
                    state.toggleTask(task.id)
                    actionTask = null
                }
            )
        } else if (task.status != TaskStatus.COMPLETED) {
            BasicComponent(
                title = if (task.status == TaskStatus.FAILED) "重试下载" else "继续下载",
                summary = task.error,
                onClick = {
                    state.toggleTask(task.id)
                    actionTask = null
                }
            )
        }

        BasicComponent(
            title = "删除任务（保留文件）",
            summary = "未完成的分块文件会被清理",
            onClick = {
                state.removeTask(task.id)
                actionTask = null
                scope.launch { snackbarHostState.showSnackbar("已删除「${task.name}」") }
            }
        )
        BasicComponent(
            title = "删除任务并删除文件",
            summary = "同时删除已下载的成品文件",
            onClick = {
                state.removeTaskWithFiles(task.id)
                actionTask = null
                scope.launch { snackbarHostState.showSnackbar("已删除「${task.name}」及其文件") }
            }
        )
    }
}

@Composable
private fun TaskCard(
    task: DownloadTask,
    onToggle: () -> Unit,
    onMore: () -> Unit,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        insideMargin = PaddingValues(16.dp),
        onClick = if (task.status == TaskStatus.COMPLETED) onOpen else null
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.name,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (task.status == TaskStatus.RUNNING) {
                Text(
                    text = formatSpeed(task.speedBytes),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = "${task.percent}%",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }

        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusText(task),
                fontSize = 13.sp,
                color = statusColor(task)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatSizeProgress(task.downloadedBytes, task.totalBytes),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }

        val indeterminate = task.status == TaskStatus.RUNNING && task.totalBytes <= 0L
        LinearProgressIndicator(
            progress = when {
                indeterminate -> null
                task.totalBytes > 0L -> task.progress
                else -> 0f
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 8.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (task.status == TaskStatus.RUNNING) {
                Text(
                    text = "连接 ${task.connections.coerceAtLeast(1)}/${task.maxConnections.coerceAtLeast(1)}",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = task.modeLabel.ifBlank { "分片下载" },
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            if (task.category != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = task.category,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            Spacer(Modifier.weight(1f))
            if (task.status == TaskStatus.COMPLETED) {
                Text(
                    text = "点击打开",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary
                )
            }
        }

        task.detail?.let { detail ->
            Text(
                text = detail,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        task.error?.let { message ->
            Text(
                text = message,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val running = task.status == TaskStatus.RUNNING || task.status == TaskStatus.WAITING
            Button(
                onClick = onToggle,
                colors = ButtonDefaults.buttonColors(),
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = if (running) MiuixIcons.Pause else MiuixIcons.Play,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when {
                        running -> "暂停"
                        task.status == TaskStatus.COMPLETED -> "重新下载"
                        task.status == TaskStatus.FAILED -> "重试"
                        else -> "继续"
                    },
                    fontSize = 14.sp
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onMore) {
                Icon(imageVector = MiuixIcons.More, contentDescription = "更多操作")
            }
        }
    }
}

private fun statusText(task: DownloadTask): String = when (task.status) {
    TaskStatus.RUNNING -> "下载中"
    TaskStatus.PAUSED -> "已暂停"
    TaskStatus.WAITING -> "等待中"
    TaskStatus.COMPLETED -> task.completedAt?.let { "完成于 $it" } ?: "任务已经完成"
    TaskStatus.FAILED -> "下载失败"
}

@Composable
private fun statusColor(task: DownloadTask) = when (task.status) {
    TaskStatus.RUNNING -> MiuixTheme.colorScheme.primary
    TaskStatus.FAILED -> MiuixTheme.colorScheme.error
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

package io.github.xiaoyouchr.ghostdownloader.miuix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import io.github.xiaoyouchr.ghostdownloader.miuix.util.formatShareRatio
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
 * 任务列表页：Material3 的 FilterChip / Card / LinearProgressIndicator / FAB
 * 全部替换为 Miuix 的 TabRow / Card / LinearProgressIndicator / FloatingActionButton。
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
                            val text = if (task.status == TaskStatus.RUNNING) "已暂停「${task.name}」" else "已继续「${task.name}」"
                            snackbarHostState.showSnackbar(text)
                        }
                    },
                    onMore = { actionTask = task }
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
                        state.toggleTask(task.id)
                        scope.launch { snackbarHostState.showSnackbar("已重新开始「${task.name}」") }
                    },
                    onMore = { actionTask = task }
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
                        text = if (state.searchQuery.isNotBlank()) "没有匹配「${state.searchQuery}」的任务" else "暂无任务",
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
        BasicComponent(
            title = "重新下载",
            summary = "清空进度后重新开始",
            onClick = {
                state.retryTask(task.id)
                actionTask = null
                scope.launch { snackbarHostState.showSnackbar("已重新开始「${task.name}」") }
            }
        )
        BasicComponent(
            title = "暂停 / 继续",
            onClick = {
                state.toggleTask(task.id)
                actionTask = null
            }
        )
        BasicComponent(
            title = "删除任务",
            summary = "不会删除已下载的文件",
            onClick = {
                state.removeTask(task.id)
                actionTask = null
                scope.launch { snackbarHostState.showSnackbar("已删除「${task.name}」") }
            }
        )
    }
}

@Composable
private fun TaskCard(task: DownloadTask, onToggle: () -> Unit, onMore: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        insideMargin = PaddingValues(16.dp)
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

        LinearProgressIndicator(
            progress = task.progress,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 8.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (task.isTorrent) {
                Text(
                    text = "↑ ${formatSpeed(task.uploadSpeedBytes)}",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = "${task.fileCount} 个文件",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.weight(1f))
            if (task.isTorrent && task.status == TaskStatus.RUNNING) {
                Text(
                    text = "连接 ${task.connections}/${task.maxConnections}",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "分享率 ${formatShareRatio(task.shareRatio)}",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
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
            val running = task.status == TaskStatus.RUNNING
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
                Text(text = if (running) "暂停" else if (task.status.isFinished) "重新下载" else "继续", fontSize = 14.sp)
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

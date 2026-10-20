package io.github.xiaoyouchr.ghostdownloader.miuix.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xiaoyouchr.ghostdownloader.miuix.state.AppState
import io.github.xiaoyouchr.ghostdownloader.miuix.state.AppTab
import io.github.xiaoyouchr.ghostdownloader.miuix.util.formatSpeed
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

/**
 * 应用外壳：SmallTopAppBar + 底部 NavigationBar + FAB，全部换成 Miuix 组件。
 * Material3 的 TopAppBar / NavigationBar / FloatingActionButton 在这里对应为同名 Miuix 组件。
 */
@Composable
fun AppShell(state: AppState) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showOverflowSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (state.currentTab == AppTab.SETTINGS) {
                SmallTopAppBar(title = "设置")
            } else {
                SmallTopAppBar(
                    title = "任务",
                    subtitle = if (state.totalSpeed > 0L) "↓ ${formatSpeed(state.totalSpeed)}" else "",
                    actions = {
                        IconButton(onClick = state::toggleSearch) {
                            Icon(imageVector = MiuixIcons.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = { showOverflowSheet = true }) {
                            Icon(imageVector = MiuixIcons.More, contentDescription = "更多")
                        }
                    },
                    bottomContent = {
                        if (state.searchVisible) {
                            TextField(
                                value = state.searchQuery,
                                onValueChange = state::updateSearchQuery,
                                label = "搜索任务名",
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.currentTab == AppTab.TASKS,
                    onClick = { state.selectTab(AppTab.TASKS) },
                    icon = MiuixIcons.Tasks,
                    label = "任务"
                )
                NavigationBarItem(
                    selected = state.currentTab == AppTab.SETTINGS,
                    onClick = { state.selectTab(AppTab.SETTINGS) },
                    icon = MiuixIcons.Settings,
                    label = "设置"
                )
            }
        },
        floatingActionButton = {
            if (state.currentTab == AppTab.TASKS) {
                FloatingActionButton(onClick = state::openAddTask) {
                    Icon(imageVector = MiuixIcons.Add, contentDescription = "新建任务")
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End,
        snackbarHost = { SnackbarHost(state = snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (state.currentTab) {
                AppTab.TASKS -> TaskListScreen(
                    state = state,
                    contentPadding = padding,
                    snackbarHostState = snackbarHostState,
                    scope = scope
                )
                AppTab.SETTINGS -> SettingsScreen(state = state, contentPadding = padding)
            }

            // Miuix 的 Overlay* 依赖 Scaffold 内部的 popupHost / CompositionLocal，
            // 必须放在 Scaffold 的 content 子树里，否则弹层不会渲染。
            OverlayBottomSheet(
                show = showOverflowSheet,
                title = "任务操作",
                onDismissRequest = { showOverflowSheet = false }
            ) {
                BasicComponent(
                    title = "全部开始",
                    onClick = {
                        state.startAll()
                        showOverflowSheet = false
                    }
                )
                BasicComponent(
                    title = "全部暂停",
                    onClick = {
                        state.pauseAll()
                        showOverflowSheet = false
                    }
                )
                BasicComponent(
                    title = "关于 Ghost Downloader",
                    onClick = {
                        showOverflowSheet = false
                        state.openAbout()
                    }
                )
            }

            AddTaskSheet(state = state)
            AboutDialog(state = state)
        }
    }
}

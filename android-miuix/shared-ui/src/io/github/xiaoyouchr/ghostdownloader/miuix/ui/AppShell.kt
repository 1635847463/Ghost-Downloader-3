package io.github.xiaoyouchr.ghostdownloader.miuix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaoyouchr.ghostdownloader.miuix.state.AppState
import io.github.xiaoyouchr.ghostdownloader.miuix.state.AppTab
import io.github.xiaoyouchr.ghostdownloader.miuix.util.formatSpeed
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用外壳：自绘左对齐大标题 TopBar + 底部 NavigationBar + FAB。
 *
 * 顶栏标题按要求左对齐并放大；右上角「三个点」使用弹出式菜单
 * （OverlayIconDropdownMenu，对应 Miuix 的 Window/Overlay 系列下拉菜单），
 * 不再展开底部抽屉。
 */
@Composable
fun AppShell(state: AppState) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            if (state.currentTab == AppTab.SETTINGS) {
                GhostTopBar(title = "设置")
            } else {
                val overflowEntry = remember(state) {
                    DropdownEntry(
                        items = listOf(
                            DropdownItem(text = "全部开始", onClick = state::startAll),
                            DropdownItem(text = "全部暂停", onClick = state::pauseAll),
                            DropdownItem(text = "关于 Ghost Downloader", onClick = state::openAbout)
                        )
                    )
                }
                GhostTopBar(
                    title = "任务",
                    subtitle = if (state.totalSpeed > 0L) "↓ ${formatSpeed(state.totalSpeed)}" else null,
                    showSearch = true,
                    searchVisible = state.searchVisible,
                    searchQuery = state.searchQuery,
                    onSearchQueryChange = state::updateSearchQuery,
                    actions = {
                        IconButton(onClick = state::toggleSearch) {
                            Icon(imageVector = MiuixIcons.Search, contentDescription = "搜索")
                        }
                        OverlayIconDropdownMenu(entry = overflowEntry) {
                            Icon(imageVector = MiuixIcons.More, contentDescription = "更多")
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
            AddTaskSheet(state = state)
            AboutDialog(state = state)

            val pairRequest = state.pendingPairRequest
            OverlayDialog(
                show = pairRequest != null,
                title = "浏览器扩展配对请求",
                summary = pairRequest?.let { request ->
                    val peer = request.peerAddress.ifBlank { "浏览器扩展" }
                    if (request.extensionVersion.isBlank()) peer
                    else "$peer · v${request.extensionVersion}"
                },
                onDismissRequest = { state.rejectPairRequest() }
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        text = "是否允许该扩展连接 Ghost Downloader？允许后配对令牌会自动下发给扩展。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            text = "拒绝",
                            onClick = { state.rejectPairRequest() },
                            modifier = Modifier.width(78.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = { state.approvePairRequest() },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.width(96.dp)
                        ) {
                            Text("允许", fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 左对齐大标题顶栏：标题 30sp 加粗，副标题展示实时速度；搜索框展开时显示在标题下方。
 */
@Composable
private fun GhostTopBar(
    title: String,
    subtitle: String? = null,
    showSearch: Boolean = false,
    searchVisible: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 20.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onBackground
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        if (showSearch && searchVisible) {
            TextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = "搜索任务名",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
            )
        }
    }
}

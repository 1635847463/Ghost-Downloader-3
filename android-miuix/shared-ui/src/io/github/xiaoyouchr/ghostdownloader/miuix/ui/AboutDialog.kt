package io.github.xiaoyouchr.ghostdownloader.miuix.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaoyouchr.ghostdownloader.miuix.state.AppState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AboutDialog(state: AppState) {
    OverlayDialog(
        show = state.showAboutDialog,
        title = "Ghost Downloader",
        summary = "Android UI · Miuix",
        onDismissRequest = state::closeAbout
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                text = "版本 ${state.settings.appVersion}（Miuix UI 预览）",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            BasicComponent(
                title = "开源地址",
                summary = "github.com/XiaoYouChR/Ghost-Downloader-3"
            )
            BasicComponent(
                title = "问题反馈",
                summary = "本项目仅替换安卓端 UI 图层，下载引擎来自主仓库"
            )
            BasicComponent(
                title = "界面库",
                summary = "Miuix for Compose Multiplatform 0.9.4"
            )
            Text(
                text = "MAINTAINED BY XIAOYOUCHR · MIUIX BY COMPOSE-MIUIX-UI",
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            TextButton(
                text = "确定",
                onClick = state::closeAbout,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }
    }
}

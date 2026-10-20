package io.github.xiaoyouchr.ghostdownloader.miuix

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.AppDirs
import io.github.xiaoyouchr.ghostdownloader.miuix.engine.HttpDownloadEngine
import io.github.xiaoyouchr.ghostdownloader.miuix.platform.AndroidPlatformFiles
import java.io.File

class MainActivity : ComponentActivity() {

    private var downloadEngine: HttpDownloadEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 下载目录放在应用专属外部目录：API 26+ 无需存储权限，卸载 App 时一并清理。
        val externalRoot = getExternalFilesDir(null) ?: filesDir
        val dirs = AppDirs(
            downloadDir = File(externalRoot, "Download"),
            dataDir = filesDir
        )
        val engine = HttpDownloadEngine(dirs)
        downloadEngine = engine
        val platformFiles = AndroidPlatformFiles(this)

        setContent {
            var darkMode by remember { mutableStateOf(false) }

            DisposableEffect(darkMode) {
                // Miuix 是全屏沉浸式设计：状态栏 / 导航栏透明，图标颜色跟随主题。
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode }
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Xiaomi / HyperOS 上必须关闭强制对比度，否则导航栏会出现半透明遮罩
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose { }
            }

            App(
                engine = engine,
                platformFiles = platformFiles,
                onDarkModeChange = { darkMode = it }
            )
        }
    }

    override fun onDestroy() {
        downloadEngine?.close()
        downloadEngine = null
        super.onDestroy()
    }
}

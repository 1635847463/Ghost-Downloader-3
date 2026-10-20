package io.github.xiaoyouchr.ghostdownloader.miuix.platform

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Android 端文件操作：通过 [FileProvider] 把应用私有下载目录里的文件暴露给系统应用打开。
 * 需要 AndroidManifest 中声明 `${applicationId}.fileprovider`。
 */
class AndroidPlatformFiles(private val context: Context) : PlatformFiles {

    override fun openFile(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val extension = file.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * Android 没有通用的「打开某个目录」Intent，这里返回 false，
     * 由 UI 退化为提示下载路径（用户可从系统「文件」App 进入应用目录）。
     */
    override fun openFolder(path: String): Boolean = false
}

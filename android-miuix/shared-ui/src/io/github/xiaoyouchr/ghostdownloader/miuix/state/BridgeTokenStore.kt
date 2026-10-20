package io.github.xiaoyouchr.ghostdownloader.miuix.state

import java.io.File

/** 浏览器扩展配对令牌的持久化（否则 App 重启后扩展每次都要重新配对）。 */
class BridgeTokenStore(private val file: File) {

    fun load(): String? = runCatching {
        if (!file.isFile) return null
        file.readText().trim().ifBlank { null }
    }.getOrNull()

    fun save(token: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(token.trim())
        }
    }
}

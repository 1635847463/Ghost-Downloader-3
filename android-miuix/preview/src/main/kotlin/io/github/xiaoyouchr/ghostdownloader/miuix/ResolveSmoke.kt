package io.github.xiaoyouchr.ghostdownloader.miuix

import io.github.xiaoyouchr.ghostdownloader.miuix.resolver.MediaResolver

/**
 * 媒体解析冒烟测试（Desktop / JVM）：
 *
 * ```
 * gradlew :preview:resolverSmoke -Purl=https://www.bilibili.com/video/BV...
 * ```
 */
fun main(args: Array<String>) {
    val url = args.firstOrNull()?.trim().orEmpty()
    if (url.isEmpty()) {
        println("usage: resolverSmoke <url>")
        return
    }
    println("resolving: $url")
    val resolver = MediaResolver()
    runCatching { resolver.resolve(url) }
        .onSuccess { media ->
            if (media == null) {
                println("DIRECT: 交给 HTTP 引擎做两阶段 Range 探测")
            } else {
                println("source=${media.source.label}")
                println("title=${media.title}")
                println("fileName=${media.fileName}")
                println("url=${media.url.take(140)}...")
                println("totalBytes=${media.totalBytes} canUseRange=${media.canUseRange}")
                println("headers=${media.headers}")
                println("note=${media.note}")
                println("RESOLVE OK")
            }
        }
        .onFailure { error ->
            println("RESOLVE FAILED: ${error.message}")
            error.printStackTrace()
        }
}

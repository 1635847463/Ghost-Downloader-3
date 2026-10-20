package io.github.xiaoyouchr.ghostdownloader.miuix.resolver

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

enum class MediaSource(val label: String) {
    DIRECT("直链"),
    BILIBILI("哔哩哔哩"),
    YOUTUBE("YouTube")
}

/** 解析出的可下载媒体。 */
data class ResolvedMedia(
    val source: MediaSource,
    val title: String,
    val fileName: String,
    val url: String,
    val headers: Map<String, String>,
    val totalBytes: Long,
    val canUseRange: Boolean,
    val note: String? = null,
    val category: String? = null
)

/** 链接需要未集成的运行时（libtorrent / Kelpie / yt-dlp 等）。 */
class UnsupportedMediaException(message: String) : Exception(message)

/**
 * 媒体链接解析：哔哩哔哩、YouTube；磁力/BT、eD2k 给出明确的不支持原因；
 * 其余普通链接返回 null，由 HTTP 引擎做两阶段 Range 探测。
 *
 * 说明：上游对应 `features/bili_pack`、`features/yt_dlp_pack`（Python）。本模块在 Kotlin 端
 * 用公开 API 做轻量解析：B 站走 view + playurl(durl)，YouTube 走 youtubei player 的渐进式格式；
 * 需要 FFmpeg 混流 / yt-dlp / libtorrent / Kelpie 的能力不在当前 Android 运行时内。
 */
class MediaResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", BROWSER_UA)
                    .build()
            )
        }
        .build()

    /** 阻塞调用；返回 null 表示普通链接，交给 HTTP 引擎。 */
    fun resolve(rawUrl: String): ResolvedMedia? {
        val url = rawUrl.trim()
        return when {
            url.startsWith("magnet:", ignoreCase = true) || url.startsWith("torrent:", ignoreCase = true) ||
                url.substringBefore('?').endsWith(".torrent", ignoreCase = true) ->
                throw UnsupportedMediaException(
                    "磁力 / BT 下载需要 libtorrent 运行时；Android 版需要交叉编译的 libtorrent wheel（上游在私有仓库构建），当前版本未内置"
                )

            url.startsWith("ed2k://", ignoreCase = true) ->
                throw UnsupportedMediaException(
                    "eD2k 下载需要 Kelpie 运行时（Go 交叉编译的 .so，上游在私有仓库构建），当前版本未内置"
                )

            isBilibili(url) -> resolveBilibili(url)
            isYouTube(url) -> resolveYouTube(url)
            else -> null
        }
    }

    // ------------------------------------------------------------ 哔哩哔哩

    private fun isBilibili(url: String): Boolean =
        url.contains("bilibili.com", true) || url.contains("b23.tv", true)

    private fun resolveBilibili(rawUrl: String): ResolvedMedia {
        val finalUrl = followRedirect(rawUrl)
        val bvidMatch = Regex("BV[0-9A-Za-z]{10}").find(finalUrl)
        val aidMatch = Regex("/av(\\d+)", RegexOption.IGNORE_CASE).find(finalUrl)
        val bvid = bvidMatch?.value
        val aid = aidMatch?.groupValues?.getOrNull(1)
        if (bvid == null && aid == null) {
            throw UnsupportedMediaException("无法从链接识别 B 站视频 ID（支持 BV/av/b23.tv 链接）")
        }
        val pageIndex = Regex("[?&]p=(\\d+)").find(finalUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.coerceAtLeast(1) ?: 1

        val viewUrl = if (bvid != null) {
            "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
        } else {
            "https://api.bilibili.com/x/web-interface/view?aid=$aid"
        }
        val view = apiGetJson(viewUrl, BILI_HEADERS)
        if (view.get("code")?.asInt != 0) {
            throw UnsupportedMediaException("B 站接口返回异常（code=${view.get("code")}）")
        }
        val data = view.getAsJsonObject("data")
            ?: throw UnsupportedMediaException("B 站接口缺少 data")
        val title = data.get("title")?.asString ?: "bilibili-video"
        val realBvid = data.get("bvid")?.asString ?: bvid ?: ""
        val pages = data.getAsJsonArray("pages")
        if (pages == null || pages.size() == 0) {
            throw UnsupportedMediaException("B 站视频没有可下载的分 P")
        }
        val page = pages[Math.min(pageIndex, pages.size()) - 1].asJsonObject
        val cid = page.get("cid")?.asLong ?: throw UnsupportedMediaException("B 站接口缺少 cid")

        val playUrl =
            "https://api.bilibili.com/x/player/playurl?bvid=$realBvid&cid=$cid&qn=64&fnval=1&fnver=0&fourk=0"
        val play = apiGetJson(playUrl, BILI_HEADERS)
        if (play.get("code")?.asInt != 0) {
            throw UnsupportedMediaException("B 站播放地址获取失败（code=${play.get("code")}），可能需要登录或地区限制")
        }
        val playData = play.getAsJsonObject("data")
            ?: throw UnsupportedMediaException("B 站播放接口缺少 data")
        val durl = playData.getAsJsonArray("durl")
        if (durl == null || durl.size() == 0) {
            throw UnsupportedMediaException("B 站没有返回可直接下载的 durl（可能是 DASH 分轨视频）")
        }
        if (durl.size() > 1) {
            throw UnsupportedMediaException("该视频被拆成 ${durl.size()} 段 FLV，暂不支持自动拼接")
        }
        val item = durl[0].asJsonObject
        val mediaUrl = item.get("url")?.asString
            ?: throw UnsupportedMediaException("B 站没有返回下载地址")
        val size = item.get("size")?.asLong ?: 0L
        val quality = playData.get("quality")?.asInt ?: 0
        val extension = if (mediaUrl.contains(".flv", true)) "flv" else "mp4"
        val qualityLabel = biliQualityLabel(quality)

        return ResolvedMedia(
            source = MediaSource.BILIBILI,
            title = title,
            fileName = fileNameOf("$title-$qualityLabel", extension),
            url = mediaUrl,
            headers = BILI_HEADERS + mapOf("Referer" to "https://www.bilibili.com/"),
            totalBytes = size,
            canUseRange = true,
            note = "B 站 ${qualityLabel} · durl 直链（CDN 需要 Referer）",
            category = "视频"
        )
    }

    private fun biliQualityLabel(quality: Int): String = when (quality) {
        127 -> "8K"
        126 -> "杜比视界"
        125 -> "HDR"
        120 -> "4K"
        116 -> "1080P60"
        112 -> "1080P+"
        80 -> "1080P"
        74 -> "720P60"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        else -> if (quality > 0) "qn$quality" else "默认"
    }

    // ------------------------------------------------------------ YouTube

    private fun isYouTube(url: String): Boolean =
        url.contains("youtube.com", true) || url.contains("youtu.be", true)

    private fun resolveYouTube(rawUrl: String): ResolvedMedia {
        val videoId = extractYouTubeId(rawUrl)
            ?: throw UnsupportedMediaException("无法识别 YouTube 视频 ID")
        var lastError = "未知错误"
        // 依次尝试对签名更宽松的客户端；优先 ANDROID_VR（通常无需签名解密）
        for (clientSpec in YOUTUBE_CLIENTS) {
            val player = runCatching { youtubePlayer(videoId, clientSpec) }.getOrElse {
                lastError = it.message ?: it.javaClass.simpleName
                continue
            }
            val status = player.getAsJsonObject("playabilityStatus")
            val playability = status?.get("status")?.asString ?: ""
            if (playability != "OK") {
                lastError = status?.get("reason")?.asString ?: playability
                continue
            }
            val streaming = player.getAsJsonObject("streamingData") ?: continue
            val formats = streaming.getAsJsonArray("formats")
            if (formats == null || formats.size() == 0) {
                lastError = "没有渐进式单文件格式"
                continue
            }
            val best = formats.map { it.asJsonObject }
                .filter { it.has("url") && it.get("url").asString.isNotBlank() }
                .maxByOrNull { (it.get("height")?.asInt ?: 0) * 1000 + (it.get("bitrate")?.asInt ?: 0) / 1000 }
                ?: continue
            val mediaUrl = best.get("url").asString
            val title = player.getAsJsonObject("videoDetails")?.get("title")?.asString ?: "youtube-video"
            val itag = best.get("itag")?.asInt ?: 0
            val size = best.get("contentLength")?.asString?.toLongOrNull() ?: 0L
            return ResolvedMedia(
                source = MediaSource.YOUTUBE,
                title = title,
                fileName = fileNameOf(title, "mp4"),
                url = mediaUrl,
                headers = mapOf("Referer" to "https://www.youtube.com/"),
                totalBytes = size,
                canUseRange = true,
                note = "YouTube ${clientSpec.name} 渐进式格式 itag=$itag（无 FFmpeg 混流）",
                category = "视频"
            )
        }
        throw UnsupportedMediaException("YouTube 解析失败：$lastError（部分视频需要 yt-dlp + FFmpeg 混流，当前 Android 运行时未内置）")
    }

    private data class YouTubeClient(val name: String, val version: String, val sdk: Int, val osName: String)

    private fun youtubePlayer(videoId: String, clientSpec: YouTubeClient): JsonObject {
        val context = JsonObject().apply {
            add("client", JsonObject().apply {
                addProperty("clientName", clientSpec.name)
                addProperty("clientVersion", clientSpec.version)
                addProperty("androidSdkVersion", clientSpec.sdk)
                addProperty("osName", clientSpec.osName)
                addProperty("hl", "en")
                addProperty("gl", "US")
            })
        }
        val body = JsonObject().apply {
            add("context", context)
            addProperty("videoId", videoId)
            addProperty("contentCheckOk", true)
            addProperty("racyCheckOk", true)
        }
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?key=$YOUTUBE_API_KEY&prettyPrint=false")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Origin", "https://www.youtube.com")
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", clientSpec.version)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw UnsupportedMediaException("YouTube 接口 HTTP ${response.code}")
            }
            return JsonParser.parseString(text).asJsonObject
        }
    }

    private fun extractYouTubeId(url: String): String? {
        Regex("(?:v=|/shorts/|/live/|youtu\\.be/)([0-9A-Za-z_-]{11})").find(url)?.let {
            return it.groupValues[1]
        }
        return null
    }

    // ------------------------------------------------------------ 通用

    private fun followRedirect(rawUrl: String): String =
        runCatching {
            client.newCall(Request.Builder().url(rawUrl).build()).execute().use { it.request.url.toString() }
        }.getOrDefault(rawUrl)

    private fun apiGetJson(url: String, headers: Map<String, String>): JsonObject {
        val builder = Request.Builder().url(url)
        for ((key, value) in headers) builder.header(key, value)
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw UnsupportedMediaException("接口 HTTP ${response.code}")
            }
            return JsonParser.parseString(text).asJsonObject
        }
    }

    private fun fileNameOf(title: String, extension: String): String {
        val safe = title
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim()
            .ifBlank { "download" }
            .take(120)
        return "$safe.$extension"
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val YOUTUBE_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val YOUTUBE_CLIENTS = listOf(
            YouTubeClient("ANDROID_VR", "1.62.27", 32, "Android"),
            YouTubeClient("ANDROID", "19.09.37", 30, "Android"),
            YouTubeClient("IOS", "19.09.3", 30, "iOS")
        )
        private val BILI_HEADERS = mapOf(
            "Referer" to "https://www.bilibili.com/",
            "Origin" to "https://www.bilibili.com",
            "User-Agent" to BROWSER_UA
        )
    }
}

/** 内置下载分类规则：扩展名 → 分类目录名。 */
object CategoryRules {
    val rules: Map<String, Set<String>> = linkedMapOf(
        "视频" to setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "ts", "m4v", "m3u8"),
        "音频" to setOf("mp3", "flac", "aac", "wav", "m4a", "ogg", "opus"),
        "软件" to setOf("apk", "exe", "msi", "dmg", "pkg", "deb", "rpm", "appimage"),
        "压缩包" to setOf("zip", "7z", "rar", "tar", "gz", "xz", "bz2"),
        "图片" to setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg"),
        "文档" to setOf("pdf", "epub", "mobi", "docx", "doc", "xlsx", "pptx", "txt")
    )

    fun detect(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isBlank()) return null
        return rules.entries.firstOrNull { extension in it.value }?.key
    }

    fun folderName(category: String): String = category
}

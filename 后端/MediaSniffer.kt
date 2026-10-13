package com.taowu.backend.sniff

import com.taowu.backend.AdFilter
import com.taowu.backend.model.Track
import com.taowu.backend.parse.MediaUtil
import com.taowu.backend.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 网页探测：内置浏览器旁观页面发出的网络请求，识别其中的媒体流（m3u8 / mp4 / mp3 …）。
 *
 * 移植自 PC 版 `collect_media_streams_browser` / `media_capture` 的「浏览器旁观」思路，
 * 专门补齐静态扫描抓不到的地址（JS 动态加载、hls.js 分片、iframe 内）。
 *
 * 应用级 object（仿 [com.taowu.GrabTasks]）：可在 WebView 的拦截线程直接调 [offer]，
 * UI 用 [found].collectAsState() 观察。全程不抛异常、不影响 WebView。
 */
object MediaSniffer {

    /** 探测到的一条网页媒体（已归一、去重）。 */
    data class Found(
        val key: String,        // 去重身份 = MediaUtil.canonKey(url)
        val url: String,        // 可下载的绝对 http(s) 地址
        val pageUrl: String,    // 该资源所属页面（Referer 优先，回落当前页）
        val kind: String,       // "m3u8" | "dash" | "direct"
        val ext: String,        // "m3u8"/"mpd"/"mp4"/.../"mp3"
        val mediaClass: String, // "video" | "audio" | ""（纯清单）
    )

    private const val MAX = 300

    /** 音频直链扩展名（MediaUtil.classifyVideo 只认视频）。 */
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "oga")

    /** 分片扩展名：HLS/DASH 的碎片，不入列（否则刷屏）。 */
    private val SEGMENT_EXT = setOf("ts", "m4s", "cmfv", "cmfa", "cmft")

    /** 廉价预筛：URL 里是否出现媒体扩展名（避免对每个图片/CSS 请求都做 URI 解析）。 */
    private val MEDIA_HINT = Regex(
        "\\.(m3u8|mpd|mp4|webm|flv|mov|m4v|mkv|ts|m4s|cmfv|cmfa|cmft|mp3|m4a|aac|flac|wav|ogg|opus|oga)([?#]|$)",
        RegexOption.IGNORE_CASE,
    )

    /** 有序、去重、最新在前。UI 用 collectAsState() 读。 */
    val found = MutableStateFlow<List<Found>>(emptyList())

    /** 记录一个请求 URL（拦截线程 / UI 线程均可调）。绝不抛异常。 */
    fun offer(url: String, pageUrl: String = "", headers: Map<String, String> = emptyMap()) {
        runCatching {
            if (!AppSettings.webProbeEnabled) return
            if (url.isEmpty() || !MEDIA_HINT.containsMatchIn(url)) return
            val u = MediaUtil.normalize(url, pageUrl) ?: return
            if (AppSettings.adFilterEnabled &&
                AdFilter.isAd(u, AdFilter.parseExtra(AppSettings.adFilterExtra))
            ) return
            val c = classify(u) ?: return
            if (c.segment) return
            val key = MediaUtil.canonKey(u)
            val referer = refererOf(headers) ?: pageUrl
            found.update { list ->
                if (list.any { it.key == key }) list
                else (listOf(Found(key, u, referer, c.kind, c.ext, c.cls)) + list).take(MAX)
            }
        }
    }

    fun clear() {
        found.value = emptyList()
    }

    /** Found → Track（index 由 UI 按列表位置给）。 */
    fun toTrack(index: Int, item: Found, cookies: Map<String, String> = emptyMap()): Track = Track(
        index = index,
        mediaClass = item.mediaClass,
        kind = item.kind,
        url = item.url,
        ext = item.ext,
        src = "browser-sniff",
        pageUrl = item.pageUrl,
        titleHint = titleFor(item.url),
        cookies = cookies,
    )

    /** 落盘容器扩展名（m3u8→ts、dash→mp4，其余用原扩展）。 */
    fun downloadExt(item: Found): String = when {
        item.kind == "m3u8" -> "ts"
        item.kind == "dash" -> "mp4"
        item.ext.isNotEmpty() -> item.ext
        else -> "mp4"
    }

    private class Classified(val kind: String, val ext: String, val cls: String, val segment: Boolean)

    private fun classify(url: String): Classified? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = if (path.contains('.')) path.substringAfterLast('.') else ""
        // 先判分片：MediaUtil 会把 .ts 当视频直链，这里必须提前拦掉
        if (ext in SEGMENT_EXT) return Classified("direct", ext, "", segment = true)
        MediaUtil.classifyVideo(url)?.let { (kind, e) ->
            val cls = if (kind == "m3u8" || kind == "dash") "" else "video"
            return Classified(kind, e, cls, segment = false)
        }
        if (ext in AUDIO_EXT) return Classified("direct", ext, "audio", segment = false)
        return null
    }

    private fun refererOf(headers: Map<String, String>): String? =
        headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.value?.takeIf { it.isNotEmpty() }

    /** 从 URL 末段取文件名当标题；取不到回落到主机名。 */
    private fun titleFor(url: String): String {
        val name = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val stem = name.substringBeforeLast('.')
        return stem.ifEmpty { hostOf(url) }
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: url
    } catch (e: Exception) {
        url
    }
}

package com.taowu.backend.net

// ================================================================
// 媒体态统一请求头（移植 video_grab._media_headers 的 purpose 化）。
// 桌面版还带 stealth_profile 身份档；安卓无该体系，这里保留"按用途细分 Accept/头"的核心。
// purpose：page / manifest / segment / key / direct / range；缺省按 URL 扩展名归类。
// ================================================================
object MediaHeaders {

    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 按 URL 扩展名归类用途。 */
    fun purposeForUrl(url: String): String {
        val p = url.split("?", limit = 2)[0].lowercase()
        return when {
            p.endsWith(".m3u8") || p.endsWith(".mpd") -> "manifest"
            p.endsWith(".key") -> "key"
            p.endsWith(".ts") || p.endsWith(".m4s") || p.endsWith(".mp4") ||
                p.endsWith(".m4a") || p.endsWith(".mp3") -> "segment"
            else -> "direct"
        }
    }

    private fun acceptFor(purpose: String): String = when (purpose) {
        "manifest" -> "application/vnd.apple.mpegurl, application/x-mpegURL, */*"
        "page" -> "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        else -> "*/*"   // segment / key / direct / range
    }

    /** 生成一次请求的头（不含 Cookie）。ua 为 null 时用设置里的全局 UA。 */
    fun headers(url: String, referer: String = "", purpose: String? = null, ua: String? = null): Map<String, String> {
        val p = purpose ?: purposeForUrl(url)
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = ua ?: com.taowu.backend.settings.AppSettings.userAgent
        h["Accept"] = acceptFor(p)
        h["Accept-Language"] = "zh-CN,zh;q=0.9,en;q=0.8"
        val override = com.taowu.backend.settings.AppSettings.refererOverride
        val ref = if (referer.isNotEmpty()) referer else override
        if (ref.isNotEmpty()) h["Referer"] = ref
        return h
    }
}

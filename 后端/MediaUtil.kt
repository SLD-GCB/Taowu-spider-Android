package com.taowu.backend.parse

// ================================================================
// 媒体小工具（移植自 video_grab.py 的 _safe_unescape/_normalize/_canon_key/_classify）
// ================================================================

object MediaUtil {

    private val SKIP_SCHEMES = listOf("blob:", "data:", "javascript:")

    /** 视频直链扩展白名单（config.VIDEO_EXT_DIRECT）。 */
    val VIDEO_EXT_DIRECT = setOf("mp4", "flv", "webm", "mov", "m4v", "mkv", "ts")

    // ── HTML 实体解码（绝不解 &times） ──────────────────────────
    private val SAFE_ENTITY = Regex(
        "&(amp|lt|gt|quot|apos|nbsp)(;|(?![A-Za-z0-9]))|&#(\\d+);?|&#[xX]([0-9a-fA-F]+);?"
    )
    private val NAMED_ENT = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00a0"
    )

    fun safeUnescape(s: String): String = SAFE_ENTITY.replace(s) { m ->
        val g1 = m.groupValues[1]
        if (g1.isNotEmpty()) return@replace NAMED_ENT[g1] ?: m.value
        val g3 = m.groupValues[3]
        if (g3.isNotEmpty()) {
            return@replace try { cpToString(g3.toInt(10)) } catch (e: Exception) { m.value }
        }
        try { cpToString(m.groupValues[4].toInt(16)) } catch (e: Exception) { m.value }
    }

    private fun cpToString(cp: Int): String =
        if (cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else "\uFFFD"

    /** 去重 key：剥 fragment。 */
    fun canonKey(url: String?): String = (url ?: "").split("#", limit = 2)[0]

    /** 原始字符串 → 可下载的绝对 http(s) URL；不可下载返回 null。 */
    fun normalize(raw: String?, pageUrl: String): String? {
        if (raw.isNullOrEmpty()) return null
        var s = raw.trim()
        if (s.isEmpty()) return null
        s = safeUnescape(s)
        s = s.replace("\\/", "/")
        s = s.trim().trim('"', '\'', ' ', '\t')
        if (s.startsWith("//")) {
            val scheme = schemeOf(pageUrl) ?: "https"
            s = "$scheme:$s"
        }
        val low = s.lowercase()
        if (SKIP_SCHEMES.any { low.startsWith(it) }) return null
        var u = resolveUrl(pageUrl, s).split("#", limit = 2)[0]
        if (SKIP_SCHEMES.any { u.lowercase().startsWith(it) }) return null
        val lu = u.lowercase()
        if (!lu.startsWith("http://") && !lu.startsWith("https://")) return null
        return u
    }

    fun schemeOf(url: String): String? = try { java.net.URI(url).scheme?.lowercase() } catch (e: Exception) { null }

    fun resolveUrl(baseUrl: String, ref: String): String = try {
        java.net.URI(baseUrl).resolve(ref).toString()
    } catch (e: Exception) {
        ref
    }

    /** 视频分类：返回 (kind, ext) 或 null。 */
    fun classifyVideo(mediaUrl: String): Pair<String, String>? {
        val path = mediaUrl.split("?", limit = 2)[0].lowercase()
        if (path.endsWith(".m3u8")) return "m3u8" to "m3u8"
        if (path.endsWith(".mpd")) return "dash" to "mpd"
        if (path.contains(".")) {
            val ext = path.substringAfterLast(".")
            if (ext in VIDEO_EXT_DIRECT) return "direct" to ext
        }
        return null
    }
}

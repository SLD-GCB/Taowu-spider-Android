package com.taowu.backend.parse

import com.taowu.backend.settings.AppSettings
import kotlinx.serialization.json.*

/** 一个静态解析出的候选（交给上层转 Track）。 */
data class Candidate(
    val kind: String,
    val url: String,
    val ext: String,
    val src: String,
    val pageUrl: String,
    val titleHint: String = "",
)

// ================================================================
// 页面静态扫描（移植自 video_grab.extract_video_candidates_from_html
//   与 audio_grab.extract_audio_candidates_from_html 的八趟扫描）
// ================================================================
object StaticExtract {

    // ── 正则常量（config.py） ───────────────────────────────────
    private val VIDEO_RE_M3U8 = Regex("""["']((?:https?:)?(?://|\\/\\/)[^"']*?\.m3u8[^"']*?)["']""", RegexOption.IGNORE_CASE)
    private val VIDEO_RE_DIRECT = Regex("""["']((?:https?:)?(?://|\\/\\/)[^"']*?\.(?:mp4|flv|webm|mov|m4v)[^"']*?)["']""", RegexOption.IGNORE_CASE)
    private val VIDEO_RE_MPD = Regex("""["']((?:https?:)?(?://|\\/\\/)[^"']*?\.mpd[^"']*?)["']""", RegexOption.IGNORE_CASE)

    private val RE_PLAYER_FIELD = Regex(
        """(?<![A-Za-z0-9_."'])["']?(?:url|file|src|link|v_url|playurl|play_url|video|mac_url|enc|content)["']?\s*[:=]\s*["']([^"']{4,})["']""",
        RegexOption.IGNORE_CASE,
    )
    private val RE_DECODE_CALL = Regex(
        """(?:atob|decodeURIComponent|decodeURI|unescape|Base64\.decode)\s*\(\s*["']([^"']{4,})["']\s*\)""",
        RegexOption.IGNORE_CASE,
    )

    // ── 混淆解码 ────────────────────────────────────────────────
    fun boundedDecodePayload(raw: String): List<String> {
        val out = ArrayList<String>()
        fun add(s: String?) {
            if (!s.isNullOrEmpty() && !out.contains(s)) out.add(s)
        }
        add(raw)
        // 反复解码直到不再变化：站点常见 **base64(URL编码(地址))**（MacCMS encrypt:2），
        // base64 解出来的仍是 %XX 串，必须继续做百分号解码才能拿到真实地址。
        var guard = 0
        var changed = true
        val maxRounds = AppSettings.decodeMaxRounds.coerceIn(2, 12)
        while (changed && guard++ < maxRounds && out.size < 200) {
            changed = false
            for (base in ArrayList(out)) {
                // ① HTML 实体
                val ent = MediaUtil.safeUnescape(base)
                if (ent != base && !out.contains(ent)) { out.add(ent); changed = true }
                // ② %uXXXX / ③ 百分号（可多轮）
                var cur = ent
                for (round in 0 until 3) {
                    var ch = false
                    val u = Regex("%u([0-9A-Fa-f]{4})").replace(cur) { m ->
                        val cp = m.groupValues[1].toInt(16)
                        if (cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else ""
                    }
                    if (u != cur) { if (!out.contains(u)) { out.add(u); changed = true }; cur = u; ch = true }
                    val p = percentDecode(cur)
                    if (p != cur) { if (!out.contains(p)) { out.add(p); changed = true }; cur = p; ch = true }
                    if (!ch) break
                }
                // ④ base64
                var b = base
                if (b.startsWith("base64,")) b = b.substring("base64,".length)
                val cleaned = b.filterNot { it.isWhitespace() }
                if (cleaned.length < 4) continue
                val tried = ArrayList<String>()
                tried.add(cleaned)
                if (!cleaned.contains("=")) {
                    val pad = (4 - cleaned.length % 4) % 4
                    tried.add(cleaned + "=".repeat(pad))
                }
                tried.add(cleaned.replace("-", "+").replace("_", "/"))
                for (t in tried) {
                    val dec = try {
                        String(java.util.Base64.getDecoder().decode(t), Charsets.UTF_8)
                    } catch (e: Exception) {
                        continue
                    }
                    if (dec.length >= 4 && !out.contains(dec)) { out.add(dec); changed = true }
                }
            }
        }
        return out
    }

    private fun percentDecode(s: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val h = Character.digit(s[i + 1], 16)
                val l = Character.digit(s[i + 2], 16)
                if (h >= 0 && l >= 0) {
                    bytes.write((h shl 4) + l)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun decodeObfuscatedUrls(html: String, pageUrl: String): List<Triple<String, String, String>> {
        if (!AppSettings.decodeEnabled) return emptyList()
        val found = ArrayList<Triple<String, String, String>>()
        val seen = HashSet<String>()
        val payloads = ArrayList<String>()
        for (m in RE_PLAYER_FIELD.findAll(html)) payloads.add(m.groupValues[1])
        for (m in RE_DECODE_CALL.findAll(html)) payloads.add(m.groupValues[1])
        for (p in payloads) {
            for (cand in boundedDecodePayload(p)) {
                val norm = MediaUtil.normalize(cand, pageUrl) ?: continue
                val key = MediaUtil.canonKey(norm)
                if (!seen.add(key)) continue
                val cls = MediaUtil.classifyVideo(norm)
                if (cls != null) found.add(Triple(norm, cls.first, cls.second))
            }
        }
        // G1 站点 deobf 槽（私有混淆现算；输出再过一次通用剥离）
        val deobf = SiteAdapters.deobfFor(pageUrl)
        if (deobf != null) {
            val extra = ArrayList<String>()
            for (p in payloads) {
                try {
                    deobf(p)?.let { extra.addAll(it) }
                } catch (_: Exception) {
                }
            }
            for (r in extra.toList()) for (sub in boundedDecodePayload(r)) if (sub !in extra) extra.add(sub)
            for (cand in extra) {
                val norm = MediaUtil.normalize(cand, pageUrl) ?: continue
                val key = MediaUtil.canonKey(norm)
                if (!seen.add(key)) continue
                val cls = MediaUtil.classifyVideo(norm)
                if (cls != null) found.add(Triple(norm, cls.first, cls.second))
            }
        }
        return found
    }

    // ── 视频：八趟扫描 ──────────────────────────────────────────
    fun extractVideoCandidates(html: String, pageUrl: String): Pair<List<Candidate>, List<String>> {
        val cands = ArrayList<Candidate>()
        val notes = ArrayList<String>()
        val seen = HashSet<String>()

        fun add(u: String?, src: String) {
            val norm = MediaUtil.normalize(u, pageUrl)
            if (norm == null) {
                val low = (u ?: "").lowercase()
                if (listOf("blob:", "data:", "javascript:").any { low.startsWith(it) }) {
                    notes.add("$src: 检测到 ${low.substringBefore(':')}: 视频，无独立文件地址，无法直接下载")
                }
                return
            }
            val key = MediaUtil.canonKey(norm)
            if (!seen.add(key)) return
            val cls = MediaUtil.classifyVideo(norm) ?: return
            val (kind, ext) = cls
            cands.add(
                Candidate(kind, norm, ext, src, pageUrl, if (ext != "m3u8" && ext != "ts") ext else "hls")
            )
        }

        if (html.isEmpty()) return cands to notes

        // 1) <video>/<source> 标签
        Regex("""<(video|source)\b[^>]*?\b(src|data-src|data-url)\s*=\s*(["'])(.*?)\3""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html).forEach { m ->
                val v = m.groupValues[4]
                if (v.trim().isNotEmpty()) add(MediaUtil.safeUnescape(v), "video/source标签")
            }

        // 2) JS 字符串字面量
        VIDEO_RE_M3U8.findAll(html).forEach { add(it.groupValues[1], "JS字面量-m3u8") }
        VIDEO_RE_MPD.findAll(html).forEach { add(it.groupValues[1], "JS字面量-mpd") }
        VIDEO_RE_DIRECT.findAll(html).forEach { add(it.groupValues[1], "JS字面量-直链") }

        // 3) 播放器配置字段（值须含媒体扩展）
        val extInVal = Regex("""\.(?:m3u8|mp4|mpd|flv|webm|mov|m4v)(?=[?#"'\s\\]|$)""", RegexOption.IGNORE_CASE)
        Regex("""(?<![A-Za-z0-9_."'])["']?(?:url|file|src|playurl|play_url|video)["']?\s*[:=]\s*["']([^"']{4,})["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val v = m.groupValues[1]
                if (extInVal.containsMatchIn(v)) add(v, "播放器配置")
            }

        // 4) 裸 URL
        Regex("""(https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*)""", RegexOption.IGNORE_CASE).findAll(html).forEach { add(it.groupValues[1], "裸URL") }
        Regex("""(https?://[^\s"'<>]+?\.mpd[^\s"'<>]*)""", RegexOption.IGNORE_CASE).findAll(html).forEach { add(it.groupValues[1], "裸URL-mpd") }

        // 5) 混淆解码
        for ((u, _k, _e) in decodeObfuscatedUrls(html, pageUrl)) add(u, "播放器解码")

        // 6) og:video meta
        val ogKey = Regex("""(?:property|name|itemprop)\s*=\s*["'](?:og:video(?::url|:secure_url)?|twitter:player:stream)["']""", RegexOption.IGNORE_CASE)
        Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            if (!ogKey.containsMatchIn(tag)) return@forEach
            Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
                ?.takeIf { it.trim().isNotEmpty() }?.let { add(MediaUtil.safeUnescape(it).trim(), "og:video meta") }
        }

        // 7) JSON-LD VideoObject
        val ldKeys = setOf("contenturl", "contentstreamurl", "embedurl", "playurl", "streamurl", "m3u8", "mpd")
        collectJsonLd(html) { key, value ->
            val kl = key.trim().lowercase()
            val vl = value.trim()
            if (kl in ldKeys || (vl.lowercase().startsWith("http") && vl.lowercase().contains(".m3u8"))) {
                add(MediaUtil.safeUnescape(vl).trim(), "JSON-LD")
            }
        }

        // 8) meta itemprop=contentUrl / link itemprop=embedURL
        Regex("""<meta\b[^>]*\bitemprop\s*=\s*["']contentUrl["'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(m.value)?.groupValues?.get(1)
                ?.takeIf { it.trim().isNotEmpty() }?.let { add(MediaUtil.safeUnescape(it).trim(), "meta contentUrl") }
        }
        Regex("""<link\b[^>]*\bitemprop\s*=\s*["']embedURL["'][^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            Regex("""href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(m.value)?.groupValues?.get(1)
                ?.takeIf { it.trim().isNotEmpty() }?.let { add(MediaUtil.safeUnescape(it).trim(), "link embedURL") }
        }

        return cands to notes
    }

    // ── 页面标题 ────────────────────────────────────────────────
    /** <title> / og:title / twitter:title → 压空白截 80；兜底域名。 */
    fun extractTitle(html: String, url: String): String {
        var title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.trim() ?: ""
        if (title.isEmpty()) {
            for (m in Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)) {
                val tag = m.value
                if (Regex("""(?:property|name)\s*=\s*["'](?:og:title|twitter:title)["']""", RegexOption.IGNORE_CASE).containsMatchIn(tag)) {
                    Regex("""content\s*=\s*["'](.*?)["']""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(tag)?.groupValues?.get(1)?.trim()?.let { title = it }
                    if (title.isNotEmpty()) break
                }
            }
        }
        title = Regex("\\s+").replace(title, " ").trim()
        if (title.isEmpty()) {
            title = try { java.net.URI(url).host ?: "video" } catch (e: Exception) { "video" }
        }
        return title.take(80)
    }

    // ── iframe 深挖：下一跳目标 ─────────────────────────────────
    private val PLAYER_IFRAME_HINT = Regex(
        """player|play|vod|video|jplayer|dplayer|ckplayer|artplayer|xgplayer|hls|m3u8|mpd|url=|\?url""",
        RegexOption.IGNORE_CASE,
    )

    /** 找本页值得跟随的下一跳：meta refresh 优先，其次"播放器感" iframe，最后任意 iframe。 */
    fun hopTarget(html: String, pageUrl: String): String? {
        Regex("""<meta\b[^>]*http-equiv\s*=\s*["']?refresh["']?[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            Regex("""url\s*=\s*["']?([^"'>\s]+)""", RegexOption.IGNORE_CASE).find(m.value)?.groupValues?.get(1)?.let { u ->
                MediaUtil.normalize(u, pageUrl)?.let { return it }
            }
        }
        var hinted: String? = null
        var fallback: String? = null
        Regex("""<(?:iframe|frame)\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val sm = Regex("""\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(m.value)
                ?: return@forEach
            val src = listOf(sm.groupValues[1], sm.groupValues[2], sm.groupValues[3]).firstOrNull { it.isNotEmpty() } ?: return@forEach
            val low = src.trim().lowercase()
            if (low.startsWith("data:") || low.startsWith("blob:") || low.startsWith("javascript:") || low.startsWith("#")) return@forEach
            val u = MediaUtil.normalize(src, pageUrl) ?: return@forEach
            if (PLAYER_IFRAME_HINT.containsMatchIn(src)) return u
            if (hinted == null && PLAYER_IFRAME_HINT.containsMatchIn(u)) hinted = u
            if (fallback == null) fallback = u
        }
        return hinted ?: fallback
    }

    // ── 工具 ────────────────────────────────────────────────────
    private fun attrVal(tag: String, attr: String): String? {
        val m = Regex("""\b$attr\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(tag) ?: return null
        return listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3]).firstOrNull { it.isNotEmpty() }
    }

    /** 遍历所有 application/ld+json，对每个 (key, stringValue) 回调。 */
    private fun collectJsonLd(html: String, onKeyValue: (String, String) -> Unit) {
        Regex("""<script\b[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html).forEach { m ->
            val data = try {
                Json.parseToJsonElement(m.groupValues[1].trim())
            } catch (e: Exception) {
                return@forEach
            }
            val stack = ArrayDeque<JsonElement>()
            stack.addLast(data)
            while (stack.isNotEmpty()) {
                when (val node = stack.removeLast()) {
                    is JsonObject -> for ((k, v) in node) {
                        if (v is JsonPrimitive && v.isString && v.content.trim().isNotEmpty()) onKeyValue(k, v.content)
                        else if (v is JsonObject || v is JsonArray) stack.addLast(v)
                    }
                    is JsonArray -> for (v in node) stack.addLast(v)
                    else -> {}
                }
            }
        }
    }
}

package com.taowu.backend.media

import com.taowu.backend.parse.MediaUtil
import kotlinx.serialization.json.*
import java.io.File

// ================================================================
// 视频元数据抽取与打标（移植 video_grab.extract_video_metadata / _maybe_tag_video）。
// 抽取：JSON-LD VideoObject/Movie/TVEpisode 优先，og:/twitter: 兜底。
// 打标：mp4/mov/m4v/mkv → 写同名旁挂 .info.json（安卓无 ffmpeg，不做容器内 atom 写入——诚实差异）。
// ================================================================
object VideoMeta {

    private val LD_TYPES = setOf("videoobject", "movie", "tvepisode", "episode")
    private val TAG_EXTS = setOf("mp4", "mov", "m4v", "mkv")

    data class Meta(
        val title: String = "",
        val artist: String = "",
        val description: String = "",
        val coverUrl: String = "",
    ) {
        val isEmpty: Boolean
            get() = title.isEmpty() && artist.isEmpty() && description.isEmpty() && coverUrl.isEmpty()
    }

    // ── 抽取 ────────────────────────────────────────────────────
    fun extract(html: String, pageUrl: String): Meta {
        var title = ""
        var artist = ""
        var desc = ""
        var cover = ""

        // ① JSON-LD
        Regex("""<script\b[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html).forEach { m ->
            val data = try { Json.parseToJsonElement(m.groupValues[1].trim()) } catch (e: Exception) { return@forEach }
            val stack = ArrayDeque<JsonElement>()
            stack.addLast(data)
            while (stack.isNotEmpty()) {
                when (val node = stack.removeLast()) {
                    is JsonObject -> {
                        val types = (node["@type"]?.let { t ->
                            if (t is JsonArray) t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
                            else listOfNotNull((t as? JsonPrimitive)?.contentOrNull?.lowercase())
                        }) ?: emptyList()
                        if (!types.any { it in LD_TYPES }) {
                            for ((_, v) in node) if (v is JsonObject || v is JsonArray) stack.addLast(v)
                            continue
                        }
                        if (title.isEmpty()) title = pick(node, "name", "headline", "title")
                        if (artist.isEmpty()) artist = nameOf(node["author"] ?: node["creator"] ?: node["director"])
                        if (desc.isEmpty()) desc = pick(node, "description", "abstract")
                        if (cover.isEmpty()) {
                            val img = node["image"] ?: node["thumbnailUrl"]
                            cover = when (img) {
                                is JsonObject -> pick(img, "url", "contentUrl", "thumbnailUrl")
                                is JsonPrimitive -> if (img.isString) img.content.trim() else ""
                                else -> ""
                            }
                        }
                    }
                    is JsonArray -> for (v in node) stack.addLast(v)
                    else -> {}
                }
            }
        }

        // ② og:/twitter: 兜底
        for (m in Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = m.value
            val pm = Regex("""(?:property|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: continue
            val cm = Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: continue
            val prop = pm.trim().lowercase()
            val v = MediaUtil.safeUnescape(cm).trim()
            if (v.isEmpty()) continue
            when {
                (prop == "og:title" || prop == "twitter:title") && title.isEmpty() -> title = v
                (prop == "og:description" || prop == "twitter:description") && desc.isEmpty() -> desc = v
                (prop in setOf("og:image", "og:image:url", "og:image:secure_url", "twitter:image")) && cover.isEmpty() -> cover = v
            }
        }

        // ②b) <link rel="image_src" href="...">
        if (cover.isEmpty()) {
            for (m in Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)) {
                val tag = m.value
                val rel = Regex("""rel\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase() ?: continue
                if ("image_src" in rel) {
                    val h = attr(tag, "href")
                    if (!h.isNullOrBlank()) { cover = MediaUtil.safeUnescape(h).trim(); break }
                }
            }
        }

        // ③ 通用海报兜底（og / JSON-LD / link 都没有时，分层识别各种形态）
        if (cover.isEmpty()) cover = findPoster(html) ?: ""

        if (cover.isNotEmpty()) cover = MediaUtil.normalize(cover, pageUrl) ?: cover
        return Meta(title.take(200), artist.take(120), desc.take(300), cover)
    }

    // ── 海报（封面）识别：从"最可信"到"最兜底"逐层尝试，覆盖各种形态 ──

    private val IMG_EXT = "(?:jpg|jpeg|png|webp|gif|bmp|avif)"

    /** 图片 URL：绝对或相对（相对由上层 normalize 转绝对），支持查询串。 */
    private val IMG_URL = Regex(
        """((?:https?://[^\s"'<>)\]]+?|/[^\s"'<>)\]]+?)\.$IMG_EXT(?:\?[^\s"'<>)\]]*)?)""",
        RegexOption.IGNORE_CASE,
    )
    /** 海报目录特征（MacCMS 的 /upload/vod/，及 /cover /pic /poster /thumb /vodpic 等）。 */
    private val POSTER_HINT = Regex(
        """(?:upload/(?:vod|pic)|/cover|/pic[/.]|/poster|/vodpic|/thumb|pic\.|poster\.)""",
        RegexOption.IGNORE_CASE,
    )
    /** `<img>` 标签本身：class/id 带海报词。 */
    private val IMG_CLASS_HINT = Regex(
        """(?:class|id)\s*=\s*["'][^"']*(?:pic|cover|poster|thumb|vod|movie-img|gen-movie)[^"']*["']""",
        RegexOption.IGNORE_CASE,
    )
    /** `<img>` 所在的“海报容器”：它前面一小段里的 `class="…pic/poster/cover…"`。 */
    private val POSTER_CONTAINER = Regex(
        """class\s*=\s*["'][^"']*(?:detail-pic|vod-pic|vod-img|movie-pic|movie-img|play-pic|pic|poster|cover|thumb)[^"']*["']""",
        RegexOption.IGNORE_CASE,
    )
    /** 明显不是封面的图（logo / 图标 / 广告 / 占位等）。 */
    private val NEG_HINT = Regex(
        """(?:logo|/icon|icon\.|avatar|qrcode|/qr|sprite|emoji|/share|weibo|loading|placeholder|blank|spacer|guanggao|/ads?[/.]|banner|pixel|beacon|/bg[/.]|watermark)""",
        RegexOption.IGNORE_CASE,
    )
    private val IMG_TAG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val SOURCE_TAG = Regex("""<source\b[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * 通用封面识别（多层，从最可信到最兜底）：
     *  a) `<img>` 在海报容器内、或 class/id 命中海报词 → 懒加载属性/src；
     *  b) 任意带懒加载属性（`data-src` 等）的 `<img>`（很多站海报无类名、只靠懒加载）；
     *  c) `<video poster>` / `<picture><source srcset>` / `data-bg` 类；
     *  d) CSS `background-image:url(...)`（优先命中海报目录的）；
     *  e) 页面里命中海报目录的图片 URL；
     *  f) 兜底：第一张“像图”的 `<img>`（排除 logo/图标/广告/占位）。
     * **绝对与相对地址都返回**（上层 normalize 成绝对）。
     */
    private fun findPoster(html: String): String? {
        // a) 海报容器内 或 class 命中海报词
        for (m in IMG_TAG.findAll(html)) {
            val tag = m.value
            val before = html.substring(maxOf(0, m.range.first - 220), m.range.first)
            if (!IMG_CLASS_HINT.containsMatchIn(tag) && !POSTER_CONTAINER.containsMatchIn(before)) continue
            lazySrc(tag)?.let { if (ok(it, tag)) return it }
        }
        // b) 任意带懒加载属性的 <img>
        for (m in IMG_TAG.findAll(html)) {
            lazyAttr(m.value)?.let { if (ok(it, m.value)) return it }
        }
        // c) <video poster> / <source srcset> / data-bg 类
        Regex("""<video\b[^>]*\bposter\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
            .find(html)?.let { m ->
                listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3]).firstOrNull { it.isNotBlank() }?.let { if (ok(it)) return it }
            }
        for (m in SOURCE_TAG.findAll(html)) {
            (attr(m.value, "srcset") ?: attr(m.value, "data-srcset"))?.let {
                val u = it.split(",")[0].trim().split(" ").firstOrNull { s -> s.isNotBlank() }
                if (!u.isNullOrBlank() && ok(u, m.value)) return u
            }
        }
        for (n in listOf("data-bg", "data-background", "data-bg-src")) {
            Regex("""\b$n\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(html)?.let { m ->
                val u = listOf(m.groupValues[1], m.groupValues[2]).firstOrNull { it.isNotBlank() } ?: return@let
                if (ok(u)) return u
            }
        }
        // d) background-image:url(...)（优先海报目录）
        Regex("""background(?:-image)?\s*:\s*url\(\s*['"]?([^'")]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val u = m.groupValues[1].trim()
                if (ok(u) && POSTER_HINT.containsMatchIn(u)) return u
            }
        // e) 命中海报目录的图片 URL
        for (m in IMG_URL.findAll(html)) {
            val u = m.groupValues[1]
            if (ok(u) && POSTER_HINT.containsMatchIn(u)) return u
        }
        // f) 兜底：第一张“像图”的 <img>
        for (m in IMG_TAG.findAll(html)) {
            val u = lazySrc(m.value) ?: continue
            if (ok(u, m.value)) return u
        }
        return null
    }

    /** 取出 `<img>` 的图片地址（懒加载优先，其次 srcset / src）。 */
    private fun lazySrc(tag: String): String? {
        lazyAttr(tag)?.let { return it }
        attr(tag, "srcset")?.let { return it.split(",")[0].trim().split(" ").firstOrNull { s -> s.isNotBlank() } }
        attr(tag, "src")?.let { if (it.isNotBlank() && !it.startsWith("data:")) return it }
        return null
    }

    /** 只认懒加载属性（不含 src）——用于"任意懒加载图"这一层。 */
    private fun lazyAttr(tag: String): String? {
        for (n in listOf("data-original", "data-src", "data-lazy-src", "data-echo", "data-original-src", "data-url", "data-img", "data-image")) {
            val v = attr(tag, n)
            if (!v.isNullOrBlank() && !v.startsWith("data:")) return v
        }
        return null
    }

    /** 该 URL（及所在标签）是否"像封面"（非占位、非 logo/图标/广告）。 */
    private fun ok(u: String, tag: String = ""): Boolean {
        if (u.isBlank() || u.startsWith("data:") || u.startsWith("#")) return false
        if (NEG_HINT.containsMatchIn(u)) return false
        if (tag.isNotEmpty() && NEG_HINT.containsMatchIn(tag)) return false
        return true
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
            .find(tag)?.let { m ->
                listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3]).firstOrNull { it.isNotEmpty() }
            }

    private fun pick(node: JsonObject, vararg keys: String): String {
        for (k in keys) {
            val s = valueToStr(node[k])
            if (s.isNotEmpty()) return s
        }
        return ""
    }

    private fun nameOf(v: JsonElement?): String = when (v) {
        is JsonObject -> pick(v, "name", "title")
        is JsonArray -> if (v.isEmpty()) "" else nameOf(v[0])
        else -> ""
    }

    private fun valueToStr(v: JsonElement?): String = when (v) {
        null, is JsonNull -> ""
        is JsonPrimitive -> if (v.isString) v.content.trim() else ""
        is JsonObject -> pick(v, "name", "url")
        is JsonArray -> if (v.isEmpty()) "" else valueToStr(v[0])
        else -> ""
    }

    // ── 打标 ────────────────────────────────────────────────────
    /** best-effort 打标。mp4/mov/m4v/mkv → 旁挂 .info.json。返回是否写入。 */
    fun tag(file: File, meta: Meta): Boolean {
        if (!file.exists()) return false
        val ext = file.extension.lowercase()
        if (ext !in TAG_EXTS) return false
        if (meta.isEmpty) return false
        return try {
            val json = buildJsonObject {
                put("title", meta.title)
                put("artist", meta.artist)
                put("description", meta.description)
                put("cover_url", meta.coverUrl)
            }
            File(file.parentFile, file.name + ".info.json").writeText(json.toString())
            true
        } catch (e: Exception) {
            false
        }
    }
}

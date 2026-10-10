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

        // ③ MacCMS / 通用海报兜底（页面无 og:image、无 JSON-LD image 时）
        if (cover.isEmpty()) cover = findPoster(html) ?: ""

        if (cover.isNotEmpty()) cover = MediaUtil.normalize(cover, pageUrl) ?: cover
        return Meta(title.take(200), artist.take(120), desc.take(300), cover)
    }

    /** 海报目录特征（MacCMS 的 /upload/vod/、常见 /cover/ /pic/ /poster/ 等）。 */
    private val POSTER_HINT = Regex("""/(?:upload/vod|upload/pic|cover|pic|poster|vodpic|thumb)/""", RegexOption.IGNORE_CASE)
    private val IMG_URL = Regex("""(https?://[^\s"'<>)\]]+?\.(?:jpg|jpeg|png|webp))""", RegexOption.IGNORE_CASE)

    /** 从页面里挑一张"像海报"的图：优先带 pic/cover/poster/thumb 类名的 <img>，其次命中海报目录的图片 URL，最后 background-image。 */
    private fun findPoster(html: String): String? {
        // a) <img> 类名/ id 带 pic|cover|poster|thumb
        for (m in Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = m.value
            if (!Regex("""(?:class|id)\s*=\s*["'][^"']*(?:pic|cover|poster|thumb)[^"']*["']""", RegexOption.IGNORE_CASE).containsMatchIn(tag)) continue
            val src = attr(tag, "data-original") ?: attr(tag, "data-src") ?: attr(tag, "src")
            if (!src.isNullOrEmpty() && src.startsWith("http")) return src
        }
        // b) 命中海报目录的任意图片 URL
        for (m in IMG_URL.findAll(html)) {
            val u = m.groupValues[1]
            if (POSTER_HINT.containsMatchIn(u)) return u
        }
        // c) background-image:url(...)
        Regex("""background(?:-image)?\s*:\s*url\(\s*['"]?([^'")]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { if (it.startsWith("http")) return it }
        return null
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

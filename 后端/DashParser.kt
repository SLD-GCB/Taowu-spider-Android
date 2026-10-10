package com.taowu.backend.download

import com.taowu.backend.parse.MediaUtil
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

// ================================================================
// DASH MPD 解析（基本支持：SegmentTemplate $Number$ / SegmentList / SegmentBase）
// 分别解析视频轨与音频轨（复合轨下载时两路都取，再合成）。
// ================================================================
object DashParser {

    data class DashTrack(val initUrl: String?, val mediaUrls: List<String>)
    data class DashTracks(val video: DashTrack?, val audio: DashTrack?)

    fun parseTracks(mpd: String, url: String): DashTracks {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(mpd.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement
        val presentationDur = isoDuration(root.getAttribute("mediaPresentationDuration"))

        val videoReps = ArrayList<Element>()
        val audioReps = ArrayList<Element>()
        val reps = ArrayList<Element>()
        collect(root, "Representation", reps)
        for (rep in reps) {
            when (kindOf(rep)) {
                "video" -> videoReps.add(rep)
                "audio" -> audioReps.add(rep)
                else -> {}
            }
        }
        val video = pickBest(videoReps)?.let { buildTrack(it, root, url, presentationDur) }
        val audio = pickBest(audioReps)?.let { buildTrack(it, root, url, presentationDur) }
        // 兜底：无法判类时，按单轨取最高带宽
        if (video == null && audio == null) {
            val any = pickBest(reps) ?: return DashTracks(null, null)
            return DashTracks(buildTrack(any, root, url, presentationDur), null)
        }
        return DashTracks(video, audio)
    }

    private fun pickBest(reps: List<Element>): Element? =
        reps.maxByOrNull { it.getAttribute("bandwidth").toIntOrNull() ?: 0 } ?: reps.firstOrNull()

    /** 判断 Representation 是视频还是音频（contentType / mimeType / 子节点）。 */
    private fun kindOf(rep: Element): String {
        // AdaptationSet 上的 contentType / mimeType
        val adapt = rep.parentNode
        if (adapt is Element) {
            val ct = adapt.getAttribute("contentType").lowercase()
            if (ct == "video" || ct == "audio") return ct
            val am = adapt.getAttribute("mimeType").lowercase()
            if (am.startsWith("video/")) return "video"
            if (am.startsWith("audio/")) return "audio"
        }
        val m = rep.getAttribute("mimeType").lowercase()
        if (m.startsWith("video/")) return "video"
        if (m.startsWith("audio/")) return "audio"
        // 有 width/height 属性 → 视频
        if (rep.getAttribute("width").isNotEmpty() || rep.getAttribute("height").isNotEmpty()) return "video"
        if (rep.getAttribute("audioSamplingRate").isNotEmpty()) return "audio"
        return ""
    }

    private fun buildTrack(rep: Element, root: Element, url: String, presentationDur: Double): DashTrack {
        var base = url
        for (node in pathTo(root, rep)) base = applyBaseUrl(node, base)

        val segTpl = nearest(rep, "SegmentTemplate")
        if (segTpl != null) {
            val media = segTpl.getAttribute("media")
            val init = segTpl.getAttribute("initialization").ifEmpty { null }
            val repId = rep.getAttribute("id")
            val bw = rep.getAttribute("bandwidth")
            if (media.isNotEmpty() && media.contains("\$Number\$")) {
                val startNum = segTpl.getAttribute("startNumber").toIntOrNull() ?: 1
                val timescale = segTpl.getAttribute("timescale").toIntOrNull() ?: 1
                val segDur = segTpl.getAttribute("duration").toDoubleOrNull() ?: 0.0
                var count = 0
                if (segDur > 0 && timescale > 0 && presentationDur > 0) {
                    count = Math.ceil(presentationDur / (segDur / timescale)).toInt()
                }
                val urls = ArrayList<String>()
                for (n in startNum until startNum + count) {
                    val u = media.replace("\$Number\$", n.toString())
                        .replace("\$RepresentationID\$", repId)
                        .replace("\$Bandwidth\$", bw)
                    urls.add(resolve(base, u))
                }
                val initUrl = init?.let { resolve(base, it.replace("\$RepresentationID\$", repId).replace("\$Bandwidth\$", bw)) }
                return DashTrack(initUrl, urls)
            }
            if (media.isNotEmpty() && media.contains("\$Time\$")) {
                return DashTrack(null, emptyList())   // SegmentTimeline 未移植
            }
        }

        val list = nearest(rep, "SegmentList")
        if (list != null) {
            val initEls = list.getElementsByTagName("Initialization")
            val initUrl = if (initEls.length > 0) resolve(base, (initEls.item(0) as Element).getAttribute("sourceURL")) else null
            val urls = ArrayList<String>()
            for (i in 0 until list.getElementsByTagName("SegmentURL").length) {
                val e = list.getElementsByTagName("SegmentURL").item(i) as Element
                val m = e.getAttribute("media")
                if (m.isNotEmpty()) urls.add(resolve(base, m))
            }
            return DashTrack(initUrl, urls)
        }

        for (i in 0 until rep.getElementsByTagName("BaseURL").length) {
            val m = (rep.getElementsByTagName("BaseURL").item(i) as Element).textContent.trim()
            if (m.isNotEmpty()) return DashTrack(null, listOf(resolve(base, m)))
        }
        return DashTrack(null, emptyList())
    }

    // ── DOM 工具 ────────────────────────────────────────────────
    private fun applyBaseUrl(node: Element, acc: String): String {
        var b = acc
        for (i in 0 until node.getElementsByTagName("BaseURL").length) {
            val e = node.getElementsByTagName("BaseURL").item(i)
            if (e.parentNode === node) {
                val txt = e.textContent.trim()
                if (txt.isNotEmpty()) b = MediaUtil.resolveUrl(b, txt)
            }
        }
        return b
    }

    private fun collect(node: Element, tag: String, out: MutableList<Element>) {
        val list = node.getElementsByTagName(tag)
        for (i in 0 until list.length) out.add(list.item(i) as Element)
    }

    private fun nearest(rep: Element, tag: String): Element? {
        var cur: Element? = rep
        while (cur != null) {
            for (i in 0 until cur.getElementsByTagName(tag).length) {
                val e = cur.getElementsByTagName(tag).item(i)
                if (e.parentNode === cur) return e as Element
            }
            val p = cur.parentNode
            cur = if (p is Element) p else null
        }
        return null
    }

    private fun pathTo(root: Element, target: Element): List<Element> {
        val path = ArrayList<Element>()
        var cur: Element? = target
        while (cur != null) {
            path.add(cur)
            if (cur === root) break
            val p = cur.parentNode
            cur = if (p is Element) p else null
        }
        return path.reversed()
    }

    private fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref else MediaUtil.resolveUrl(base, ref)

    private fun isoDuration(s: String): Double {
        if (s.isEmpty()) return 0.0
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?""").find(s) ?: return 0.0
        val hh = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val mm = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val ss = m.groupValues[3].toDoubleOrNull() ?: 0.0
        return hh * 3600 + mm * 60 + ss
    }
}

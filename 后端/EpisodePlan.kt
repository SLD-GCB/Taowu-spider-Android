package com.taowu.backend.parse

import com.taowu.backend.settings.AppSettings

// ================================================================
// 整部番剧分集计划（移植 video_grab.parse_episode_plan / _collect_anchors / _plan_from_anchors）。
// 从详情页/播放页 HTML 识别整部分集：优先站点适配器给的分集清单，否则 MacCMS 形状 / 通用聚类。
// ================================================================
object EpisodePlan {

    private val EP_TEXT_RES = listOf(
        Regex("""第\s*(\d{1,4})\s*[集话話话]"""),
        Regex("""第\s*(\d{1,4})"""),
        Regex("""^\s*(\d{1,4})\s*[集话話话]"""),
        Regex("""(?<![A-Za-z0-9])(?:ep|EP)\s*\.?\s*[:：\-]?\s*(\d{1,4})(?![A-Za-z0-9])"""),
    )
    // MacCMS 模板链接：/<模块>/<vodid>-<线路>-<集>（可选 .html/.htm/.php 后缀与尾斜杠）
    // 覆盖两种普遍形态：/v/56485-1-9/ 与 /p/139004-3-1.html（age 系 a_0001 模板）
    private val MACCMS_LINK_RE = Regex("""^/?([A-Za-z0-9_]+)/(\d{1,9})-(\d{1,4})-(\d{1,4})(?:\.[A-Za-z0-9]+)?/?$""")
    private val NAV_EP_RE = Regex(
        """上一[集话話]|下一[集话話]|播放|正片|预告|預告|简介|簡介|全集|更新|首播|加載|加载""",
        RegexOption.IGNORE_CASE,
    )

    data class Episode(val num: Int?, val label: String, val url: String, val line: Int?)
    data class Plan(
        val title: String,
        val url: String,
        val matched: String,     // adapter | maccms | generic | none
        val episodes: List<Episode>,
        val lines: Int,
        val notes: List<String>,
        val reason: String,
    )

    class Anchor(val text: String, val url: String, val path: String, var num: Int?) {
        var txtNum: Int? = null
        var line: Int? = null
        var nid: Int? = null
        var group: Any? = null
    }

    /** 播放页/详情页标题 → 动漫名：去掉"- 站点名"尾巴与" 第01集"部分。 */    fun cleanSeriesTitle(title: String?): String {
        var t = (title ?: "").trim()
        t = t.split(Regex("\\s*[-–]\\s*"), limit = 2)[0]
        val m = Regex("""第\s*\d{1,4}\s*[集话話]|_?第\d{1,4}集| · 第\d+集""").find(t)
        if (m != null) t = t.substring(0, m.range.first)
        return Regex("\\s+").replace(t, " ").trim(' ', '.', '-', '_', '—')
    }

    fun epNumFromText(text: String): Int? {
        for (rx in EP_TEXT_RES) rx.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    /** 一条线路至少要有这么多集才算"完整"（只有 1 集的线路多半是预告/残留，丢弃）。 */
    const val MIN_LINE_EPISODES = 2

    /**
     * 过滤不完整线路：当存在多集线路时，丢掉集数少于 [AppSettings.minLineEpisodes] 的线路
     * （line 为 null 的通用分集不动）。返回 (过滤后的分集, 被丢弃的线路数)。
     */
    fun dropIncompleteLines(episodes: List<Episode>): Pair<List<Episode>, Int> {
        if (episodes.isEmpty()) return episodes to 0
        val minPer = AppSettings.minLineEpisodes.coerceIn(1, 9)
        val byLine = episodes.groupBy { it.line }
        val maxCount = byLine.values.maxOf { it.size }
        if (maxCount < minPer) return episodes to 0              // 电影/单集：全部保留
        val badLines = byLine.filter { (line, list) -> line != null && list.size < minPer }.keys
        if (badLines.isEmpty()) return episodes to 0
        val kept = episodes.filter { it.line == null || it.line !in badLines }
        if (kept.isEmpty()) return episodes to 0                  // 兜底：全被滤掉就不滤
        return kept to badLines.size
    }

    private fun uriPath(url: String): String = try {
        java.net.URI(url).path ?: "/"
    } catch (e: Exception) {
        "/"
    }

    /** URL 路径指纹：纯数字段替换为 @（供通用启发式给同族分集链接聚类）。 */
    fun pathFp(path: String): String =
        path.trimEnd('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { seg ->
            val s = Regex("""\.(html?|htm|php)$""", RegexOption.IGNORE_CASE).replace(seg, "")
            Regex("\\d+").replace(s, "@")
        }

    /** 收集页面所有 <a>（含 data-href/data-url/data-link 兜底），识别分集号。 */
    fun collectAnchors(html: String, pageUrl: String): List<Anchor> {
        val out = ArrayList<Anchor>()
        val attrNames = listOf("href", "data-href", "data-url", "data-link")
        Regex("""<a\b([^>]*)>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html).forEach { m ->
                val attribs = m.groupValues[1]
                val inner = m.groupValues[2]
                var href = ""
                for (name in attrNames) {
                    val hm = Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(attribs)
                    if (hm != null) {
                        href = listOf(hm.groupValues[1], hm.groupValues[2], hm.groupValues[3]).firstOrNull { it.isNotEmpty() } ?: ""
                        break
                    }
                }
                if (href.isEmpty()) return@forEach
                var text = MediaUtil.safeUnescape(Regex("<[^>]+>").replace(inner, " "))
                text = Regex("\\s+").replace(text, " ").trim()
                if (text.isEmpty()) return@forEach
                val u = MediaUtil.normalize(href, pageUrl) ?: return@forEach
                if (!u.startsWith("http://") && !u.startsWith("https://")) return@forEach
                out.add(Anchor(text, u, uriPath(u).ifEmpty { "/" }, epNumFromText(text)))
            }
        return out
    }

    /**
     * 分集链接正则的最终取值：**配方自报的优先**（站点专属，随 App 内置发布），
     * 该站没有配方时再用「设置 → 解析 → 额外分集链接正则」里那条全局的。
     */
    fun effectiveEpisodeLinkRegex(pageUrl: String): String =
        SiteAdapters.episodeLinkFor(pageUrl)?.takeIf { it.isNotBlank() }
            ?: AppSettings.extraEpisodeLinkRegex

    /** 通用模式用：页面里第一个分集播放页链接（详情页 → 播放页 跳转）。也认站点正则那种形状。 */
    fun firstMacCmsAnchor(
        html: String,
        pageUrl: String,
        extraLinkRegex: String = effectiveEpisodeLinkRegex(pageUrl),
    ): String? {
        val extraRe = extraLinkRegex.trim().takeIf { it.isNotEmpty() }
            ?.let { runCatching { Regex(it) }.getOrNull() }
        return collectAnchors(html, pageUrl).firstOrNull { a ->
            val p = a.path.trimEnd('/')
            MACCMS_LINK_RE.matchEntire(p) != null ||
                (extraRe?.matchEntire(p.trimStart('/'))?.groupValues?.size ?: 0) >= 4
        }?.url
    }

    private fun emptyPlan(pageUrl: String, title: String, notes: List<String>, reason: String) =
        Plan(title, pageUrl, "none", emptyList(), 0, notes, reason)

    /** 从详情页/分集播放页识别整部分集。 */
    fun parse(html: String, pageUrl: String): Plan {
        val title = cleanSeriesTitle(StaticExtract.extractTitle(html, pageUrl))
        val ad = SiteAdapters.apply(pageUrl, html, pageUrl)
        if (ad.episodes.isNotEmpty()) {
            val eps = ArrayList<Episode>()
            val seen = HashSet<String>()
            for (a in ad.episodes) {
                if (a.url.isNotEmpty() && seen.add(a.url)) eps.add(Episode(a.num, a.label, a.url, null))
            }
            if (eps.isNotEmpty()) return Plan(title, pageUrl, "adapter", eps, 1, listOf("站点适配器给出分集清单"), "")
        }
        val anchors = collectAnchors(html, pageUrl)
        if (anchors.isEmpty()) return emptyPlan(pageUrl, title, emptyList(), "页面无任何链接")
        return planFromAnchors(anchors, pageUrl, title)
    }

    /** 由锚点列表识别整部（macs/gens 两路判定核心）。 */
    fun planFromAnchors(
        anchors: List<Anchor>,
        pageUrl: String,
        title: String,
        extraLinkRegex: String = effectiveEpisodeLinkRegex(pageUrl),
    ): Plan {
        val notes = ArrayList<String>()
        if (anchors.isEmpty()) return emptyPlan(pageUrl, title, notes, "页面无任何链接")

        val pgPath = uriPath(pageUrl).trimEnd('/')
        val pm = MACCMS_LINK_RE.matchEntire(pgPath)
        // 额外分集链接正则（用户可配，3 组：分组/线路/集；按"去掉首斜杠"的路径匹配）
        val extraRe = extraLinkRegex.trim().takeIf { it.isNotEmpty() }
            ?.let { runCatching { Regex(it) }.getOrNull() }
        val pme = extraRe?.matchEntire(pgPath.trimStart('/'))
        val macs = ArrayList<Anchor>()
        val gens = ArrayList<Anchor>()
        for (a in anchors) {
            val path = a.path.trimEnd('/')
            val mac = MACCMS_LINK_RE.matchEntire(path)
            val ex = if (mac == null && extraRe != null) extraRe.matchEntire(path.trimStart('/')) else null
            when {
                mac != null -> {
                    a.txtNum = a.num
                    a.group = mac.groupValues[1] to mac.groupValues[2]
                    a.line = mac.groupValues[3].toInt()
                    a.nid = mac.groupValues[4].toInt()
                    if (a.num == null) a.num = a.nid
                    macs.add(a)
                }
                ex != null && ex.groupValues.size >= 4 -> {
                    a.txtNum = a.num
                    a.group = ex.groupValues[1]
                    a.line = ex.groupValues[2].toIntOrNull() ?: 0
                    a.nid = ex.groupValues[3].toIntOrNull() ?: 0
                    if (a.num == null) a.num = a.nid
                    macs.add(a)
                }
                a.num != null -> {
                    a.line = null
                    a.group = pathFp(a.path)
                    gens.add(a)
                }
            }
        }

        val episodes = ArrayList<Episode>()
        if (macs.isNotEmpty()) {
            val cnt = HashMap<Any?, Int>()
            for (a in macs) cnt[a.group] = (cnt[a.group] ?: 0) + 1
            var target: Any? = null
            if (pm != null) {
                val cand = pm.groupValues[1] to pm.groupValues[2]
                if (cnt.containsKey(cand)) target = cand
            } else if (pme != null && pme.groupValues.size >= 4) {
                val cand = pme.groupValues[1]
                if (cnt.containsKey(cand)) target = cand
            }
            if (target == null) target = cnt.maxByOrNull { it.value }?.key
            val lines = LinkedHashMap<Int, MutableList<Anchor>>()
            for (a in macs) if (a.group == target) lines.getOrPut(a.line!!) { ArrayList() }.add(a)
            for (line in lines.keys.sorted()) {
                val arr = lines[line]!!.sortedWith(compareBy({ it.num ?: Int.MAX_VALUE }, { it.nid ?: Int.MAX_VALUE }))
                val seenUrl = HashSet<String>()
                for (a in arr) {
                    if (a.txtNum == null && NAV_EP_RE.containsMatchIn(a.text)) continue
                    if (!seenUrl.add(a.url)) continue
                    episodes.add(Episode(a.num, a.text, a.url, line))
                }
            }
            if (episodes.isNotEmpty()) {
                return Plan(title, pageUrl, "maccms", episodes, lines.size, notes, "")
            }
        }

        // 通用启发式：按路径指纹聚类，选锚点最多、且半数以上含分集号的一族
        if (gens.isNotEmpty()) {
            val fam = LinkedHashMap<Any?, MutableList<Anchor>>()
            for (a in gens) fam.getOrPut(a.group) { ArrayList() }.add(a)
            val anchorMin = AppSettings.seriesAnchorMin.coerceIn(2, 20)
            var best: Pair<Int, Int>? = null
            var bestKey: Any? = null
            for ((g, arr) in fam) {
                if (arr.size < anchorMin) continue
                val numy = arr.count { it.num != null }
                if (numy * 2 < arr.size) continue
                val cand = arr.size to numy
                val b = best
                if (b == null || cand.first > b.first || (cand.first == b.first && cand.second > b.second)) {
                    best = cand
                    bestKey = g
                }
            }
            if (bestKey != null) {
                val seen = HashSet<String>()
                for (a in fam[bestKey]!!.sortedBy { it.num ?: Int.MAX_VALUE }) {
                    if (!seen.add(a.url)) continue
                    episodes.add(Episode(a.num, a.text, a.url, null))
                }
                if (episodes.isNotEmpty()) {
                    return Plan(title, pageUrl, "generic", episodes, 1, notes, "")
                }
            }
        }

        notes.add("未识别到分集列表链接")
        return emptyPlan(pageUrl, title, notes, "未识别到分集列表链接")
    }
}

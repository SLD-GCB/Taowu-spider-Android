package com.taowu.backend.parse

import com.taowu.backend.registry.netlocOf

// ================================================================
// 站点/CMS 适配器注册表（移植 video_grab.register_site_adapter / _apply_adapters 等）。
// 一站一条：host_suffix 命中时调 finder(html, page_url) 取裸媒体 URL / 分集；最长后缀匹配、可覆盖。
// 附加槽：deobf（播放器字段私有混淆解码 fn(payload)->List<String>）。
// ================================================================
object SiteAdapters {

    private class Rec(
        var fn: ((String, String) -> Any?)? = null,
        var serial: Boolean = false,
        var deobf: ((String) -> List<String>)? = null,
        /** 站点自报的"分集锚点路径"正则（来自配方 episode_link.re）。 */
        var episodeLink: String? = null,
    )

    private val map = LinkedHashMap<String, Rec>()
    private val lock = Any()

    fun register(
        hostSuffix: String,
        finder: ((String, String) -> Any?)? = null,
        serial: Boolean = false,
        deobf: ((String) -> List<String>)? = null,
        episodeLink: String? = null,
    ) {
        val hs = hostSuffix.trim().lowercase().trimStart('*').trimStart('.').trimEnd('.')
        if (hs.isEmpty()) return
        synchronized(lock) {
            val rec = map.getOrPut(hs) { Rec() }
            if (finder != null) { rec.fn = finder; rec.serial = serial }
            if (deobf != null) rec.deobf = deobf
            if (!episodeLink.isNullOrBlank()) rec.episodeLink = episodeLink
        }
    }

    fun reset() = synchronized(lock) { map.clear() }
    fun registeredSuffixes(): List<String> = synchronized(lock) { map.keys.toList() }

    /** 命中 host 的最长后缀 key；无命中 null。 */
    fun matchSuffix(hostOrUrl: String): String? {
        val host = netlocOf(hostOrUrl)
        if (host.isEmpty()) return null
        synchronized(lock) {
            return map.keys.filter { host == it || host.endsWith(".$it") }.maxByOrNull { it.length }
        }
    }

    fun serialFor(hostOrUrl: String): Boolean {
        val k = matchSuffix(hostOrUrl) ?: return false
        synchronized(lock) { return map[k]?.serial ?: false }
    }

    fun deobfFor(hostOrUrl: String): ((String) -> List<String>)? {
        val k = matchSuffix(hostOrUrl) ?: return null
        synchronized(lock) { return map[k]?.deobf }
    }

    /** 命中站点自报的分集锚点路径正则（没有则 null）。 */
    fun episodeLinkFor(hostOrUrl: String): String? {
        val k = matchSuffix(hostOrUrl) ?: return null
        synchronized(lock) { return map[k]?.episodeLink }
    }

    data class Episode(val num: Int?, val label: String, val url: String, val line: Any?)
    data class Result(val candidates: List<String>, val episodes: List<Episode>)

    /** 命中则跑 finder，返回规整结果；无命中/异常 → 空。 */
    fun apply(hostOrUrl: String, html: String, pageUrl: String): Result {
        val k = matchSuffix(hostOrUrl) ?: return Result(emptyList(), emptyList())
        val fn = synchronized(lock) { map[k]?.fn } ?: return Result(emptyList(), emptyList())
        return try {
            normalize(fn(html, pageUrl))
        } catch (e: Exception) {
            Result(emptyList(), emptyList())
        }
    }

    /** list/tuple = 媒体 URL 列表；dict 可含 candidates/urls 与 episodes。 */
    private fun normalize(out: Any?): Result {
        val cands = ArrayList<String>()
        val eps = ArrayList<Episode>()
        when (out) {
            is List<*> -> for (x in out) x?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { cands.add(it) }
            is Map<*, *> -> {
                (out["candidates"] ?: out["urls"])?.let { c ->
                    if (c is List<*>) for (x in c) x?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { cands.add(it) }
                }
                (out["episodes"])?.let { e ->
                    if (e is List<*>) for (x in e) {
                        if (x is Map<*, *>) {
                            val u = x["url"]?.toString()?.trim() ?: continue
                            if (u.isNotEmpty()) eps.add(Episode((x["num"] as? Number)?.toInt(), x["label"]?.toString() ?: "", u, x["line"]))
                        }
                    }
                }
            }
            else -> {}
        }
        return Result(cands, eps)
    }
}

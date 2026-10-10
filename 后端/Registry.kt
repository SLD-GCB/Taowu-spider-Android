package com.taowu.backend.registry

import com.taowu.backend.json.strField
import com.taowu.backend.json.strOrNull
import com.taowu.backend.json.primContent
import com.taowu.backend.model.Track
import com.taowu.backend.run.RecipeEngine
import com.taowu.backend.schema.validateSpec
import kotlinx.serialization.json.*

/** host 是否等于/以 suffix 结尾（子域命中），带前缀边界保护。 */
fun hostSuffixMatch(host: String?, suffix: String?): Boolean {
    val h = (host ?: "").trim().lowercase().trimEnd('.')
    val s = (suffix ?: "").trim().lowercase().trimStart('.').trimEnd('.')
    if (h.isEmpty() || s.isEmpty()) return false
    return h == s || h.endsWith(".$s")
}

/** URL → 去端口/去 userinfo 的 netloc（lower）。失败返回 ""。 */
fun netlocOf(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    try {
        val u = java.net.URI(url)
        val host = u.host
        if (host != null) return host.lowercase().trimEnd('.')
    } catch (_: Exception) {
    }
    return try {
        val s = url.substringAfter("://", url)
        val auth = s.substringBefore('/').substringBefore('?').substringBefore('#')
        auth.substringAfterLast('@').substringBefore(':').lowercase().trimEnd('.')
    } catch (e: Exception) {
        ""
    }
}

fun canonUrl(url: String?): String = (url ?: "").split("#", limit = 2)[0]

/** 按 (mediaClass, kind, 规范URL) 去重，保序。 */
fun dedupeTracks(tracks: List<Track?>): List<Track> {
    val seen = HashSet<Triple<String, String, String>>()
    val out = ArrayList<Track>()
    for (t in tracks) {
        if (t == null || t.url.isEmpty()) continue
        val key = Triple(t.mediaClass, t.kind, canonUrl(t.url))
        if (!seen.add(key)) continue
        out.add(t)
    }
    return out
}

data class ResolverEntry(
    val hostSuffix: String,
    val resolve: (String) -> List<Track>,
    val name: String,
    val enabled: Boolean,
    val order: Int,
)

/** 平台配方注册表（host_suffix 最长匹配优先，同 suffix 覆盖）。 */
class RecipeRegistry {
    private val resolvers = ArrayList<ResolverEntry>()
    private val lock = Any()

    fun register(
        hostSuffix: String,
        resolve: (String) -> List<Track>,
        name: String = "",
        enabled: Boolean = true,
        order: Int = 100,
    ): ResolverEntry {
        val suffix = (hostSuffix ?: "").trim().lowercase().trimStart('.')
        val e = ResolverEntry(suffix, resolve, name.ifEmpty { hostSuffix }, enabled, order)
        synchronized(lock) {
            resolvers.removeAll { it.hostSuffix == e.hostSuffix }
            resolvers.add(e)
            resolvers.sortWith(compareBy({ it.order }, { it.hostSuffix }))
        }
        return e
    }

    fun reset() = synchronized(lock) { resolvers.clear() }
    fun snapshot(): List<ResolverEntry> = synchronized(lock) { resolvers.toList() }

    /** URL → 命中的配方 entry（enabled 且后缀最长命中优先）。 */
    fun forUrl(url: String): ResolverEntry? {
        val host = netlocOf(url)
        if (host.isEmpty()) return null
        var hit: ResolverEntry? = null
        synchronized(lock) {
            for (e in resolvers) {
                if (!e.enabled) continue
                if (hostSuffixMatch(host, e.hostSuffix)) {
                    if (hit == null || e.hostSuffix.length > hit!!.hostSuffix.length) hit = e
                }
            }
        }
        return hit
    }
}

private val jsonParser = Json { ignoreUnknownKeys = true }

/** 解析一份配方文本 → JsonObject；缺 name 用文件名主干补。 */
fun loadRecipeText(text: String, fileStem: String): Pair<JsonObject?, String?> {
    if (text.toByteArray(Charsets.UTF_8).size > 512 * 1024) return null to "配方文件超过 512KB"
    return try {
        val el = jsonParser.parseToJsonElement(text)
        if (el !is JsonObject) return null to "配方根必须是 JSON 对象"
        if (!el.containsKey("name")) {
            JsonObject(el.toMutableMap().also { it["name"] = JsonPrimitive(fileStem) }) to null
        } else el to null
    } catch (e: Exception) {
        null to "JSON 解析失败：${e.message}"
    }
}

private fun jsonTruthyEnabled(spec: JsonObject): Boolean {
    val e = spec["enabled"] ?: return true
    val p = e as? JsonPrimitive ?: return true
    return p.booleanOrNull ?: true
}

/** 把一份配方注册进 registry（含 host_suffix 解析器）。返回是否成功。 */
fun registerSpec(registry: RecipeRegistry, engine: RecipeEngine, spec: JsonObject): Boolean {
    if (validateSpec(spec).isNotEmpty()) return false
    if (!jsonTruthyEnabled(spec)) return false
    val suffixes: List<String> = when (val hs = spec["host_suffix"]) {
        is JsonArray -> hs.mapNotNull { it.strOrNull() }
        else -> listOfNotNull(hs?.strOrNull())
    }
    if (suffixes.isEmpty()) return false
    val name = spec.strField("name")
    val order = spec["order"].primContent()?.toDoubleOrNull()?.toInt() ?: 100
    // 站点自报的分集链接形状（配方 episode_link.re）→ 注册进站点表，供通用模式识别分集用
    val episodeLink = (spec["episode_link"] as? JsonObject)?.get("re")?.strOrNull()?.takeIf { it.isNotBlank() }
    for (s in suffixes) {
        registry.register(s, { url -> engine.tracksFromSpec(spec, url) }, name = name, enabled = true, order = order)
        if (episodeLink != null) {
            com.taowu.backend.parse.SiteAdapters.register(s, episodeLink = episodeLink)
        }
    }
    return true
}

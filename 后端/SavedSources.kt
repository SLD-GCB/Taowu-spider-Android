package com.taowu.backend.save

import android.content.Context
import kotlinx.serialization.json.*
import java.io.File

// ================================================================
// 解析源存档：把「解析」出来的直链/分集保存下来，供内容页「已解析」在线看。
// 存 filesDir/saved_sources.json（JSON 数组）。同 pageUrl 覆盖。
// ================================================================
object SavedSources {

    private const val FILE = "saved_sources.json"

    data class SavedTrack(
        val kind: String,
        val url: String,
        val ext: String,
        val mediaClass: String,
        val titleHint: String,
        val pageUrl: String,
    )

    data class SavedEpisode(
        val label: String,
        val num: Int?,
        val candidates: List<String>,   // 该集各线路的播放页 URL
    )

    data class Item(
        val id: String,
        val title: String,
        val pageUrl: String,
        val savedAt: Long,
        val mode: String,               // GENERAL | RECIPE
        val tracks: List<SavedTrack>,
        val episodes: List<SavedEpisode>,
    )

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun load(ctx: Context): List<Item> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val text = try { f.readText() } catch (e: Exception) { return emptyList() }
        val arr = try { Json.parseToJsonElement(text) as? JsonArray } catch (e: Exception) { null } ?: return emptyList()
        return arr.mapNotNull { el -> parseItem(el as? JsonObject ?: return@mapNotNull null) }
            .sortedByDescending { it.savedAt }
    }

    /** 保存一条（同 pageUrl 覆盖旧的）。 */
    fun add(ctx: Context, item: Item): Boolean {
        val list = load(ctx).filterNot { it.pageUrl == item.pageUrl }.toMutableList()
        list.add(item)
        return write(ctx, list)
    }

    fun delete(ctx: Context, id: String): Boolean =
        write(ctx, load(ctx).filterNot { it.id == id })

    fun clear(ctx: Context): Boolean = write(ctx, emptyList())

    // ── 完整性判定（按实际情况）────────────────────────────────
    private val MOVIE_LABEL = Regex("""正片|全集|完整版|剧场版|劇場版|电影|電影|蓝光|藍光|OVA|OAD|SP|特别篇|特別篇""", RegexOption.IGNORE_CASE)
    private val EPISODE_LABEL = Regex("""第\s*\d+\s*[集话話]|^\s*\d+\s*$|(?i)\b(?:ep|episode)\s*\.?\s*\d+""")

    /**
     * 该解析结果是否"集数齐全"，可以保存。
     *  · 无分集 → 有直链轨即完整；
     *  · 分集 ≥2 → 完整；
     *  · 只有 1 集 → 逐案看：该集有多条线路候选（多源都只有这一集，典型电影）、或集名/标题带
     *    "正片/全集/剧场版"等 → 完整；若集名是"第N集"式且只有一条线路 → 判为"没抓全"。
     */
    fun isComplete(episodes: List<SavedEpisode>, hasTracks: Boolean, title: String): Boolean {
        if (episodes.isEmpty()) return hasTracks
        if (episodes.size >= 2) return true
        val e = episodes[0]
        return e.candidates.size >= 2 ||
            MOVIE_LABEL.containsMatchIn(e.label) ||
            MOVIE_LABEL.containsMatchIn(title) ||
            !EPISODE_LABEL.containsMatchIn(e.label)
    }

    private fun write(ctx: Context, list: List<Item>): Boolean = try {
        val arr = buildJsonArray {
            for (it in list) add(itemToJson(it))
        }
        file(ctx).writeText(arr.toString())
        true
    } catch (e: Exception) {
        false
    }

    private fun itemToJson(it: Item): JsonElement = buildJsonObject {
        put("id", it.id)
        put("title", it.title)
        put("page_url", it.pageUrl)
        put("saved_at", it.savedAt)
        put("mode", it.mode)
        put("tracks", buildJsonArray {
            for (t in it.tracks) add(buildJsonObject {
                put("kind", t.kind); put("url", t.url); put("ext", t.ext)
                put("media_class", t.mediaClass); put("title_hint", t.titleHint); put("page_url", t.pageUrl)
            })
        })
        put("episodes", buildJsonArray {
            for (e in it.episodes) add(buildJsonObject {
                put("label", e.label)
                put("num", e.num?.let { n -> JsonPrimitive(n) } ?: JsonNull)
                put("candidates", buildJsonArray { for (c in e.candidates) add(c) })
            })
        })
    }

    private fun parseItem(o: JsonObject): Item? {
        val id = o.str("id") ?: return null
        val tracks = (o["tracks"] as? JsonArray)?.mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            val url = t.str("url") ?: return@mapNotNull null
            SavedTrack(
                t.str("kind") ?: "direct", url, t.str("ext") ?: "",
                t.str("media_class") ?: "", t.str("title_hint") ?: "", t.str("page_url") ?: "",
            )
        } ?: emptyList()
        val episodes = (o["episodes"] as? JsonArray)?.mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            val cands = (e["candidates"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            SavedEpisode(e.str("label") ?: "", (e["num"] as? JsonPrimitive)?.intOrNull, cands)
        } ?: emptyList()
        return Item(
            id = id,
            title = o.str("title") ?: "",
            pageUrl = o.str("page_url") ?: "",
            savedAt = (o["saved_at"] as? JsonPrimitive)?.longOrNull ?: 0L,
            mode = o.str("mode") ?: "GENERAL",
            // 有分集的源：丢掉混入的直链轨（那可能只是第 1 集的 m3u8）
            tracks = if (episodes.isNotEmpty()) emptyList() else tracks,
            episodes = episodes,
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

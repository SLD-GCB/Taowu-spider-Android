package com.taowu.backend.field

import android.content.Context
import com.taowu.backend.settings.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** 一条采集结果：一个页面抽出来的一行 + 来源信息。 */
data class CollectRow(
    val url: String,
    val site: String,
    val template: String,
    val time: Long,
    val values: Map<String, String>,   // 列名 → 值（保持模板列序）
)

/**
 * 采集结果存储（含导出）。
 * 存 filesDir/collect.json，按 url 去重（重复采集同一页则覆盖），最新的在前，上限可配。
 */
object CollectStore {

    private const val FILE = "collect.json"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun load(ctx: Context): List<CollectRow> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val text = runCatching { f.readText() }.getOrNull() ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { parseRow(it) }
    }

    /** 记一条（同 url 覆盖并置顶）。返回更新后的列表。 */
    fun add(ctx: Context, row: CollectRow): List<CollectRow> {
        if (row.url.isBlank()) return load(ctx)
        val cap = AppSettings.collectMax.coerceIn(1, 100000)
        val list = ArrayList<CollectRow>(cap + 1)
        list.add(row)
        for (r in load(ctx)) if (r.url != row.url && list.size < cap) list.add(r)
        write(ctx, list)
        return list
    }

    fun remove(ctx: Context, url: String): List<CollectRow> {
        val list = load(ctx).filterNot { it.url == url }
        write(ctx, list)
        return list
    }

    fun clear(ctx: Context): List<CollectRow> {
        write(ctx, emptyList())
        return emptyList()
    }

    // ── 导出 ──────────────────────────────────────────────────────
    /** CSV 表头 = 各行列名的并集（按首见顺序），第一列固定 job_url。 */
    fun toCsv(rows: List<CollectRow>): String {
        val cols = LinkedHashSet<String>()
        cols.add("job_url")
        for (r in rows) for (k in r.values.keys) if (k != "job_url") cols.add(k)
        val sb = StringBuilder()
        sb.append(cols.joinToString(",") { csvCell(it) }).append("\r\n")
        for (r in rows) {
            sb.append(cols.joinToString(",") { k ->
                csvCell(if (k == "job_url") r.url else (r.values[k] ?: ""))
            }).append("\r\n")
        }
        return sb.toString()
    }

    fun toJson(rows: List<CollectRow>): String {
        val arr = buildJsonArray {
            for (r in rows) add(buildJsonObject {
                put("url", JsonPrimitive(r.url))
                put("site", JsonPrimitive(r.site))
                put("template", JsonPrimitive(r.template))
                put("time", JsonPrimitive(r.time))
                put("data", buildJsonObject {
                    for ((k, v) in r.values) put(k, JsonPrimitive(v))
                })
            })
        }
        return json.encodeToString(JsonArray.serializer(), arr)
    }

    private fun csvCell(s: String): String {
        val v = s.replace("\r", " ").replace("\n", " ")
        return if (v.contains(',') || v.contains('"')) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    // ── 序列化 ────────────────────────────────────────────────────
    private fun parseRow(el: kotlinx.serialization.json.JsonElement): CollectRow? {
        val o = runCatching { el.jsonObject }.getOrNull() ?: return null
        val url = o["url"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: return null
        val values = LinkedHashMap<String, String>()
        runCatching { o["data"]?.jsonObject }.getOrNull()?.forEach { (k, v) ->
            values[k] = runCatching { v.jsonPrimitive.content }.getOrNull() ?: ""
        }
        return CollectRow(
            url = url,
            site = o["site"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: "",
            template = o["template"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: "",
            time = o["time"]?.let { runCatching { it.jsonPrimitive.content.toLongOrNull() }.getOrNull() } ?: 0L,
            values = values,
        )
    }

    private fun write(ctx: Context, list: List<CollectRow>) {
        runCatching {
            val arr = buildJsonArray {
                for (r in list) add(buildJsonObject {
                    put("url", JsonPrimitive(r.url))
                    put("site", JsonPrimitive(r.site))
                    put("template", JsonPrimitive(r.template))
                    put("time", JsonPrimitive(r.time))
                    put("data", buildJsonObject {
                        for ((k, v) in r.values) put(k, JsonPrimitive(v))
                    })
                })
            }
            file(ctx).writeText(json.encodeToString(JsonArray.serializer(), arr))
        }
    }
}

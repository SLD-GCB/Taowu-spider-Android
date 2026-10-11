package com.taowu.backend.field

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** 一套字段模板（数据表模板）：一份 JSON = 建表模板 + 字段匹配配置。 */
data class FieldTemplate(
    val name: String,          // 表名 / 显示名
    val id: String,
    val desc: String,
    val engine: String,
    val columns: List<FieldColumn>,
    val source: String,        // builtin / user
    val file: String,
)

/** 规则库的一条规则（字段规则片段里的值）。 */
data class RuleDef(
    val synonyms: List<String>,
    val clean: String?,
    val re: List<String>,
)

/** 用户导入的一份文字抓取配置（模板或规则片段）。 */
data class UserCfg(val file: String, val kind: String, val label: String)

/**
 * 字段模板 / 规则库装载与合并 —— 移植自 PC 版 `config.load_field_rules` + `_resolve_column`。
 *
 * 两套库：内置（assets/fieldrules、assets/fieldtemplates）+ 用户（filesDir/fieldcfg），
 * **同名用户覆盖内置**。两种形状按内容自动识别：
 *  · 顶层有非空 `columns[]` → 字段模板；
 *  · 否则 `{规则名: {...}}` → 规则片段。
 */
object FieldSchema {

    private const val TAG = "taowu.field"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val templates = LinkedHashMap<String, FieldTemplate>()   // key = 名称
    private val rules = LinkedHashMap<String, RuleDef>()             // key = 规则名
    private val userFiles = LinkedHashMap<String, UserCfg>()         // key = 文件名
    private var loaded = false

    fun userDir(context: Context): File = File(context.filesDir, "fieldcfg").apply { mkdirs() }

    fun init(context: Context) { if (!loaded) reload(context) }

    /** 全量重建：先收全部规则，再收全部模板（这样模板引用的规则一定能解析）。 */
    fun reload(context: Context) {
        templates.clear()
        rules.clear()

        val sources = ArrayList<Source>()   // (text, stem, source, file)

        // ① 内置 assets（规则目录先于模板目录）
        for (dir in listOf("fieldrules", "fieldtemplates")) {
            val names = try { context.assets.list(dir) ?: emptyArray() } catch (e: Exception) { emptyArray() }
            for (n in names.sorted()) {
                if (!n.endsWith(".json") || n.startsWith("_")) continue
                val text = try { context.assets.open("$dir/$n").bufferedReader().use { it.readText() } } catch (e: Exception) { continue }
                sources.add(Source(text, n.removeSuffix(".json"), "builtin", n))
            }
        }
        // ② 用户导入（filesDir/fieldcfg）
        userFiles.clear()
        val files = userDir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
        for (f in files) {
            val text = try { f.readText() } catch (e: Exception) { continue }
            val stem = f.name.removeSuffix(".json")
            sources.add(Source(text, stem, "user", f.name))
            val root = rootOf(text)
            val cols = root?.get("columns") as? JsonArray
            if (cols != null && cols.isNotEmpty()) {
                val name = root.str("name").ifBlank { root.str("id") }.ifBlank { stem }
                userFiles[f.name] = UserCfg(f.name, "template", name)
            } else {
                val n = root?.entries?.count { (k, v) -> !k.startsWith("_") && runCatching { v.jsonObject }.isSuccess } ?: 0
                userFiles[f.name] = UserCfg(f.name, "rules", "规则 ×$n")
            }
        }

        // 第一遍：规则（用户在后 → 覆盖内置）
        for (s in sources) parseRulesInto(s)
        // 第二遍：模板（用户在后 → 覆盖内置）
        for (s in sources) parseTemplateInto(s)

        loaded = true
        Log.d(TAG, "字段模板 ${templates.size} 套，规则 ${rules.size} 条")
    }

    fun templateList(): List<FieldTemplate> =
        templates.values.sortedWith(compareBy({ if (it.engine == "schema_v2") 0 else 1 }, { it.name }))

    fun template(name: String): FieldTemplate? = templates[name]
    fun ruleCount(): Int = rules.size

    /** 用户导入的配置清单（模板 / 规则片段），可删除。 */
    fun userConfigs(): List<UserCfg> = userFiles.values.toList()

    // ── 内部 ──────────────────────────────────────────────────────

    private class Source(val text: String, val stem: String, val source: String, val file: String)

    private fun rootOf(text: String): JsonObject? =
        try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) { null }

    private fun parseRulesInto(s: Source) {
        val root = rootOf(s.text) ?: return
        val cols = root["columns"] as? JsonArray
        if (cols != null && cols.isNotEmpty()) return   // 是模板，不是规则片段
        for ((k, v) in root) {
            if (k.startsWith("_")) continue
            val obj = runCatching { v.jsonObject }.getOrNull() ?: continue
            rules[k] = RuleDef(
                synonyms = obj.strList("synonyms"),
                clean = obj["clean"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
                re = obj.strList("re"),
            )
        }
    }

    private fun parseTemplateInto(s: Source) {
        val root = rootOf(s.text) ?: return
        val colsEl = root["columns"] as? JsonArray
        if (colsEl == null || colsEl.isEmpty()) return   // 是规则片段
        val name = root.str("name").ifBlank { root.str("id") }.ifBlank { s.stem }
        val merged = colsEl.mapNotNull { e ->
            val o = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
            mergeOne(parseRawColumn(o), rules)
        }
        if (merged.isEmpty()) return
        templates[name] = FieldTemplate(
            name = name,
            id = root.str("id").ifBlank { name },
            desc = root.str("desc"),
            engine = root.str("engine"),
            columns = merged,
            source = s.source,
            file = s.file,
        )
    }

    private class RawCol(
        val name: String,
        val type: String,
        val clean: String,
        val synonyms: List<String>,
        val re: List<String>,
        val rule: String,
    )

    private fun parseRawColumn(o: JsonObject): RawCol = RawCol(
        name = o.str("name").trim(),
        type = o.str("type").ifBlank { "TEXT" },
        clean = o.str("clean"),
        synonyms = o.strList("synonyms"),
        re = o.strList("re"),
        rule = o.str("rule"),
    )

    /** 合并一列与其引用的规则（对齐 config._resolve_column）。 */
    private fun mergeOne(c: RawCol, ruleLib: Map<String, RuleDef>): FieldColumn {
        val r = c.rule.takeIf { it.isNotBlank() }?.let { ruleLib[it] }
        return if (r == null) {
            FieldColumn(c.name, c.type.ifBlank { "TEXT" }, c.clean.ifBlank { "text" }, c.synonyms, c.re)
        } else {
            FieldColumn(
                name = c.name,
                type = c.type.ifBlank { "TEXT" },                            // 列内覆盖规则
                clean = c.clean.ifBlank { r.clean ?: "" }.ifBlank { "text" }, // 列内覆盖规则
                synonyms = dedupe(r.synonyms + c.synonyms),                   // 规则在前 + 列内追加
                re = dedupe(c.re + r.re),                                     // 列内在前 + 规则追加
            )
        }
    }

    private fun dedupe(xs: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (x in xs) { val t = x.trim(); if (t.isNotEmpty() && seen.add(t)) out.add(t) }
        return out
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: ""

    private fun JsonObject.strList(key: String): List<String> {
        val el = this[key] ?: return emptyList()
        runCatching { el.jsonArray }.getOrNull()?.let { arr ->
            return arr.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.filter { it.isNotBlank() }
        }
        return runCatching { listOf(el.jsonPrimitive.content) }.getOrDefault(emptyList()).filter { it.isNotBlank() }
    }
}

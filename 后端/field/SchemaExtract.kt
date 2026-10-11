package com.taowu.backend.field

/**
 * 一列字段定义（建表用 name/type，抽取用 name/clean/synonyms/re）。
 * 由 FieldSchema 把「列 + 引用的 rule」合并后产出。
 */
data class FieldColumn(
    val name: String,
    val type: String = "TEXT",
    val clean: String = "text",
    val synonyms: List<String> = emptyList(),
    val re: List<String> = emptyList(),
)

/** 抽取上下文：回填系统列（job_url/source）与 title 兜底。 */
data class FieldCtx(
    val url: String = "",
    val site: String = "",
    val titles: List<String> = emptyList(),
)

/**
 * 通用字段抽取器 —— 1:1 移植自 PC 版 `schema_engine.py`（纯函数，无 IO）。
 *
 * 两段式：
 *  · Pass A `scanPairs`：把页面文本扫成 {标签:值}，兼容 `【标签】值`（优先）与 `标签：值`。
 *  · Pass B `extract`：逐列取值，优先级固定
 *      `列名 → 同义词 → (clean=title 时 ctx.titles) → 列内 re → clean 形态兜底 → 系统列(job_url/source)`。
 *
 * `clean` 取值：title / money / datetime / phone / link / int / longtext / text。
 * 取不到值 → 空串（落库为空列，不报错）。
 */
object SchemaExtract {

    // ── Pass A 正则 ──
    private val MARKER = Regex("""【([^】\n]{1,40})】\s*([^\n【]*)""")
    private val COLON = Regex(
        """^\s*([^：:\s【】\d][^：:\n【】]{0,24}?)[：:]\s*(.+)$""",
        RegexOption.MULTILINE,
    )

    // ── 形态兜底正则 ──
    private val RE_MONEY = listOf(
        Regex("""[¥￥]\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""),
        Regex("""([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:万元|元|万|人民币|RMB)""", RegexOption.IGNORE_CASE),
    )
    private val RE_DT = Regex("""(20\d{2})\s*[年/\-.]\s*(\d{1,2})\s*[月/\-.]\s*(\d{1,2})(?:日)?(?:\s+(\d{1,2})[:：](\d{1,2}))?""")
    private val RE_PHONE = Regex("""(?:1[3-9]\d{9}|0\d{2,3}[-]?\d{7,8})""")
    private val RE_LINK = Regex("""https?://[^\s　，。；;"'<>（）()]+""")
    private val RE_INT = Regex("""[0-9,]+""")
    private const val TEXT_FALLBACK_LIMIT = 3000

    private val NL3 = Regex("""\n{3,}""")
    private val WS = Regex("""[ \t　]+""")

    // 编译缓存：坏正则记为 null，运行期静默跳过
    private val reCache = HashMap<String, Regex?>()

    /** Pass A：文本 → {标签: 首个非空值}。`【】` 优先于 `：`。 */
    fun scanPairs(text: String): Map<String, String> {
        val pairs = LinkedHashMap<String, String>()
        for (m in MARKER.findAll(text)) {
            val lab = m.groupValues[1].trim()
            val v = m.groupValues[2].trim()
            if (lab.isNotEmpty() && v.isNotEmpty() && !pairs.containsKey(lab)) pairs[lab] = v
        }
        for (m in COLON.findAll(text)) {
            val lab = m.groupValues[1].trim()
            val v = m.groupValues[2].trim()
            val low = lab.lowercase()
            if (lab.isEmpty() || v.isEmpty() || low.startsWith("http") || low.startsWith("www")) continue
            if (pairs.containsKey(lab)) continue
            pairs[lab] = v
        }
        return pairs
    }

    /** Pass B：逐列取值 → {列名: 值}。 */
    fun extract(text: String, columns: List<FieldColumn>, ctx: FieldCtx? = null): Map<String, String> {
        val t = text
        val pairs = if (t.isNotEmpty()) scanPairs(t) else emptyMap()
        val result = LinkedHashMap<String, String>()
        for (c in columns) {
            val name = c.name.trim()
            if (name.isEmpty()) continue
            val clean = c.clean.lowercase()
            val pats = c.re.filter { it.isNotBlank() }
            var v = ""

            // ① 列名 + 同义词
            for (lab in listOf(name) + c.synonyms) {
                val hit = pairs[lab]
                if (!hit.isNullOrBlank()) { v = hit; break }
            }
            // ② title 兜底
            if (v.isEmpty() && clean == "title") {
                for (tt in ctx?.titles ?: emptyList()) {
                    if (tt.isNotBlank()) { v = tt.trim(); break }
                }
            }
            // ③ 列内正则
            if (v.isEmpty() && pats.isNotEmpty()) v = regexFind(t, pats)
            // ④ clean 形态兜底
            if (v.isEmpty()) v = fallback(t, clean)
            // ⑤ 系统列回填
            if (v.isEmpty() && ctx != null) {
                if (name == "job_url") v = ctx.url
                else if (name == "source") v = ctx.site
            }
            result[name] = cleanValue(v, name, clean)
        }
        return result
    }

    // ── 内部 ──────────────────────────────────────────────────────

    private fun rx(p: String): Regex? = reCache.getOrPut(p) {
        try { Regex(p) } catch (e: Exception) { null }
    }

    /** 取第一个命中：有捕获组取第 1 组，无组取整段。 */
    private fun regexFind(text: String, pats: List<String>): String {
        for (p in pats) {
            val r = rx(p) ?: continue
            val m = r.find(text) ?: continue
            val g = if (m.groupValues.size > 1) m.groupValues[1] else m.value
            if (g.isNotEmpty()) return g
        }
        return ""
    }

    private fun one(v: String): String {
        var s = NL3.replace(v, "\n").trim()
        s = WS.replace(s, " ").trim()
        return s
    }

    private fun keepLong(v: String): String {
        var s = NL3.replace(v, "\n\n").trim()
        s = s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return if (s.length > 8000) s.substring(0, 8000) else s
    }

    private fun fmtDt(m: MatchResult): String {
        val y = m.groupValues[1]
        val mo = m.groupValues[2].toIntOrNull()?.toString()?.padStart(2, '0') ?: m.groupValues[2]
        val d = m.groupValues[3].toIntOrNull()?.toString()?.padStart(2, '0') ?: m.groupValues[3]
        var base = "$y-$mo-$d"
        val hh = m.groupValues.getOrNull(4) ?: ""
        if (hh.isNotEmpty()) base += " $hh:${m.groupValues[5]}"
        return base
    }

    /** 标签命中后也要按 clean 类型规范化。 */
    private fun cleanValue(vIn: String, name: String, clean: String): String {
        if (vIn == name) return ""
        if (clean == "longtext") return keepLong(vIn)
        val v = one(vIn)
        if (v.isEmpty()) return v
        return when (clean) {
            "phone" -> RE_PHONE.find(v)?.value ?: v
            "datetime" -> RE_DT.find(v)?.let { fmtDt(it) } ?: v
            "link" -> RE_LINK.find(v)?.value ?: v
            "int" -> RE_INT.find(v)?.value?.replace(",", "") ?: v
            else -> v   // money / text / title 保留原文
        }
    }

    /** clean 形态兜底：标签/title 未命中时按形态从全文泛扫。 */
    private fun fallback(text: String, clean: String): String {
        if (text.isEmpty()) return ""
        val head = if (text.length > TEXT_FALLBACK_LIMIT) text.substring(0, TEXT_FALLBACK_LIMIT) else text
        return when (clean) {
            "money" -> {
                for (r in RE_MONEY) {
                    val m = r.find(head) ?: continue
                    return m.groupValues[1].replace(",", "")
                }
                ""
            }
            "datetime" -> RE_DT.find(head)?.let { fmtDt(it) } ?: ""
            "phone" -> RE_PHONE.find(head)?.value ?: ""
            "link" -> RE_LINK.find(text)?.value ?: ""   // link 用全文
            "int" -> RE_INT.find(head)?.value?.replace(",", "") ?: ""
            else -> ""   // text 无形态兜底
        }
    }
}

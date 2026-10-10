package com.taowu.backend.core

import com.taowu.backend.json.*
import kotlinx.serialization.json.*

// 值解析：模板 / 算术 / 条件选择 / 选优
private val RE_TPL = Regex("""\{([^{}]+)\}""")

fun render(tpl: String?, ns: Any?): String =
    RE_TPL.replace(tpl ?: "") { m -> renderValue(walk(ns, m.groupValues[1])) }

private fun renderValue(v: Any?): String = when {
    v === Miss || v == null -> ""
    v is Boolean -> if (v) "true" else "false"
    v is Double && v == Math.floor(v) && !v.isInfinite() -> v.toLong().toString()
    else -> v.toString()
}

fun renderOne(tpl: String?, ns: Any?): Any? {
    val m = RE_TPL.matchEntire(tpl ?: "")
    if (m != null) {
        val v = walk(ns, m.groupValues[1])
        return if (v === Miss) null else v
    }
    return render(tpl, ns)
}

fun asNum(v: Any?, default: Double = 0.0): Double = when (v) {
    is Boolean -> if (v) 1.0 else 0.0
    is Number -> v.toDouble()
    null -> default
    else -> {
        val s = v.toString().trim()
        if (s.isEmpty()) default else s.toDoubleOrNull() ?: default
    }
}

fun asInt(v: Any?, default: Int = 0): Int = asNum(v, default.toDouble()).toInt()

fun fmtSize(b: Any?): String {
    val n = asInt(b, 0)
    return when {
        n >= 1073741824 -> "%.2fGB".format(n / 1073741824.0)
        n >= 1048576 -> "%.1fMB".format(n / 1048576.0)
        n >= 1024 -> "%.1fKB".format(n / 1024.0)
        else -> if (n != 0) "${n}B" else ""
    }
}

fun extOf(url: String?, whitelist: List<String>?, default: String = ""): String {
    val path = (url ?: "").split("?", limit = 2)[0].split("#", limit = 2)[0].trimEnd('/')
    val lastSeg = path.split("/").last()
    val e = if ("." in lastSeg) path.substringAfterLast(".").lowercase() else ""
    val wl = whitelist.orEmpty().map { it.lowercase().trimStart('.') }
    if (e.isNotEmpty() && (wl.isEmpty() || e in wl)) return e
    return default.ifEmpty { if (e.isNotEmpty() && wl.isEmpty()) e else "" }
}

private data class SortKey(val t: Int, val n: Double, val s: String)

private fun sortValue(item: Any?, rule: JsonObject): SortKey {
    if (rule.containsKey("prefer_re")) {
        val hit = try {
            Regex(rule.strField("prefer_re")).containsMatchIn((get(item, rule.strField("path"), "") ?: "").toString())
        } catch (e: Exception) {
            false
        }
        return SortKey(1, if (hit) 1.0 else 0.0, "")
    }
    if (rule.containsKey("mul") || rule.containsKey("add")) {
        val arr = (rule["mul"] ?: rule["add"]).asArr()
        var acc = 1.0
        for (a in arr ?: JsonArray(emptyList())) acc *= asNum(get(item, a.primContent(), 0))
        return SortKey(1, acc, "")
    }
    val v = get(item, rule.strField("path"), null)
    return when {
        v == null -> SortKey(0, 0.0, "")
        v is Boolean -> SortKey(1, if (v) 1.0 else 0.0, "")
        v is Number -> SortKey(1, v.toDouble(), "")
        v is List<*> -> SortKey(2, 0.0, v.size.toString())
        else -> SortKey(2, 0.0, v.toString())
    }
}

private fun cmp2(a: Int, b: Int) = if (a > b) 1 else if (a < b) -1 else 0
private fun cmp2(a: Double, b: Double) = if (a > b) 1 else if (a < b) -1 else 0
private fun cmp2(a: String, b: String) = a.compareTo(b).let { if (it > 0) 1 else if (it < 0) -1 else 0 }

fun sortItems(items: List<Any?>?, rules: JsonArray?): List<Any?> {
    val list = items ?: return emptyList()
    if (list.isEmpty() || rules == null || rules.isEmpty()) return list.toList()
    val ruleObjs = rules.mapNotNull { it as? JsonObject }
    return list.sortedWith { x, y ->
        for (r in ruleObjs) {
            val va = sortValue(x, r)
            val vb = sortValue(y, r)
            var c = cmp2(va.t, vb.t)
            if (c == 0) c = cmp2(va.n, vb.n)
            if (c == 0) c = cmp2(va.s, vb.s)
            if (r.strField("order", "desc") == "desc") c = -c
            if (c != 0) return@sortedWith c
        }
        0
    }
}

fun whereItems(items: List<Any?>?, rules: JsonElement?, ns: Any?): List<Any?> {
    val list = items ?: return emptyList()
    if (rules == null || rules is JsonNull) return list.toList()
    return list.filter { x -> cond(rules, x as? Map<String, Any?> ?: emptyMap<String, Any?>(), ns) }
}

private fun isRuleList(lst: JsonArray): Boolean {
    if (lst.isEmpty()) return false
    for (x in lst) {
        if (x !is JsonObject || (!x.containsKey("value") && !x.containsKey("when"))) return false
    }
    return true
}

private fun isEmptyish(v: Any?): Boolean = when {
    v == null || v == "" || v == false -> true
    v is Number -> v.toDouble() == 0.0
    v is List<*> -> v.isEmpty()
    v is Map<*, *> -> v.isEmpty()
    else -> false
}

/** 统一取值入口：字符串模板 / 规则表 / 各类 op 字典。 */
fun resolve(sel: JsonElement?, ns: Any?): Any? {
    when (sel) {
        null, is JsonNull -> return null
        is JsonPrimitive -> {
            if (sel.isString) return renderOne(sel.content, ns)
            return sel.toNative()
        }
        is JsonArray -> {
            if (isRuleList(sel)) {
                for (r in sel) {
                    if (r is JsonObject && cond(r["when"], ns)) return resolve(r["value"], ns)
                }
                return null
            }
            return sel.map { resolve(it, ns) }
        }
        is JsonObject -> {}
    }
    val sel2 = sel as JsonObject

    if (sel2.containsKey("rules")) return resolve(sel2["rules"], ns)
    if (sel2.containsKey("const")) return sel2["const"].toNative()
    if (sel2.containsKey("template")) return render(sel2.strField("template"), ns)
    if (sel2.containsKey("path") && sel2.size <= 2) {
        val v = walk(ns, sel2.strField("path"))
        if (v === Miss) return if (sel2.containsKey("default")) resolve(sel2["default"], ns) else null
        if (v == null && sel2.containsKey("default")) return resolve(sel2["default"], ns)
        return v
    }
    if (sel2.containsKey("pick")) return pick(sel2["pick"].asObj(), ns)
    if (sel2.containsKey("len")) {
        val v = resolve(sel2["len"], ns)
        return try {
            (v as? Collection<*>)?.size ?: (v as? Map<*, *>)?.size ?: (v as? String)?.length ?: 0
        } catch (e: Exception) {
            0
        }
    }
    if (sel2.containsKey("bool")) return cond(sel2["bool"], ns)
    if (sel2.containsKey("not") && !sel2.containsKey("path")) return !truthy(resolve(sel2["not"], ns))
    if (sel2.containsKey("any") && !sel2.containsKey("path")) {
        val vals = (sel2["any"].asArr() ?: JsonArray(emptyList())).map { resolve(it, ns) }
        return vals.firstOrNull { !isEmptyish(it) }
    }
    if (sel2.containsKey("match") && !sel2.containsKey("path")) {
        val c = sel2["match"].asObj() ?: JsonObject(emptyMap())
        val pv = c["path"]
        val txt: Any? = if (pv is JsonObject) resolve(pv, ns)
        else if (pv != null && jsonTruthy(pv)) walk(ns, pv.primContent()) else ""
        val txtStr = if (txt === Miss || txt == null) "" else txt.toString()
        val m = try {
            Regex(c.strField("pattern")).find(txtStr)
        } catch (e: Exception) {
            null
        }
        if (m == null) return if (c.containsKey("default")) resolve(c["default"], ns) else ""
        val gi = asInt(c["group"].toNative(), 1)
        val grp = if (gi in 0..m.groups.size - 1) m.groups[gi] else null
        return grp?.value ?: m.value
    }
    if (sel2.containsKey("size")) return fmtSize(resolve(sel2["size"], ns))
    if (sel2.containsKey("ext_of")) {
        val c = sel2["ext_of"].asObj() ?: JsonObject(emptyMap())
        val wl = c["whitelist"].asArr()?.mapNotNull { it.strOrNull() }
        return extOf(resolve(c["url"], ns)?.toString() ?: "", wl, c.strField("default"))
    }
    if (sel2.containsKey("int") || sel2.containsKey("float") || sel2.containsKey("str")) {
        val key = if (sel2.containsKey("int")) "int" else if (sel2.containsKey("float")) "float" else "str"
        val v = resolve(sel2[key], ns)
        return when (key) {
            "int" -> asInt(v, 0)
            "float" -> asNum(v, 0.0)
            else -> if (v == null) "" else v.toString()
        }
    }
    if (sel2.containsKey("div") || sel2.containsKey("mul") || sel2.containsKey("add") || sel2.containsKey("sub")) {
        for (op in listOf("div", "mul", "add", "sub")) {
            if (!sel2.containsKey(op)) continue
            val args = sel2[op].asArr() ?: JsonArray(emptyList())
            val a = asNum(if (args.size > 0) resolve(args[0], ns) else 0)
            val b = asNum(if (args.size > 1) resolve(args[1], ns) else 0)
            return when (op) {
                "div" -> if (b != 0.0) a / b else 0.0
                "mul" -> a * b
                "add" -> a + b
                else -> a - b
            }
        }
    }
    if (sel2.containsKey("min") || sel2.containsKey("max")) {
        val src = sel2["min"] ?: sel2["max"]
        val vals = (src.asArr() ?: JsonArray(emptyList())).map { asNum(resolve(it, ns)) }
        if (vals.isEmpty()) return 0.0
        return if (sel2.containsKey("min")) vals.min() else vals.max()
    }
    if (sel2.containsKey("join")) {
        val sep = sel2.strField("sep")
        val parts = (sel2["join"].asArr() ?: JsonArray(emptyList())).map { resolve(it, ns) }
        return parts.filter { it != null && it != "" }.joinToString(sep) { renderValue(it) }
    }
    if (sel2.containsKey("list")) {
        return (sel2["list"].asArr() ?: JsonArray(emptyList())).map { resolve(it, ns) }
    }
    val out = LinkedHashMap<String, Any?>()
    for ((k, v) in sel2) {
        if (k.startsWith("_")) continue
        val rv = resolve(v, ns)
        if (rv == "") continue
        out[k] = rv
    }
    return out
}

fun pick(cfg: JsonObject?, ns: Any?): Any? {
    val c = cfg ?: JsonObject(emptyMap())
    val listSel: JsonElement? = if (c.containsKey("list")) c["list"] else JsonObject(mapOf("path" to (c["path"] ?: JsonNull)))
    val items = resolve(listSel, ns)
    if (items !is List<*>) return null
    var list: List<Any?> = whereItems(items, c["where"], ns)
    val need = c["require_len"]
    if (need != null && need !is JsonNull && list.size < asInt(need.toNative(), 0)) return null
    list = sortItems(list, c["sort"].asArr())
    val take = asInt(c["take"].toNative(), 0)
    if (take < 0 || take >= list.size) return null
    return list[take]
}

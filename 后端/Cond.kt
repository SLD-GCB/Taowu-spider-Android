package com.taowu.backend.core

import com.taowu.backend.json.*
import kotlinx.serialization.json.*

// 条件（微表达式：只有比较，没有脚本）
val CMP_OPS = listOf("eq", "ne", "gt", "ge", "lt", "le", "in", "nin",
    "contains", "contains_i", "re", "startswith", "endswith", "any_re", "all_re", "any_eq", "all_eq")
val FLAG_OPS = listOf("exists", "nonempty", "empty", "truthy", "falsy")
val LEN_OPS = linkedMapOf("len_eq" to "eq", "len_ne" to "ne", "len_gt" to "gt",
    "len_ge" to "ge", "len_lt" to "lt", "len_le" to "le")
val ALL_OPS: List<String> = CMP_OPS + FLAG_OPS + LEN_OPS.keys.toList()

fun truthy(v: Any?): Boolean = when {
    v == null || v == false -> false
    v is String -> v.isNotEmpty()
    v is List<*> -> v.isNotEmpty()
    v is Map<*, *> -> v.isNotEmpty()
    v is Number -> v.toDouble() != 0.0
    else -> true
}

private fun pyContains(container: Any?, item: Any?): Boolean = when (container) {
    is List<*> -> container.contains(item)
    is String -> (item as? String)?.let { container.contains(it) } ?: false
    is Map<*, *> -> container.containsKey(item)
    else -> false
}

fun oneCond(c: JsonElement?, ns: Any?): Boolean {
    if (c !is JsonObject) return jsonTruthy(c)
    if (c.containsKey("not")) return !cond(c["not"], ns)
    if (c.containsKey("any")) return (c["any"].asArr() ?: JsonArray(emptyList())).any { oneCond(it, ns) }
    if (c.containsKey("all")) return (c["all"].asArr() ?: JsonArray(emptyList())).all { oneCond(it, ns) }

    val op = (c.strField("op").ifEmpty { if (!c.containsKey("value")) "exists" else "eq" }).lowercase()
    val raw = if (c.truthyField("path")) walk(ns, c.strField("path")) else Miss
    val v: Any? = if (raw === Miss) null else raw

    when (op) {
        "exists" -> return raw !== Miss
        "nonempty", "truthy" -> return truthy(v)
        "empty", "falsy" -> return !truthy(v)
    }
    if (op in LEN_OPS) {
        val n = when (v) {
            is String -> v.length
            is List<*> -> v.size
            is Map<*, *> -> v.size
            else -> return false
        }
        return compareNum(n.toDouble(), LEN_OPS[op]!!, asNum(resolve(c["value"], ns)))
    }
    if (op in listOf("gt", "ge", "lt", "le")) {
        return compareNum(asNum(v), op, asNum(resolve(c["value"], ns)))
    }
    val want = resolve(c["value"], ns)
    if (op == "eq" || op == "ne") {
        var r = (v == want)
        if (!r && (v is Number || v is String) && (want is Number || want is String)) {
            val fv = asStrNum(v); val fw = asStrNum(want)
            r = fv != null && fw != null && fv == fw
        }
        return if (op == "eq") r else !r
    }
    if (op == "in" || op == "nin") {
        val container = if (truthy(want)) want else emptyList<Any?>()
        val r = pyContains(container, v)
        return if (op == "in") r else !r
    }
    if (op == "contains") return pyContains(v, want)
    if (op == "contains_i") return (want?.toString() ?: "").lowercase() in (v?.toString() ?: "").lowercase()
    if (op == "re") {
        return try {
            Regex(want?.toString() ?: "").containsMatchIn(v?.toString() ?: "")
        } catch (e: Exception) {
            false
        }
    }
    if (op == "any_re" || op == "all_re") {
        val vals = listify(v).filterNotNull()
        if (vals.isEmpty()) return false
        val hits = try {
            vals.map { Regex(want?.toString() ?: "").containsMatchIn(it.toString()) }
        } catch (e: Exception) {
            return false
        }
        return if (op == "any_re") hits.any { it } else hits.all { it }
    }
    if (op == "any_eq" || op == "all_eq") {
        val vals = listify(v).filterNotNull()
        if (vals.isEmpty()) return false
        val hits = vals.map { x ->
            x == want || run {
                val a = asStrNum(x); val b = asStrNum(want)
                a != null && b != null && a == b
            }
        }
        return if (op == "any_eq") hits.any { it } else hits.all { it }
    }
    if (op == "startswith") return (v?.toString() ?: "").startsWith(want?.toString() ?: "")
    if (op == "endswith") return (v?.toString() ?: "").endsWith(want?.toString() ?: "")
    return false
}

private fun asStrNum(v: Any?): Double? = when (v) {
    is Boolean -> null
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull()
    else -> null
}

private fun listify(v: Any?): List<Any?> = when {
    v is List<*> -> v.toList()
    v == null -> emptyList()
    else -> listOf(v)
}

fun compareNum(a: Double, op: String, b: Double): Boolean = when (op) {
    "gt" -> a > b
    "ge" -> a >= b
    "lt" -> a < b
    "le" -> a <= b
    "eq" -> a == b
    "ne" -> a != b
    else -> false
}

/** 条件列表（AND）。空/None → True。base：外层命名空间，ns 覆盖 base。 */
fun cond(conds: JsonElement?, ns: Any?, base: Any? = null): Boolean {
    if (conds == null || conds is JsonNull) return true
    if (!jsonTruthy(conds)) return true
    var n = ns
    if (base != null && truthy(base)) {
        val merged = LinkedHashMap<String, Any?>()
        (base as? Map<String, Any?>)?.let { merged.putAll(it) }
        (ns as? Map<String, Any?>)?.let { merged.putAll(it) }
        n = merged
    }
    val seq: List<JsonElement> = if (conds is JsonArray) conds.toList() else listOf(conds)
    return seq.all { oneCond(it, n) }
}

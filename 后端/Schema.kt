package com.taowu.backend.schema

import com.taowu.backend.core.ALL_OPS
import com.taowu.backend.core.KIND_ALLOWED
import com.taowu.backend.core.KIND_CONTRACT
import com.taowu.backend.core.asInt
import com.taowu.backend.core.normKind
import com.taowu.backend.json.asArr
import com.taowu.backend.json.asObj
import com.taowu.backend.json.strOrNull
import kotlinx.serialization.json.*

// 校验（白名单：未知键一律报错 —— 红线在 schema 上的落点）
const val SCHEMA_VERSION = 1
const val MAX_SPEC_BYTES = 512 * 1024
const val MAX_EMIT_DEPTH = 4
const val MAX_TRACKS_PER_RULE = 500

private val TOP_KEYS = setOf("schema_version", "name", "desc", "enabled", "order", "host_suffix",
    "short_hosts", "cookies", "session", "identity", "steps", "emit", "browser_seam", "episode_link")
private val EPISODE_LINK_KEYS = setOf("re", "line", "ep")
private val SESSION_KEYS = setOf("user_agent", "referer", "headers", "bootstrap")
private val BOOT_KEYS = setOf("url", "method", "timeout", "cookies_from", "cookie_domain", "headers", "expect")
private val IDENT_KEYS = setOf("any_of", "all_of", "query")
private val IDENT_RULE_KEYS = setOf("name", "from", "pattern", "type")
private val IDENT_QUERY_KEYS = setOf("name", "param", "type", "default", "min", "max")
private val STEP_KEYS = setOf("name", "method", "url", "alt_url", "referer", "alt_referer", "headers",
    "cookies", "timeout", "as", "when", "expect", "ladder", "required", "json", "body", "desc", "parse")
private val LADDER_KEYS = setOf("var", "values")
private val PARSE_KEYS = setOf("from", "re", "json", "index", "unescape")
private val EMIT_KEYS = setOf("name", "when", "loop", "set", "steps", "produce", "track", "desc")
private val LOOP_KEYS = setOf("path", "as", "index_as", "episode_param", "max", "fallback", "pause")
private val PRODUCE_KEYS = setOf("name", "when", "set", "track", "desc", "stop")
private val TRACK_KEYS = setOf("media_class", "kind", "url", "ext", "title_hint", "duration_s",
    "note", "cookies", "ephemeral", "parts", "drm")
private val PART_KEYS = setOf("media_class", "kind", "url", "ext", "note")
private val SEAM_KEYS = setOf("name", "url", "watch", "wait_sec", "emit", "identity", "enabled")
private val WATCH_KEYS = setOf("path_contains", "url_contains", "json_require", "validate", "as", "max_bytes")

private val FORBIDDEN_KEYS = setOf(
    "eval", "exec", "script", "js", "javascript", "code", "python", "lambda", "expr",
    "sign", "signature", "sign_url", "a_bogus", "abogus", "wbi", "nsig", "n_sig",
    "decrypt", "decode", "decipher", "key", "keys", "license", "widevine", "playready",
    "fairplay", "clearkey", "captcha", "verify_code", "token_gen", "potoken", "botguard")

private val COND_KEYS = setOf("expect", "when", "json_require", "validate", "any", "all", "not")

private fun badKeys(d: JsonElement?, allowed: Set<String>, where: String, errs: MutableList<String>) {
    if (d !is JsonObject) {
        errs.add("$where 必须是对象")
        return
    }
    for (k in d.keys) {
        if (k.startsWith("_")) continue
        if (k in FORBIDDEN_KEYS) {
            errs.add("$where 含被禁键 '$k' —— 配置不支持签名/解密/验证码/脚本一类能力（红线）")
        } else if (k !in allowed) {
            errs.add("$where 含未知键 '$k'（允许：${allowed.sorted().joinToString("/")}）")
        }
    }
}

private fun deepScanForbidden(node: JsonElement?, where: String, errs: MutableList<String>, depth: Int = 0) {
    if (depth > 8) return
    when (node) {
        is JsonObject -> for ((k, v) in node) {
            if (!k.startsWith("_") && k in FORBIDDEN_KEYS) {
                errs.add("$where 出现被禁键 '$k'（红线：配置不支持签名/解密/验证码/脚本）")
            }
            deepScanForbidden(v, "$where.$k", errs, depth + 1)
        }
        is JsonArray -> node.take(64).forEachIndexed { i, v -> deepScanForbidden(v, "$where[$i]", errs, depth + 1) }
        else -> {}
    }
}

private fun checkCondTree(node: JsonElement?, where: String, errs: MutableList<String>, depth: Int = 0) {
    if (depth > 8 || errs.size > 40) return
    if (node is JsonArray) {
        node.take(64).forEachIndexed { i, x -> checkCondTree(x, "$where[$i]", errs, depth + 1) }
        return
    }
    if (node !is JsonObject) return
    val op = (node["op"] as? JsonPrimitive)?.strOrNull()
    if (op != null && op.lowercase() !in ALL_OPS) {
        errs.add("$where 用了不认识的算子 op='$op'（可用：${ALL_OPS.joinToString("/")}）")
    }
    for (k in listOf("any", "all", "not")) {
        if (node.containsKey(k)) checkCondTree(node[k], "$where.$k", errs, depth + 1)
    }
}

private fun deepCheckConds(node: JsonElement?, where: String, errs: MutableList<String>, depth: Int = 0) {
    if (depth > 8) return
    when (node) {
        is JsonObject -> for ((k, v) in node) {
            if (k in COND_KEYS) checkCondTree(v, "$where.$k", errs)
            else deepCheckConds(v, "$where.$k", errs, depth + 1)
        }
        is JsonArray -> node.take(64).forEachIndexed { i, v -> deepCheckConds(v, "$where[$i]", errs, depth + 1) }
        else -> {}
    }
}

private fun validateIdentity(ident: JsonObject): List<String> {
    val errs = ArrayList<String>()
    for (key in listOf("any_of", "all_of")) {
        val groupsEl = ident[key] ?: continue
        val groups: List<JsonElement> = when (groupsEl) {
            is JsonObject -> listOf(groupsEl)
            is JsonArray -> groupsEl.toList()
            else -> emptyList()
        }
        groups.forEachIndexed { gi, grp ->
            val rules = if (grp is JsonArray) grp.toList() else listOf(grp)
            rules.forEachIndexed { ri, r ->
                val w = if (grp !is JsonArray) "identity.$key[$gi]" else "identity.$key[$gi][$ri]"
                badKeys(r, IDENT_RULE_KEYS, w, errs)
                if (r !is JsonObject) return@forEachIndexed
                if ((r["name"]?.strOrNull() ?: "").isBlank()) {
                    errs.add("$w 缺少 name —— 捕获组要存进哪个变量（缺了这条规则永远不命中）")
                }
                val pat = r["pattern"]?.strOrNull()
                if (pat.isNullOrBlank()) {
                    errs.add("$w 缺少 pattern —— 用来抓取的正则")
                } else {
                    try {
                        Regex(pat)
                    } catch (e: Exception) {
                        errs.add("$w 的 pattern 不是合法正则：${e.message}")
                    }
                }
            }
        }
    }
    (ident["query"].asArr() ?: JsonArray(emptyList())).forEachIndexed { qi, q ->
        val w = "identity.query[$qi]"
        badKeys(q, IDENT_QUERY_KEYS, w, errs)
        if (q is JsonObject && !(q.containsKey("name") || q.containsKey("param"))) {
            errs.add("$w 需要 name 或 param（从查询串取哪个参数、存成什么变量）")
        }
    }
    return errs
}

/** 校验配方 → 错误列表（空 = 合法）。绝不抛。 */
fun validateSpec(spec: JsonElement?): List<String> {
    val errs = ArrayList<String>()
    try {
        if (spec !is JsonObject) return listOf("配方根必须是 JSON 对象")
        badKeys(spec, TOP_KEYS, "配方", errs)
        if ((spec["name"]?.strOrNull() ?: "").isBlank()) errs.add("缺少 name（配方名）")
        val hsEl = spec["host_suffix"]
        val hs: List<String?> = when (hsEl) {
            is JsonPrimitive -> listOf(hsEl.strOrNull())
            is JsonArray -> hsEl.map { it.strOrNull() }
            else -> emptyList()
        }
        if (hs.isEmpty() || hs.any { it.isNullOrBlank() }) {
            errs.add("host_suffix 必须是非空字符串或字符串数组")
        }
        val sv = asInt(spec["schema_version"]?.let { if (it is JsonPrimitive) it.content else null } ?: SCHEMA_VERSION, SCHEMA_VERSION)
        if (sv > SCHEMA_VERSION) errs.add("schema_version=$sv 高于本引擎支持的 $SCHEMA_VERSION，请升级程序")
        if ((spec["cookies"]?.strOrNull() ?: "jar").lowercase() !in setOf("none", "session", "jar", "session+jar")) {
            errs.add("cookies 只能是 none/session/jar/session+jar")
        }

        spec["session"]?.let { sess ->
            badKeys(sess, SESSION_KEYS, "session", errs)
            if (sess is JsonObject && sess.containsKey("bootstrap")) {
                badKeys(sess["bootstrap"], BOOT_KEYS, "session.bootstrap", errs)
            }
        }

        spec["identity"]?.let { ident ->
            badKeys(ident, IDENT_KEYS, "identity", errs)
            if (ident is JsonObject) {
                if (!ident.containsKey("any_of") && !ident.containsKey("all_of")) {
                    errs.add("identity 至少要有 any_of 或 all_of")
                }
                errs.addAll(validateIdentity(ident))
            }
        }

        (spec["steps"].asArr() ?: JsonArray(emptyList())).forEachIndexed { i, st ->
            val w = "steps[$i]"
            badKeys(st, STEP_KEYS, w, errs)
            if (st is JsonObject) {
                if (!st.containsKey("url")) errs.add("$w 缺少 url")
                st["ladder"]?.let { ladder ->
                    badKeys(ladder, LADDER_KEYS, "$w.ladder", errs)
                    val lad = ladder as? JsonObject
                    if (lad == null || !lad.containsKey("var") || lad["values"] !is JsonArray) {
                        errs.add("$w.ladder 需要 var 与 values[]")
                    }
                }
                st["parse"]?.let { parse ->
                    badKeys(parse, PARSE_KEYS, "$w.parse", errs)
                    val ps = parse as? JsonObject
                    if (ps == null || !(ps["re"]?.strOrNull()?.isNotEmpty() ?: false)) {
                        errs.add("$w.parse 缺少 re（从响应文本里抽内联 JSON 的正则，建议带一个捕获组）")
                    }
                    if (ps != null && ps.containsKey("unescape")) {
                        val u = ps["unescape"]
                        if (u !is JsonPrimitive || u.booleanOrNull == null) {
                            errs.add("$w.parse.unescape 必须是 true / false（要不要做 HTML 实体解码）")
                        }
                    }
                    if ((st["method"]?.strOrNull() ?: "GET").uppercase() == "POST") {
                        errs.add("$w.parse 只支持 GET（parse 要的是响应原文，POST 目前没有 raw 通道）")
                    }
                }
                for (k in listOf("alt_url", "alt_referer")) {
                    val v = st[k] ?: continue
                    if (v is JsonPrimitive && v.isString) continue
                    if (v !is JsonArray || v.any { !(it is JsonPrimitive && it.isString) }) {
                        errs.add("$w.$k 必须是字符串或字符串数组")
                    }
                }
            }
        }

        val emit = spec["emit"].asArr() ?: JsonArray(emptyList())
        if (emit.isEmpty() && !spec.containsKey("browser_seam") && !spec.containsKey("episode_link")) {
            errs.add("至少要有一条 emit（或 browser_seam / episode_link）")
        }
        emit.forEachIndexed { i, e -> errs.addAll(validateEmit(e, "emit[$i]")) }

        // 站点自报的"分集链接形状"：只给通用模式当规则用，不产出轨道。
        spec["episode_link"]?.let { el ->
            badKeys(el, EPISODE_LINK_KEYS, "episode_link", errs)
            if (el !is JsonObject) {
                errs.add("episode_link 必须是对象")
            } else {
                val re = el["re"]?.strOrNull()
                if (re.isNullOrBlank()) {
                    errs.add("episode_link 缺少 re（匹配分集锚点路径的正则，去掉首斜杠后匹配）")
                } else {
                    try {
                        Regex(re)
                    } catch (e: Exception) {
                        errs.add("episode_link.re 不是合法正则：${e.message}")
                    }
                }
                for (k in listOf("line", "ep")) {
                    val v = el[k] ?: continue
                    val n = if (v is JsonPrimitive) v.intOrNull else null
                    if (n == null || n !in 1..9) errs.add("episode_link.$k 必须是 1..9 的捕获组号")
                }
            }
        }

        spec["browser_seam"]?.let { seam ->
            badKeys(seam, SEAM_KEYS, "browser_seam", errs)
            if (seam is JsonObject) {
                if (!seam.containsKey("url")) errs.add("browser_seam 缺少 url")
                badKeys(seam["watch"], WATCH_KEYS, "browser_seam.watch", errs)
                (seam["emit"].asArr() ?: JsonArray(emptyList())).forEachIndexed { i, e ->
                    errs.addAll(validateEmit(e, "browser_seam.emit[$i]"))
                }
            }
        }

        deepScanForbidden(spec, "配方", errs)
        deepCheckConds(spec, "配方", errs)
    } catch (e: Exception) {
        errs.add("校验异常：${e.message}")
    }
    return errs
}

private fun checkKind(tr: JsonObject?, where: String, errs: MutableList<String>) {
    if (tr == null) return
    val nodes = ArrayList<Pair<String, JsonObject>>()
    nodes.add(where to tr)
    (tr["parts"].asArr() ?: JsonArray(emptyList())).forEachIndexed { j, pt ->
        (pt as? JsonObject)?.let { nodes.add("$where.parts[$j]" to it) }
    }
    for ((w, node) in nodes) {
        if (!node.containsKey("kind")) continue
        val k = node["kind"]
        if (k is JsonNull) continue
        val raw = if (k is JsonPrimitive) k.content else null
        if (normKind(raw) !in KIND_CONTRACT) {
            errs.add("$w.kind 写的是 '$k'，引擎不认识（可用：${KIND_ALLOWED.joinToString(" / ")}；留空 = direct）")
        }
    }
}

private fun validateEmit(e: JsonElement?, where: String): List<String> {
    val errs = ArrayList<String>()
    badKeys(e, EMIT_KEYS, where, errs)
    if (e !is JsonObject) return errs
    if (!e.containsKey("track") && !e.containsKey("produce")) errs.add("$where 需要 track 或 produce")
    e["loop"]?.let { loop ->
        badKeys(loop, LOOP_KEYS, "$where.loop", errs)
        if (loop is JsonObject && !loop.containsKey("path")) errs.add("$where.loop 缺少 path")
        val lp = (loop as? JsonObject)?.get("pause")
        if (lp != null && lp !is JsonNull) {
            val ok = lp is JsonPrimitive && !lp.isString && (lp.doubleOrNull != null)
            if (!ok) errs.add("$where.loop.pause 必须是数字（每项之间等的秒数；不写=不等）")
        }
    }
    (e["steps"].asArr() ?: JsonArray(emptyList())).forEachIndexed { i, st ->
        badKeys(st, STEP_KEYS, "$where.steps[$i]", errs)
        if (st is JsonObject && !st.containsKey("url")) errs.add("$where.steps[$i] 缺少 url")
    }
    (e["produce"].asArr() ?: JsonArray(emptyList())).forEachIndexed { i, p ->
        val w = "$where.produce[$i]"
        badKeys(p, PRODUCE_KEYS, w, errs)
        if (p is JsonObject && !p.containsKey("track")) errs.add("$w 需要 track")
        if (p is JsonObject) {
            val tr = p["track"]
            if (tr is JsonObject) {
                badKeys(tr, TRACK_KEYS, "$w.track", errs)
                checkKind(tr, "$w.track", errs)
            }
        }
    }
    val et = e["track"]
    if (et is JsonObject) {
        badKeys(et, TRACK_KEYS, "$where.track", errs)
        checkKind(et, "$where.track", errs)
        (et["parts"].asArr() ?: JsonArray(emptyList())).forEachIndexed { j, pt ->
            badKeys(pt, PART_KEYS, "$where.track.parts[$j]", errs)
        }
    }
    return errs
}

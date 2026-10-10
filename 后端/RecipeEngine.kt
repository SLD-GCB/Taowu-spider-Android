package com.taowu.backend.run

import com.taowu.backend.core.*
import com.taowu.backend.json.*
import com.taowu.backend.model.Part
import com.taowu.backend.model.Track
import com.taowu.backend.net.NetEngine
import com.taowu.backend.schema.MAX_EMIT_DEPTH
import com.taowu.backend.schema.MAX_TRACKS_PER_RULE
import com.taowu.backend.schema.validateSpec
import kotlinx.serialization.json.*

/** 注入式 GET 钩子（离线 canned 测试用）。 */
typealias GetHook = (url: String, cookies: Map<String, String>?, referer: String?) -> Any?
/** 注入式 POST 钩子。 */
typealias PostHook = (url: String, payload: Any?, headers: Map<String, String>) -> Any?

/**
 * 配方引擎：会话 / 步骤执行 / 轨道产出 / identity / 主入口。
 * 逐函数移植自 Python media_recipe.py。
 */
class RecipeEngine(
    private val net: NetEngine,
    private val log: ((String) -> Unit)? = null,
) {

    private class SessionState {
        var cfg: JsonObject? = null
        val defaultHeaders = LinkedHashMap<String, String>()
        val cookies = LinkedHashMap<String, String>()
        var built = false
    }

    private fun logI(msg: String) {
        try {
            log?.invoke(msg)
        } catch (_: Exception) {
        }
    }

    // ── HTTP 取数 ────────────────────────────────────────────────
    private fun buildSession(cfg: JsonObject?): SessionState {
        val s = SessionState()
        s.cfg = cfg
        if (cfg != null) {
            cfg.strField("user_agent").takeIf { it.isNotEmpty() }?.let { s.defaultHeaders["User-Agent"] = it }
            cfg.strField("referer").takeIf { it.isNotEmpty() }?.let { s.defaultHeaders["Referer"] = it }
            cfg["headers"].asObj()?.let { h ->
                for ((k, v) in h) (v as? JsonPrimitive)?.content?.let { s.defaultHeaders[k] = it }
            }
        }
        return s
    }

    private fun ensureSession(s: SessionState) {
        if (s.built) return
        s.built = true
        val boot = s.cfg?.get("bootstrap").asObj() ?: return
        val bootUrl = boot.strField("url")
        if (bootUrl.isEmpty()) return
        try {
            val method = boot.strField("method").ifEmpty { "GET" }.uppercase()
            val timeout = asInt(boot["timeout"].toNative(), 8)
            val text = if (method == "POST") net.postJsonRaw(bootUrl, "null", emptyMap(), emptyMap(), timeout)
            else net.getRaw(bootUrl, emptyMap(), emptyMap(), timeout)
            val j = text?.let { try { Json.parseToJsonElement(it).toNative() } catch (e: Exception) { null } }
            var data: Any? = j
            val from = boot.strField("cookies_from")
            if (from.isNotEmpty()) {
                val w = walk(j, from)
                data = if (w === Miss) emptyMap<String, Any?>() else w
            }
            if (data is Map<*, *>) {
                for ((k, v) in data) if (v is String) s.cookies[k.toString()] = v
            }
        } catch (e: Exception) {
            logI("会话引导失败（不影响主流程）：${e.message}")
        }
    }

    /** 共享 cookie jar —— Android 无桌面浏览器 jar，恒空。 */
    private fun jarCookies(url: String): Map<String, String> = emptyMap()

    private fun resolveCookies(mode: String?, url: String, session: SessionState): Map<String, String> {
        val m = (mode ?: "jar").lowercase()
        val out = LinkedHashMap<String, String>()
        if (m == "session" || m == "session+jar") {
            try {
                ensureSession(session)
                out.putAll(session.cookies)
            } catch (_: Exception) {
            }
        }
        if (m == "jar" || m == "session+jar") out.putAll(jarCookies(url))
        return out
    }

    private fun cookiesFor(mode: JsonElement?, ns: Any?, url: String, session: SessionState): Map<String, String> {
        if (mode == null || mode is JsonNull) {
            val c = (ns as? Map<*, *>)?.get("cookies")
            return if (c is Map<*, *>) c.entries.associate { it.key.toString() to (it.value?.toString() ?: "") }
            else emptyMap()
        }
        if (mode is JsonObject) {
            return mode.entries.associate { (k, v) -> k to (resolve(v, ns)?.toString() ?: "") }
        }
        return resolveCookies((mode as? JsonPrimitive)?.content, url, session)
    }

    private fun httpGet(
        url: String, stepHeaders: Map<String, String>, cookies: Map<String, String>,
        referer: String?, timeout: Int, session: SessionState, transport: GetHook?, raw: Boolean,
    ): Any? {
        val j: Any? = if (transport != null) {
            transport(url, cookies.ifEmpty { null }, referer)
        } else {
            val merged = LinkedHashMap<String, String>()
            merged.putAll(session.defaultHeaders)
            merged.putAll(stepHeaders)
            if (!referer.isNullOrEmpty()) merged["Referer"] = referer
            ensureSession(session)
            net.getRaw(url, merged, cookies, timeout)
        }
        if (raw) return if (j is String) j else null
        var k = j
        if (k is String) {
            k = try {
                Json.parseToJsonElement(k).toNative()
            } catch (e: Exception) {
                return null
            }
        }
        return k as? Map<*, *>
    }

    private fun httpPost(
        url: String, stepHeaders: Map<String, String>, cookies: Map<String, String>,
        timeout: Int, jsonBody: Any?, session: SessionState, transport: PostHook?,
    ): Any? {
        val j: Any? = if (transport != null) {
            transport(url, jsonBody, stepHeaders)
        } else {
            val merged = LinkedHashMap<String, String>()
            merged.putAll(session.defaultHeaders)
            merged.putAll(stepHeaders)
            ensureSession(session)
            net.postJsonRaw(url, jsonBody?.toJsonElement()?.toString(), merged, cookies, timeout)
        }
        var k = j
        if (k is String) {
            k = try {
                Json.parseToJsonElement(k).toNative()
            } catch (e: Exception) {
                return null
            }
        }
        return k as? Map<*, *>
    }

    // ── HTML 实体解码 ────────────────────────────────────────────
    private val safeEntity = Regex(
        "&(amp|lt|gt|quot|apos|nbsp)(;|(?![A-Za-z0-9]))|&#(\\d+);?|&#[xX]([0-9a-fA-F]+);?"
    )
    private val namedEnt = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00a0"
    )

    /** 只解 XML/数字字符引用与 6 个 HTML4 无分号实体。**绝不解 `&times`**。 */
    fun safeUnescape(s: String): String = safeEntity.replace(s) { m ->
        val g1 = m.groupValues[1]
        if (g1.isNotEmpty()) return@replace namedEnt[g1] ?: m.value
        val g3 = m.groupValues[3]
        if (g3.isNotEmpty()) {
            return@replace try { cpToString(g3.toInt(10)) } catch (e: Exception) { m.value }
        }
        val g4 = m.groupValues[4]
        try {
            cpToString(g4.toInt(16))
        } catch (e: Exception) {
            m.value
        }
    }

    private fun cpToString(cp: Int): String =
        if (cp in 0..0x10FFFF && !(cp in 0xD800..0xDFFF)) String(Character.toChars(cp)) else "\uFFFD"

    private fun unescapeDeep(v: Any?): Any? = when (v) {
        is String -> safeUnescape(v)
        is List<*> -> v.map { unescapeDeep(it) }
        is Map<*, *> -> v.entries.associate { it.key.toString() to unescapeDeep(it.value) }
        else -> v
    }

    /** 从响应原文里抽内联 JSON。任何一步失败返回 null。 */
    fun applyParse(spec: JsonElement?, text: String?): Any? {
        if (spec !is JsonObject || text == null) return null
        val m = try {
            Regex(spec.strField("re"), RegexOption.DOT_MATCHES_ALL).find(text)
        } catch (e: Exception) {
            return null
        }
        if (m == null) return null
        var cap: Any? = if (m.groups.size > 1) m.groups[1]?.value ?: m.value else m.value
        val asJson = !(spec["json"] is JsonPrimitive && (spec["json"] as JsonPrimitive).booleanOrNull == false)
        if (asJson) {
            cap = try {
                Json.parseToJsonElement(cap as String).toNative()
            } catch (e: Exception) {
                return null
            }
        }
        val idx = spec["index"]
        if (idx != null && idx !is JsonNull && cap is List<*>) {
            val i = asInt(idx.toNative(), 0)
            cap = if (i in cap.indices) cap[i] else return null
        }
        if (jsonTruthy(spec["unescape"])) cap = unescapeDeep(cap)
        return cap
    }

    // ── 步骤执行 ────────────────────────────────────────────────
    private fun tplList(v: JsonElement?): List<String> = when (v) {
        null, is JsonNull -> emptyList()
        is JsonPrimitive -> if (v.isString) listOf(v.content) else emptyList()
        is JsonArray -> v.mapNotNull { if (it is JsonPrimitive && it.isString) it.content else null }
        else -> emptyList()
    }

    private fun firstRenderable(cands: List<String>, ns: Any?): String? {
        for (t in cands) {
            var ok = true
            for (m in Regex("\\{([^{}]+)\\}").findAll(t)) {
                val v = walk(ns, m.groupValues[1])
                if (v === Miss || v == null || v == "") {
                    ok = false; break
                }
            }
            if (ok) return render(t, ns)
        }
        return null
    }

    private fun runStep(
        st: JsonObject, ns: MutableMap<String, Any?>, session: SessionState,
        getTransport: GetHook?, postTransport: PostHook?,
    ): Boolean {
        if (!cond(st["when"], ns)) return false
        val asName = (st.strField("as").ifEmpty { st.strField("name") }).ifEmpty { "_step" }
        val method = st.strField("method").ifEmpty { "GET" }.uppercase()
        val timeout = asInt(st["timeout"].toNative(), 15)

        val lad = st["ladder"].asObj()
        val values: List<Any?> = if (lad != null && lad.truthyField("var")) {
            val arr = lad["values"].asArr()
            if (arr.isNullOrEmpty()) listOf(null) else arr.map { it.toNative() }
        } else listOf(null)
        if (lad != null && lad.truthyField("var") && values[0] != null) {
            ns.putIfAbsent(lad.strField("var"), values[0])
        }

        val urls = tplList(st["url"]) + tplList(st["alt_url"])
        val urlTpl = firstRenderable(urls, ns)
        if (urlTpl == null) {
            logI("步骤 $asName 跳过：URL 模板变量不全")
            return false
        }
        val ref = firstRenderable(tplList(st["referer"]) + tplList(st["alt_referer"]), ns)
        val bodySel: JsonElement? = if (st.containsKey("json")) st["json"] else st["body"]
        val mode = st["cookies"]
        val pspec = st["parse"].asObj()

        val ladVar = lad?.strField("var")?.takeIf { it.isNotEmpty() }
        for (vel in values) {
            if (ladVar != null) ns[ladVar] = vel
            val u = render(urlTpl, ns)
            val hdr = LinkedHashMap<String, String>()
            st["headers"].asObj()?.let { h ->
                for ((k, v) in h) {
                    val sv = render(v.primContent() ?: "", ns)
                    if (sv != "") hdr[k] = sv
                }
            }
            val body = resolve(bodySel, ns)
            val cookies = cookiesFor(mode, ns, u, session)
            var j: Any? = try {
                if (method == "POST") {
                    httpPost(u, hdr, cookies, timeout, body, session, postTransport)
                } else {
                    httpGet(u, hdr, cookies, ref, timeout, session, getTransport, raw = pspec != null)
                }
            } catch (e: Exception) {
                logI("步骤 $asName 请求失败：${e.message}")
                null
            }
            if (pspec != null) {
                j = if (j is String) {
                    val parsed = applyParse(pspec, j)
                    if (parsed == null) logI("步骤 $asName：parse 未能从响应里抽出数据")
                    parsed
                } else null
            }
            if (j == null && getTransport == null && postTransport == null) continue
            ns[asName] = j ?: emptyMap<String, Any?>()
            val probe = LinkedHashMap<String, Any?>()
            probe.putAll(ns)
            if (j is Map<*, *>) for ((k, v) in j) probe[k.toString()] = v
            if (cond(st["expect"], probe)) {
                ns.putIfAbsent("cookies", emptyMap<String, Any?>())
                return true
            }
        }
        ns.putIfAbsent(asName, emptyMap<String, Any?>())
        if (jsonTruthy(st["required"])) ns["_failed"] = true
        return false
    }

    private fun runSteps(
        steps: JsonArray?, ns: MutableMap<String, Any?>, session: SessionState,
        getTransport: GetHook?, postTransport: PostHook?,
    ): Boolean {
        for (st in steps ?: JsonArray(emptyList())) {
            if (st is JsonObject) runStep(st, ns, session, getTransport, postTransport)
            if (truthy(ns["_failed"])) return false
        }
        return true
    }

    // ── 轨道产出 ────────────────────────────────────────────────
    private fun buildTrack(tr: JsonObject, ns: Any?, spec: JsonObject, url: String, session: SessionState): Track? {
        val u = (resolve(tr["url"], ns)?.toString() ?: "").trim()
        if (u.isEmpty()) return null
        val parts = ArrayList<Part>()
        for (pt in tr["parts"].asArr() ?: JsonArray(emptyList())) {
            val po = pt as? JsonObject ?: continue
            val pu = resolve(po["url"], ns)
            if (pu != null && pu.toString().isNotEmpty()) {
                parts.add(
                    Part(
                        mediaClass = resolve(po["media_class"], ns)?.toString() ?: "",
                        kind = normKind(resolve(po["kind"], ns)),
                        url = pu.toString(),
                        ext = resolve(po["ext"], ns)?.toString() ?: "",
                        note = resolve(po["note"], ns)?.toString() ?: "",
                    )
                )
            }
        }
        val ck = cookiesFor(tr["cookies"], ns, u, session)
        val title = resolve(tr["title_hint"], ns)
        val note = resolve(tr["note"], ns)
        val dur = resolve(tr["duration_s"], ns)
        val drmList = (tr["drm"].asArr() ?: JsonArray(emptyList())).mapNotNull { it.primContent() }
        return Track(
            index = 0,
            mediaClass = resolve(tr["media_class"], ns)?.toString() ?: "",
            kind = normKind(resolve(tr["kind"], ns)),
            url = u,
            ext = resolve(tr["ext"], ns)?.toString() ?: "",
            src = "recipe:" + (spec.strField("name").ifEmpty { "?" }),
            pageUrl = url,
            titleHint = title?.toString() ?: "",
            durationS = asNum(dur, 0.0),
            cookies = ck,
            drm = drmList,
            note = note?.toString() ?: "",
            parts = parts,
            ephemeral = truthy(resolve(tr["ephemeral"], ns)),
        )
    }

    private fun applySet(bindings: JsonObject?, ns: MutableMap<String, Any?>) {
        bindings ?: return
        for ((k, sel) in bindings) {
            if (k.startsWith("_")) continue
            ns[k] = resolve(sel, ns)
        }
    }

    private fun produceTracks(
        rule: JsonObject, ns: Any?, spec: JsonObject, url: String, session: SessionState, depth: Int,
    ): List<Track> {
        if (depth > MAX_EMIT_DEPTH) return emptyList()
        if (!cond(rule["when"], ns)) return emptyList()
        val local = LinkedHashMap<String, Any?>()
        (ns as? Map<*, *>)?.let { for ((k, v) in it) local[k.toString()] = v }
        applySet(rule["set"].asObj(), local)
        if (truthy(local["_failed"])) return emptyList()
        val tr = buildTrack(rule["track"].asObj() ?: JsonObject(emptyMap()), local, spec, url, session)
        return if (tr != null) listOf(tr) else emptyList()
    }

    private fun emitRule(
        e: JsonObject, ns: MutableMap<String, Any?>, spec: JsonObject, url: String, session: SessionState,
        depth: Int, getTransport: GetHook?, postTransport: PostHook?,
    ): List<Track> {
        if (depth > MAX_EMIT_DEPTH || !cond(e["when"], ns)) return emptyList()
        val loop = e["loop"].asObj()
        if (loop == null) {
            val local = LinkedHashMap<String, Any?>()
            local.putAll(ns)
            applySet(e["set"].asObj(), local)
            if (!runSteps(e["steps"].asArr(), local, session, getTransport, postTransport)) return emptyList()
            applySet(e["set"].asObj(), local)
            return emitTracks(e, local, spec, url, session, depth)
        }
        var items = resolve(JsonObject(mapOf("path" to (loop["path"] ?: JsonNull))), ns)
        if (items !is List<*> || items.isEmpty()) {
            val fb = loop["fallback"].asArr() ?: JsonArray(emptyList())
            items = fb.mapNotNull { it as? JsonObject }.map { it2 ->
                LinkedHashMap<String, Any?>().also { m ->
                    for ((k, v) in it2) m[k] = if (v is JsonPrimitive && v.isString) render(v.content, ns) else v.toNative()
                }
            }
        }
        val itemList = (items as? List<*>)?.toList() ?: emptyList()
        if (itemList.isEmpty()) return emptyList()
        val capped = itemList.take(maxOf(1, asInt(loop["max"].toNative(), MAX_TRACKS_PER_RULE)))
        val n = capped.size
        val pname = loop.strField("episode_param").takeIf { it.isNotEmpty() }
        val p = if (pname != null) asInt(get(ns, pname, 0), 0) else 0
        val chosen: List<Pair<Int, Int>> = when {
            p > 1 && n > 1 -> listOf((minOf(p, n) - 1) to minOf(p, n))
            n > 1 -> (0 until n).map { it to it + 1 }
            else -> listOf(0 to 1)
        }
        val pause = maxOf(0.0, asNum(loop["pause"].toNative(), 0.0))
        val out = ArrayList<Track>()
        chosen.forEachIndexed { seq, pair ->
            val i = pair.first
            val idx = pair.second
            if (seq > 0 && pause > 0) {
                try {
                    Thread.sleep((pause * 1000).toLong())
                } catch (_: InterruptedException) {
                }
            }
            val local = LinkedHashMap<String, Any?>()
            local.putAll(ns)
            local[loop.strField("as").ifEmpty { "item" }] = capped[i]
            local[loop.strField("index_as").ifEmpty { "idx" }] = idx
            local["picked"] = capped[i]
            local["count"] = n
            applySet(e["set"].asObj(), local)
            if (!runSteps(e["steps"].asArr(), local, session, getTransport, postTransport)) return@forEachIndexed
            applySet(e["set"].asObj(), local)
            out.addAll(emitTracks(e, local, spec, url, session, depth))
        }
        return out
    }

    private fun emitTracks(
        e: JsonObject, local: MutableMap<String, Any?>, spec: JsonObject, url: String, session: SessionState, depth: Int,
    ): List<Track> {
        val et = e["track"]
        if (et is JsonObject) return produceTracks(e, local, spec, url, session, depth)
        val out = ArrayList<Track>()
        for (pr in e["produce"].asArr() ?: JsonArray(emptyList())) {
            val po = pr as? JsonObject ?: continue
            val got = produceTracks(po, local, spec, url, session, depth)
            out.addAll(got)
            if (got.isNotEmpty() && jsonTruthy(po["stop"])) break
        }
        return out
    }

    // ── identity ────────────────────────────────────────────────
    private fun queryParams(url: String): Map<String, List<String>> {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        for (pair in q.split("&")) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val k = if (i >= 0) pair.substring(0, i) else pair
            val v = if (i >= 0) pair.substring(i + 1) else ""
            val dk = try { java.net.URLDecoder.decode(k, "UTF-8") } catch (e: Exception) { k }
            val dv = try { java.net.URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { v }
            out.getOrPut(dk) { ArrayList() }.add(dv)
        }
        return out
    }

    private fun identityVars(spec: JsonObject, url: String, cookies: Map<String, String>?): MutableMap<String, Any?>? {
        val ident = spec["identity"].asObj() ?: JsonObject(emptyMap())
        val ns = LinkedHashMap<String, Any?>()
        var groups: List<JsonElement> = when {
            ident.containsKey("any_of") -> {
                val g = ident["any_of"]!!
                if (g is JsonObject) listOf(g) else (g as? JsonArray)?.toList() ?: emptyList()
            }
            else -> listOf(ident["all_of"] ?: JsonArray(emptyList()))
        }
        if (groups.isEmpty()) groups = listOf(JsonArray(emptyList()))
        var hit = false
        for (grp in groups) {
            val rules: List<JsonElement> = if (grp is JsonArray) grp.toList() else listOf(grp)
            val got = LinkedHashMap<String, Any?>()
            var ok = true
            for (r in rules) {
                if (r !is JsonObject) {
                    ok = false; break
                }
                val m = try {
                    Regex(r.strField("pattern")).find(url)
                } catch (e: Exception) {
                    null
                }
                if (m == null) {
                    ok = false; break
                }
                val name = r.strField("name")
                if (name.isEmpty()) {
                    ok = false; break
                }
                var value: Any? = if (m.groups.size > 1) m.groups[1]!!.value else m.value
                if (r.strField("type").lowercase() == "int") value = asInt(value, 0)
                got[name] = value
            }
            if (ok && got.isNotEmpty()) {
                ns.putAll(got)
                hit = true
                break
            }
        }
        if (!hit && (ident.containsKey("any_of") || ident.containsKey("all_of"))) return null
        for (q in ident["query"].asArr() ?: JsonArray(emptyList())) {
            val qo = q as? JsonObject ?: continue
            val params = queryParams(url)
            val raw = params[qo.strField("param")] ?: emptyList()
            var value: Any? = raw.firstOrNull() ?: qo["default"].toNative()
            if (qo.strField("type").lowercase() == "int") {
                value = asInt(value, asInt(qo["default"].toNative(), 0))
                val lo = qo["min"].toNative()
                val hi = qo["max"].toNative()
                if (lo != null) value = maxOf(asInt(lo, 0), value as Int)
                if (hi != null) value = minOf(asInt(hi, 0), value as Int)
            }
            if (value != null) ns[qo.strField("name").ifEmpty { qo.strField("param") }] = value
        }
        ns["cookies"] = cookies?.toMap() ?: emptyMap<String, Any?>()
        ns["_url"] = url
        return ns
    }

    // ── 主入口 ──────────────────────────────────────────────────
    /** 按 JSON 配方解析 URL → [Track]。失败一律空列表，绝不抛。 */
    fun tracksFromSpec(
        spec: JsonElement?, url: String,
        cookies: Map<String, String>? = null,
        httpGet: GetHook? = null,
        httpPost: PostHook? = null,
    ): List<Track> {
        return try {
            if (spec !is JsonObject) return emptyList()
            val errs = validateSpec(spec)
            if (errs.isNotEmpty()) {
                logI("配方 ${spec.strField("name").ifEmpty { "?" }} 校验未通过：${errs.first()}")
                return emptyList()
            }
            val session = buildSession(spec["session"].asObj())
            val ck: Map<String, String> = cookies ?: resolveCookies(spec.strField("cookies").ifEmpty { "jar" }, url, session)
            val ns = identityVars(spec, url, ck) ?: return emptyList()
            ns["cookies"] = ck

            runSteps(spec["steps"].asArr(), ns, session, httpGet, httpPost)
            if (truthy(ns["_failed"])) return emptyList()

            val out = ArrayList<Track>()
            for (e in spec["emit"].asArr() ?: JsonArray(emptyList())) {
                if (e is JsonObject) out.addAll(emitRule(e, ns, spec, url, session, 0, httpGet, httpPost))
            }
            val keep = out.filter { it.url.isNotEmpty() }.take(MAX_TRACKS_PER_RULE)
            keep.forEachIndexed { i, t -> t.index = i + 1 }
            keep
        } catch (e: Exception) {
            logI("配方解析异常：${e.message}")
            emptyList()
        }
    }
}

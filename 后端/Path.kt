package com.taowu.backend.core

import com.taowu.backend.json.Miss

// 路径取值：点分 + [n] 下标 + [] 通配 + [a:b] 切片
private val TOK = Regex("""\[\s*(\d*)\s*:\s*(\d*)\s*\]|\[\s*(\d*)\s*\]|([^\.\[\]]+)""")

sealed class Tok {
    data class Key(val name: String) : Tok()
    data class Idx(val i: Int) : Tok()
    data object Wild : Tok()
    data class Slice(val start: Int?, val end: Int?) : Tok()
}

fun tokens(path: String?): List<Tok> {
    val out = ArrayList<Tok>()
    for (m in TOK.findAll(path ?: "")) {
        val g = m.groups
        val g4 = g[4]
        val g3 = g[3]
        when {
            g4 != null -> out.add(Tok.Key(g4.value))
            g3 != null -> {
                val v = g3.value
                if (v.isEmpty()) out.add(Tok.Wild) else out.add(Tok.Idx(v.toInt()))
            }
            else -> {
                val s = g[1]?.value?.takeIf { it.isNotEmpty() }
                val e = g[2]?.value?.takeIf { it.isNotEmpty() }
                out.add(Tok.Slice(s?.toInt(), e?.toInt()))
            }
        }
    }
    return out
}

/** 按路径取值；任一段缺失返回 Miss。通配/切片段返回列表；单命中退化为标量。 */
fun walk(obj: Any?, path: String?): Any? {
    val toks = tokens(path)
    if (toks.isEmpty()) return obj
    var cur: MutableList<Any?> = arrayListOf(obj)
    for (t in toks) {
        val nxt = ArrayList<Any?>()
        for (c in cur) {
            if (t is Tok.Key) {
                if (c is Map<*, *> && c.containsKey(t.name)) nxt.add(c[t.name])
            } else if (c is List<*>) {
                when (t) {
                    is Tok.Wild -> nxt.addAll(c)
                    is Tok.Slice -> {
                        val sz = c.size
                        val s = clampIndex(t.start ?: 0, sz)
                        val e = clampIndex(t.end ?: sz, sz)
                        if (s < e) nxt.addAll(c.subList(s, e))
                    }
                    is Tok.Idx -> if (t.i >= 0 && t.i < c.size) nxt.add(c[t.i])
                    else -> {}
                }
            }
        }
        if (nxt.isEmpty()) return Miss
        cur = nxt
    }
    return if (cur.size == 1) cur[0] else cur
}

private fun clampIndex(i: Int, size: Int): Int {
    val v = if (i < 0) i + size else i
    return v.coerceIn(0, size)
}

fun get(ns: Any?, path: String?, default: Any? = null): Any? {
    val v = if (!path.isNullOrEmpty()) walk(ns, path) else Miss
    return if (v === Miss) default else v
}

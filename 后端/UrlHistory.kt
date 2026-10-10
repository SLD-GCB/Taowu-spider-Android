package com.taowu.backend

import android.content.Context
import kotlinx.serialization.json.*
import java.io.File

// ================================================================
// 抓取历史：记录用户爬过的网址（去重、最近的在前），供捕获页一键回填输入框。
// 存 filesDir/url_history.json。
// ================================================================
object UrlHistory {

    private const val FILE = "url_history.json"
    private const val MAX = 60

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun load(ctx: Context): List<String> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val text = runCatching { f.readText() }.getOrNull() ?: return emptyList()
        val arr = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }

    /** 记一条（去重后置顶）。返回更新后的列表。 */
    fun add(ctx: Context, url: String): List<String> {
        val u = url.trim()
        if (u.isEmpty()) return load(ctx)
        val list = ArrayList<String>(MAX + 1)
        list.add(u)
        for (x in load(ctx)) if (x != u && list.size < MAX) list.add(x)
        write(ctx, list)
        return list
    }

    fun remove(ctx: Context, url: String): List<String> {
        val list = load(ctx).filterNot { it == url }
        write(ctx, list)
        return list
    }

    fun clear(ctx: Context): List<String> {
        write(ctx, emptyList())
        return emptyList()
    }

    private fun write(ctx: Context, list: List<String>) {
        runCatching {
            val arr = buildJsonArray { for (s in list) add(s) }
            file(ctx).writeText(arr.toString())
        }
    }
}

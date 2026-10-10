package com.taowu.backend

import android.content.Context

// ================================================================
// 播放进度记忆：按"源标识"记住上次看到的位置，下次进入自动续播。
// 用 SharedPreferences（原子、按 key 覆盖，适合高频小写入）。
//  · 整部（有分集）：key = "s:<标题>#<集标签>"（跨会话稳定，不受直链过期影响）
//  · 单条媒体：      key = "u:<直链>"
// ================================================================
object PlayPositions {

    private const val FILE = "play_positions"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): Long =
        if (key.isEmpty()) 0L else runCatching { sp(ctx).getLong(key, 0L) }.getOrDefault(0L)

    fun put(ctx: Context, key: String, positionMs: Long) {
        if (key.isEmpty() || positionMs <= 0) return
        runCatching { sp(ctx).edit().putLong(key, positionMs).apply() }
    }

    fun clear(ctx: Context, key: String) {
        if (key.isEmpty()) return
        runCatching { sp(ctx).edit().remove(key).apply() }
    }
}

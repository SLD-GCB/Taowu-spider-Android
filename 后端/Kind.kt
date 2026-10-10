package com.taowu.backend.core

// kind 归一：用户写 hls/mpd/direct → 内部三值契约 m3u8/dash/direct
val KIND_ALIASES = mapOf(
    "" to "direct", "direct" to "direct", "file" to "direct",
    "m3u8" to "m3u8", "hls" to "m3u8", "m3u" to "m3u8", "hls_m3u8" to "m3u8", "m3u8_url" to "m3u8",
    "dash" to "dash", "mpd" to "dash", "dash_mpd" to "dash",
)

val KIND_ALLOWED = listOf("direct", "hls", "mpd", "m3u8", "dash")
val KIND_CONTRACT = listOf("direct", "m3u8", "dash")

fun normKind(v: Any?): String {
    val k = (v?.toString() ?: "").trim().lowercase()
    return KIND_ALIASES[k] ?: k
}

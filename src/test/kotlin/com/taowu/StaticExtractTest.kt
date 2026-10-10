package com.taowu

import com.taowu.backend.parse.StaticExtract
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 静态扫描：`player_aaaa` 的 `encrypt:2` 是 **base64(URL编码(地址))** 两层编码
 * （真实站：girigirilove `/playGV27113-1-1/`）。要能一路解出 `.../playlist.m3u8`。
 */
class StaticExtractTest {

    // 站点原样抓到的 "url" 字段（base64 串）
    private val raw =
        "JTY4JTc0JTc0JTcwJTczJTNBJTJGJTJGJTYxJTZCJTc1JTYxJTJFJTY3JTY5JTcyJTY5JTY3JTY5JTcyJTY5JTZDJTZGJTc2JTY1JTJFJTYzJTZGJTZEJTJGJTdBJTY5JTZBJTY5JTYxJTZFJTJGJTZGJTZDJTY0JTYxJTZFJTY5JTZEJTY1JTJGJTMyJTMwJTMyJTM2JTJGJTMwJTM3JTJGJTYzJTY4JTc0JTJGJTQzJTY4JTYxJTY5JTZFJTczJTZEJTZGJTZCJTY1JTcyJTQzJTYxJTc0JTQzJTQ4JTU0JTJGJTMwJTMxJTJGJTcwJTZDJTYxJTc5JTZDJTY5JTczJTc0JTJFJTZEJTMzJTc1JTM4"

    @Test
    fun decodesBase64ThenUrlEncodedPlayerUrl() {
        val html = """<script>var player_aaaa={"flag":"play","encrypt":2,"url":"$raw","from":"cht"}</script>"""
        val (cands, notes) = StaticExtract.extractVideoCandidates(html, "https://ani.girigirilove.com/playGV27113-1-1/")
        println("cands=${cands.map { it.url }} notes=$notes")
        assertTrue(
            cands.any { it.url.contains("akua.girigirilove.com") && it.url.endsWith(".m3u8") },
            "应解出真实 m3u8，实际候选=${cands.map { it.url }}",
        )
    }
}

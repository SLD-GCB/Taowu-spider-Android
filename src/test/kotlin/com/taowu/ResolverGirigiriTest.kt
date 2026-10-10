package com.taowu

import com.taowu.backend.MediaResolver
import com.taowu.backend.ResolveMode
import com.taowu.backend.net.BinResult
import com.taowu.backend.net.NetEngine
import com.taowu.backend.net.TextResponse
import com.taowu.backend.registry.RecipeRegistry
import com.taowu.backend.run.RecipeEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** 端到端：整条 MediaResolver(通用) 链路 —— 真实站 girigirilove 的 encrypt:2 播放页要能解析出 m3u8。 */
class ResolverGirigiriTest {

    private val raw =
        "JTY4JTc0JTc0JTcwJTczJTNBJTJGJTJGJTYxJTZCJTc1JTYxJTJFJTY3JTY5JTcyJTY5JTY3JTY5JTcyJTY5JTZDJTZGJTc2JTY1JTJFJTYzJTZGJTZEJTJGJTdBJTY5JTZBJTY5JTYxJTZFJTJGJTZGJTZDJTY0JTYxJTZFJTY5JTZEJTY1JTJGJTMyJTMwJTMyJTM2JTJGJTMwJTM3JTJGJTYzJTY4JTc0JTJGJTQzJTY4JTYxJTY5JTZFJTczJTZEJTZGJTZCJTY1JTcyJTQzJTYxJTc0JTQzJTQ4JTU0JTJGJTMwJTMxJTJGJTcwJTZDJTYxJTc5JTZDJTY5JTczJTc0JTJFJTZEJTMzJTc1JTM4"

    private inner class PageNet(private val html: String) : NetEngine {
        override fun getRaw(url: String, headers: Map<String, String>, cookies: Map<String, String>, timeoutSec: Int): String? = html
        override fun postJsonRaw(url: String, bodyJson: String?, headers: Map<String, String>, cookies: Map<String, String>, timeoutSec: Int): String? = null
        override fun getBytes(url: String, headers: Map<String, String>, cookies: Map<String, String>, timeoutSec: Int): ByteArray? = null
        override fun getText(url: String, headers: Map<String, String>, cookies: Map<String, String>, timeoutSec: Int): TextResponse = TextResponse(html, url)
        override fun getBytesDetailed(url: String, headers: Map<String, String>, cookies: Map<String, String>, timeoutSec: Int, rangeStart: Long, rangeEnd: Long): BinResult? = null
        override fun probeTotalSize(url: String, headers: Map<String, String>, cookies: Map<String, String>, timeoutSec: Int): Long = -1L
        override fun headContentType(url: String, headers: Map<String, String>, cookies: Map<String, String>, timeoutSec: Int): String? = null
    }

    @Test
    fun resolvesEncrypt2PlayPage() = runBlocking {
        val html = """<html><head><title>尼古喵喵 - 站点</title></head><body>
            <script>var player_aaaa={"flag":"play","encrypt":2,"url":"$raw","from":"cht","id":"27113"}</script>
            </body></html>"""
        val net = PageNet(html)
        val resolver = MediaResolver(RecipeRegistry(), RecipeEngine(net), net)
        val pr = resolver.resolve("https://ani.girigirilove.com/playGV27113-1-1/", ResolveMode.GENERAL)
        println("route=${pr.route} notes=${pr.notes} tracks=${pr.tracks.map { it.url }}")
        assertTrue(pr.tracks.any { it.url.contains("playlist.m3u8") }, "应解出 m3u8，实际 tracks=${pr.tracks.map { it.url }}")
    }
}

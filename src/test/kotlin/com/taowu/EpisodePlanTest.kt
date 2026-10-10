package com.taowu

import com.taowu.backend.parse.EpisodePlan
import com.taowu.backend.parse.SiteAdapters
import com.taowu.backend.schema.validateSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpisodePlanTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.bufferedReader(Charsets.UTF_8).readText()

    @Test
    fun dropsIncompleteSingleEpisodeLine() {
        val html = fixture("aged139004.html")
        val plan = EpisodePlan.parse(html, "https://www.agedmani.com/v/139004.html")
        // 原始：线路1 只有 1 集（不完整），线路2..5 各 12 集
        assertEquals(setOf(1, 2, 3, 4, 5), plan.episodes.groupBy { it.line }.keys)

        val (kept, dropped) = EpisodePlan.dropIncompleteLines(plan.episodes)
        println("droppedLines=$dropped  linesAfter=${kept.groupBy { it.line }.mapValues { it.value.size }}")
        assertEquals(1, dropped, "应过滤掉 1 条只有 1 集的线路")
        val byLine = kept.groupBy { it.line }
        assertEquals(setOf(2, 3, 4, 5), byLine.keys, "线路1（仅1集）应被丢弃")
        assertTrue(byLine.values.all { it.size == 12 })

        // 单集（电影）场景：全部只有 1 集 → 一条都不滤
        val movie = listOf(
            EpisodePlan.Episode(1, "正片", "https://x/a", 1),
            EpisodePlan.Episode(1, "正片", "https://x/b", 2),
        )
        val (mKept, mDropped) = EpisodePlan.dropIncompleteLines(movie)
        assertEquals(0, mDropped, "全是单集（电影）时不应过滤")
        assertEquals(2, mKept.size)
    }

    @Test
    fun agedmaniDetailYieldsAllEpisodes() {
        val html = fixture("aged139004.html")
        val plan = EpisodePlan.parse(html, "https://www.agedmani.com/v/139004.html")
        println("matched=${plan.matched} lines=${plan.lines} episodes=${plan.episodes.size} title=${plan.title}")
        assertEquals("maccms", plan.matched)
        assertTrue(plan.episodes.size >= 12, "应识别出全部 12 集，实际 ${plan.episodes.size}")
        // 按集号分组后应为 12 个分集项
        val nums = plan.episodes.mapNotNull { it.num }.toSortedSet()
        println("distinct nums=$nums")
        assertEquals(12, nums.size)
        assertEquals(12, nums.last())

        // 按线路分组：5 条线路（线路1=1集，线路2..5 各 12 集）
        val byLine = plan.episodes.groupBy { it.line }
        println("lines -> counts: " + byLine.mapValues { it.value.size })
        assertEquals(5, byLine.size)
        assertEquals(setOf(1, 2, 3, 4, 5), byLine.keys)
        assertEquals(1, byLine[1]!!.size)
        assertEquals(12, byLine[2]!!.size)
        assertEquals(12, byLine[5]!!.size)
    }

    /** 非标准格式（playGV{id}-{线}-{集}）：默认不认，配了「额外分集链接正则」后能认。 */
    @Test
    fun extraLinkRegexEnablesPlayGvFormat() {
        val html = """
            <html><body>
            <a href="/playGV27113-1-1/">入口A</a>
            <a href="/playGV27113-1-2/">入口B</a>
            <a href="/playGV27113-1-3/">入口C</a>
            <a href="/playGV27113-1-4/">入口D</a>
            </body></html>
        """.trimIndent()
        val pageUrl = "https://ani.girigirilove.com/playGV27113-1-1/"
        val anchors = EpisodePlan.collectAnchors(html, pageUrl)

        val off = EpisodePlan.planFromAnchors(anchors, pageUrl, "尼古喵喵", "")
        println("off: matched=${off.matched} eps=${off.episodes.size}")
        assertEquals("none", off.matched, "默认不认 playGV 格式")

        val on = EpisodePlan.planFromAnchors(anchors, pageUrl, "尼古喵喵", """^playGV(\d+)-(\d+)-(\d+)$""")
        println("on: matched=${on.matched} eps=${on.episodes.size} nums=${on.episodes.map { it.num }}")
        assertEquals("maccms", on.matched, "配了额外正则后应识别为分集计划")
        assertEquals(4, on.episodes.size)
        assertEquals(listOf(1, 2, 3, 4), on.episodes.map { it.num })
    }

    /**
     * 内置配方 girigirilove.json：① 能过 schema 校验；② 它自报的分集形状（episode_link）
     * 注册进站点表后，**通用模式不传正则**也能认出 /playGV{id}-{线}-{集}/ 并支持详情页→播放页跳转。
     */
    @Test
    fun builtinGirigiriRecipeDrivesEpisodeLink() {
        val f = java.io.File("src/main/assets/recipes/girigirilove.json")
        assertTrue(f.exists(), "找不到内置配方：${f.absolutePath}")
        val spec = Json.parseToJsonElement(f.readText())
        val errs = validateSpec(spec)
        assertEquals(emptyList(), errs, "内置配方未通过 schema 校验：$errs")

        val re = ((spec as JsonObject)["episode_link"] as? JsonObject)?.get("re")?.let {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        }
        assertTrue(!re.isNullOrBlank(), "配方缺 episode_link.re")

        SiteAdapters.reset()
        try {
            // 等价 registerSpec 里那步：把站点自报的形状注册进站点表
            SiteAdapters.register("ani.girigirilove.com", episodeLink = re)

            val html = """
                <html><body>
                <a href="/playGV27186-1-1/">01</a>
                <a href="/playGV27186-1-2/">02</a>
                <a href="/playGV27186-2-1/">01</a>
                <a href="/playGV27186-2-2/">02</a>
                </body></html>
            """.trimIndent()
            val pageUrl = "https://ani.girigirilove.com/GV27186/"

            // 详情页：通用模式靠配方形状就能跳到播放页
            assertEquals(
                "https://ani.girigirilove.com/playGV27186-1-1/",
                EpisodePlan.firstMacCmsAnchor(html, pageUrl),
                "应能认出播放页链接",
            )

            // 分集计划：**不显式传正则**，走配方注册的形状
            val plan = EpisodePlan.parse(html, pageUrl)
            println("builtin: matched=${plan.matched} eps=${plan.episodes.size} byLine=${plan.episodes.groupBy { it.line }.mapValues { it.value.size }}")
            assertEquals("maccms", plan.matched)
            assertEquals(4, plan.episodes.size)
        } finally {
            SiteAdapters.reset()
        }
    }
}

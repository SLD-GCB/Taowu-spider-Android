package com.taowu

import com.taowu.backend.field.FieldColumn
import com.taowu.backend.field.FieldCtx
import com.taowu.backend.field.SchemaExtract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 字段抽取引擎（移植自 schema_engine.py）回归。 */
class SchemaExtractTest {

    private val sample = """
        【新闻标题】能帮我弄干净吗？
        作者：张三
        发布时间：2026-10-10 12:30
        来源：某某日报
        阅读量：1,234 次阅读
        售价：￥199.50 元
    """.trimIndent()

    @Test
    fun scanPairsHandlesBothForms() {
        val p = SchemaExtract.scanPairs(sample)
        assertEquals("能帮我弄干净吗？", p["新闻标题"])   // 【标签】值
        assertEquals("张三", p["作者"])                 // 标签：值
        assertEquals("某某日报", p["来源"])
    }

    @Test
    fun extractByLabelSynonymAndClean() {
        val cols = listOf(
            FieldColumn("标题", "TEXT", "title", synonyms = listOf("新闻标题")),
            FieldColumn("作者", "TEXT", "text"),
            FieldColumn("发布时间", "TEXT", "datetime", synonyms = listOf("发表时间")),
            FieldColumn("来源媒体", "TEXT", "text", synonyms = listOf("来源")),
            FieldColumn("价格", "TEXT", "money"),
        )
        val r = SchemaExtract.extract(sample, cols)
        assertEquals("能帮我弄干净吗？", r["标题"])        // 同义词命中
        assertEquals("张三", r["作者"])
        assertEquals("2026-10-10 12:30", r["发布时间"])   // datetime 归一
        assertEquals("某某日报", r["来源媒体"])
        assertEquals("199.50", r["价格"])                // money 兜底（无「价格」标签，从 ¥ 泛扫）
    }

    @Test
    fun titleFallsBackToCtxTitles() {
        val cols = listOf(FieldColumn("标题", "TEXT", "title"))
        val r = SchemaExtract.extract("正文没有标题标签", cols, FieldCtx(titles = listOf("页面大标题")))
        assertEquals("页面大标题", r["标题"])
    }

    @Test
    fun columnRegexUsedWhenNoLabel() {
        val cols = listOf(
            FieldColumn("阅读量", "TEXT", "text", re = listOf("""([0-9][0-9,]*(?:\.[0-9]+)?万?)\s*\+?\s*(?:次阅读|人阅读|浏览量)""")),
        )
        // 文本里没有「阅读量：」标签，只能靠列内正则
        val r = SchemaExtract.extract("本文共 3.5万 次阅读 完毕", cols)
        assertEquals("3.5万", r["阅读量"])
    }

    @Test
    fun systemColumnsFromCtx() {
        val cols = listOf(
            FieldColumn("job_url", "TEXT", "link"),
            FieldColumn("source", "TEXT", "text"),
        )
        val r = SchemaExtract.extract("空", cols, FieldCtx(url = "https://x.com/a", site = "x.com"))
        assertEquals("https://x.com/a", r["job_url"])
        assertEquals("x.com", r["source"])
    }

    @Test
    fun valueEqualToNameIsDropped() {
        val cols = listOf(FieldColumn("标题", "TEXT", "text"))
        val r = SchemaExtract.extract("标题：标题", cols)
        assertEquals("", r["标题"])
    }

    @Test
    fun missingValueIsEmptyNotError() {
        val cols = listOf(FieldColumn("不存在字段", "TEXT", "text"))
        val r = SchemaExtract.extract("完全没有的文本", cols)
        assertTrue(r.containsKey("不存在字段"))
        assertEquals("", r["不存在字段"])
    }
}

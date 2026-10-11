package com.taowu

import com.taowu.backend.field.RecruitExtract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 招聘抽取器（移植自潜龙纯正则链路）回归。 */
class RecruitExtractTest {

    private val sample =
        "深圳某某科技有限公司诚聘。岗位名称：高级测试工程师。工作地点：深圳南山区。" +
            "薪资范围：15K-20K。学历要求：本科。工作经验：3-5年。" +
            "福利：五险一金、双休、年终奖。联系电话：13800138000。"

    @Test
    fun extractsCoreFields() {
        val r = RecruitExtract.extract(sample, url = "https://job.example.com/1", site = "example.com")
        assertEquals("深圳某某科技有限公司", r["企业名称"])
        assertEquals("高级测试工程师", r["岗位名称"])
        assertTrue(r["工作地点"]!!.contains("深圳"), "工作地点应含城市：${r["工作地点"]}")
        assertEquals("15K-20K", r["薪资范围"])
        assertEquals("本科", r["学历要求"])
        assertEquals("3-5年", r["工作经验"])
        assertTrue(r["联系方式"]!!.contains("13800138000"))
        assertEquals("https://job.example.com/1", r["job_url"])
        assertEquals("example.com", r["source"])
    }

    @Test
    fun welfareJoinsMultiple() {
        val r = RecruitExtract.extract(sample)
        val w = r["福利信息"]!!
        assertTrue(w.contains("五险一金") && w.contains("双休"), "福利应拼接多项：$w")
    }

    @Test
    fun missingFieldIsEmpty() {
        val r = RecruitExtract.extract("一段和招聘无关的文字。")
        assertEquals("", r["薪资范围"])
        assertEquals("", r["学历要求"])
    }

    @Test
    fun hintsOverrideHeuristic() {
        // 页面正文没有"岗位名称："标签（SPA），只有脚本里的结构化线索 → 优先用线索
        val r = RecruitExtract.extract(
            "智联校园 首页 一段没有岗位标签的普通文本",
            url = "u",
            site = "s",
            hints = mapOf(
                "job" to "6000+线上服务",
                "company" to "金慧科技",
                "salary" to "6000-7000元",
                "location" to "甘井子区",
                "education" to "大专",
                "experience" to "3年以上",
            ),
        )
        assertEquals("6000+线上服务", r["岗位名称"])
        assertEquals("金慧科技", r["企业名称"])
        assertEquals("6000-7000元", r["薪资范围"])
        assertEquals("甘井子区", r["工作地点"])
        assertEquals("大专", r["学历要求"])
        assertEquals("3年以上", r["工作经验"])
    }
}

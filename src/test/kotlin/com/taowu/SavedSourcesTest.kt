package com.taowu

import com.taowu.backend.save.SavedSources
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 解析源"集数是否齐全"的判定（按实际情况）。 */
class SavedSourcesTest {

    private fun ep(label: String, candidates: Int) =
        SavedSources.SavedEpisode(label, null, List(candidates) { "https://x/p$it" })

    @Test
    fun noEpisodesNeedsTracks() {
        assertTrue(SavedSources.isComplete(emptyList(), hasTracks = true, title = ""))
        assertFalse(SavedSources.isComplete(emptyList(), hasTracks = false, title = ""))
    }

    @Test
    fun multiEpisodeIsComplete() {
        val eps = listOf(ep("第01集", 1), ep("第02集", 1), ep("第03集", 1))
        assertTrue(SavedSources.isComplete(eps, false, "某番"))
    }

    @Test
    fun singleEpisodeJudgedByActualSituation() {
        // 1 集 + 只有一条线路 + 集名是"第01集" → 判为没抓全
        assertFalse(SavedSources.isComplete(listOf(ep("第01集", 1)), hasTracks = false, title = "某番"))
        assertFalse(SavedSources.isComplete(listOf(ep("1", 1)), false, "某番"))
        assertFalse(SavedSources.isComplete(listOf(ep("EP01", 1)), false, "某番"))

        // 1 集但多条线路都指向它 → 典型电影，完整
        assertTrue(SavedSources.isComplete(listOf(ep("第01集", 3)), false, "某番"))

        // 1 集且集名是电影式标签 → 完整
        assertTrue(SavedSources.isComplete(listOf(ep("正片", 1)), false, "某电影"))
        assertTrue(SavedSources.isComplete(listOf(ep("HD", 1)), false, "某电影"))
        assertTrue(SavedSources.isComplete(listOf(ep("", 1)), false, "某电影"))

        // 标题带"剧场版" → 完整
        assertTrue(SavedSources.isComplete(listOf(ep("第01集", 1)), false, "某番剧场版"))
    }
}

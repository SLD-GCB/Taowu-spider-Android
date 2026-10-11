package com.taowu

import com.taowu.backend.AdFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 广告过滤规则回归：广告域/广告流要拦，正常视频/含 ad 的普通词不能误伤。 */
class AdFilterTest {

    @Test
    fun blocksAdDomains() {
        assertTrue(AdFilter.isAd("https://ads.doubleclick.net/pixel.gif"))
        assertTrue(AdFilter.isAd("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(AdFilter.isAd("https://googleads.g.doubleclick.net/video/ad.mp4"))
        assertTrue(AdFilter.isAd("http://cpro.baidu.com/cpro/ui/x.js"))
    }

    @Test
    fun blocksAdPathTokens() {
        assertTrue(AdFilter.isAd("https://cdn.example.com/ad/clip.mp4"))
        assertTrue(AdFilter.isAd("https://v.cdn.com/2026/preroll/0001.ts"))
        assertTrue(AdFilter.isAd("https://x.com/vast/advert/vast.xml"))
        assertTrue(AdFilter.isAd("https://x.com/adserver/index.m3u8"))
    }

    @Test
    fun keepsRealVideo() {
        assertFalse(AdFilter.isAd("https://vip.ffzy-plays.com/20260112/49422_edd2289f/index.m3u8"))
        assertFalse(AdFilter.isAd("https://akua.girigirilove.com/zijian/oldanime/2026/07/cht/X/01/playlist.m3u8"))
    }

    @Test
    fun doesNotFalsePositiveAdSubstring() {
        // radio / shadow / load / broadv 不能被当广告
        assertFalse(AdFilter.isAd("https://x.com/radio/stream.m3u8"))
        assertFalse(AdFilter.isAd("https://x.com/shadow/1.mp4"))
        assertFalse(AdFilter.isAd("https://x.com/download/video.mp4"))
        assertFalse(AdFilter.isAd("https://x.com/adventure/ep1.m3u8"))
    }

    @Test
    fun extraKeywordsAndParse() {
        val extra = AdFilter.parseExtra("myads.com, 推广 ;\nsponsor-x")
        assertEquals(listOf("myads.com", "推广", "sponsor-x"), extra)
        assertTrue(AdFilter.isAd("https://cdn.myads.com/a.mp4", extra))
        assertTrue(AdFilter.isAd("https://x.com/path/sponsor-x/1.ts", extra))
        assertFalse(AdFilter.isAd("https://cdn.foo.com/a.mp4", extra))
    }
}

package com.taowu

import com.taowu.backend.media.VideoMeta
import kotlin.test.Test
import kotlin.test.assertEquals

/** 封面（海报）抽取回归：覆盖各种形态。 */
class VideoMetaTest {

    private val page = "https://site.com/v/1.html"

    private fun cover(html: String) = VideoMeta.extract(html, page).coverUrl

    @Test
    fun relativePosterViaImgClass() {
        assertEquals(
            "https://site.com/upload/vod/2026/01/a.jpg",
            cover("""<html><body><img class="pic" data-src="/upload/vod/2026/01/a.jpg"></body></html>"""),
        )
    }

    @Test
    fun posterInsideContainerWithoutClassHint() {
        // 仿 girigiri：img 自身 class 无海报词，但父容器 class="detail-pic"，且用 data-src 懒加载
        val html = """<div class="detail-pic"><img class="lazy lazy1 mask" src="data:image/gif;base64,R0lGOD" data-src="/upload/vod/20261001-1/xx.webp"></div>"""
        assertEquals("https://site.com/upload/vod/20261001-1/xx.webp", cover(html))
    }

    @Test
    fun bareLazyImgWithoutAnyHint() {
        // 只有 data-src、无类名、无海报目录词
        assertEquals("https://site.com/img/abc.webp", cover("""<img data-src="/img/abc.webp">"""))
    }

    @Test
    fun lazyAttrsAndSrcset() {
        assertEquals("https://site.com/cover/p1.jpg", cover("""<img class="poster" data-original="/cover/p1.jpg">"""))
        assertEquals("https://site.com/img/t2.webp", cover("""<img id="thumb" data-lazy-src="/img/t2.webp">"""))
    }

    @Test
    fun ogAndLinkImageSrc() {
        assertEquals("https://site.com/cover/x.png", cover("""<meta property="og:image" content="/cover/x.png">"""))
        assertEquals("https://site.com/cover/y.jpg", cover("""<link rel="image_src" href="/cover/y.jpg">"""))
    }

    @Test
    fun videoPosterAndPictureSource() {
        assertEquals("https://site.com/pic/p.jpg", cover("""<video poster="/pic/p.jpg"></video>"""))
        assertEquals("https://site.com/pic/s.webp", cover("""<picture><source srcset="/pic/s.webp 1x"></picture>"""))
    }

    @Test
    fun backgroundImage() {
        assertEquals("https://site.com/pic/p.jpg", cover("""<div style="background-image:url('/pic/p.jpg')"></div>"""))
    }

    @Test
    fun skipsLogoAndPicksRealPoster() {
        val html = """<img src="/upload/liang.png" class="logo1 none">
                      <img class="pic" data-src="/cover/real.webp">"""
        assertEquals("https://site.com/cover/real.webp", cover(html))
    }

    @Test
    fun logoOnlyYieldsNoCover() {
        assertEquals("", cover("""<img src="/upload/liang.png" class="logo1 none">"""))
    }
}

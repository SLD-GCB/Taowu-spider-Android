package com.taowu.backend

/**
 * 广告过滤：按「广告域名 + URL 路径词元」拦掉广告请求与广告流。
 *
 * 用途：
 *  · 解析候选轨道时（MediaResolver）把广告流（如 preroll/vast/广告 CDN）剔除，避免把广告当视频抓下来；
 *  · 内置浏览器（CaptureScreen）拦截广告域请求，页面更干净、也不会误点进广告页。
 *
 * 原则：**保守优先**。只认明确的广告域与整词路径词元（按 . _ - / ? & = 切分后整词比对），
 * 避免把 radio / load / shadow 这类含 "ad" 的正常词误伤。用户可用「额外关键字」补充。
 */
object AdFilter {

    /** 广告 / 追踪域名（命中 host 本身或其子域即拦）。 */
    private val AD_DOMAINS = listOf(
        // 国际
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googleadsserving.cn", "imasdk.googleapis.com", "2mdn.net",
        "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com",
        "advertising.com", "adform.net", "smartadserver.com", "spotxchange.com",
        "adskeeper.com", "adsterra.com", "propellerads.com", "popads.net",
        "popcash.net", "exoclick.com", "juicyads.com", "trafficjunky.net",
        "onclickads.net", "revcontent.com", "mgid.com", "zedo.com",
        "adcash.com", "adcolony.com", "vungle.com", "applovin.com",
        "inmobi.com", "moloco.com", "sharethrough.com", "yieldmo.com",
        "ad-delivery.net", "moatads.com", "adroll.com", "teads.tv",
        // 国内常见
        "cpro.baidu.com", "pos.baidu.com", "union.baidu.com", "hm.baidu.com",
        "tanx.com", "alimama.com", "mmstat.com", "cnzz.com",
        "umeng.com", "umengcloud.com", "gdt.qq.com", "e.qq.com", "ipinyou.com",
    )

    /** 路径 / 文件名里的广告词元（整词比对）。 */
    private val AD_TOKENS = setOf(
        "ad", "ads", "adv", "advert", "adverts", "advertising", "advertisement",
        "preroll", "midroll", "postroll", "vast", "vmap",
        "adserver", "adcdn", "popunder", "sponsor",
    )

    /** 是否广告 URL。extra 为额外的子串关键字（用户自定义）。 */
    fun isAd(url: String, extra: List<String> = emptyList()): Boolean {
        if (url.isEmpty()) return false
        val lower = url.lowercase()

        val schemeIdx = lower.indexOf("://")
        val afterScheme = if (schemeIdx >= 0) lower.substring(schemeIdx + 3) else lower
        val slash = afterScheme.indexOf('/')
        val host: String
        val pathAndQuery: String
        if (slash >= 0) {
            host = afterScheme.substring(0, slash).substringBefore(':')
            pathAndQuery = afterScheme.substring(slash)
        } else {
            host = afterScheme.substringBefore(':')
            pathAndQuery = ""
        }

        // ① 广告域
        for (d in AD_DOMAINS) {
            if (host == d || host.endsWith(".$d")) return true
        }

        // ② 路径/查询词元
        val tokens = pathAndQuery.split('/', '.', '_', '-', '?', '&', '=')
        if (tokens.any { it.isNotEmpty() && it in AD_TOKENS }) return true

        // ③ 用户自定义子串
        for (p in extra) {
            val s = p.trim().lowercase()
            if (s.isNotEmpty() && lower.contains(s)) return true
        }
        return false
    }

    /** 从设置里的字符串解析出额外关键字（逗号/空格/分号/换行分隔）。 */
    fun parseExtra(raw: String): List<String> =
        raw.split(',', '，', ' ', '\n', '\r', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }
}

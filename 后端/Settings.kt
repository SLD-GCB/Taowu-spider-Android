package com.taowu.backend.settings

import android.content.Context
import android.content.SharedPreferences
import com.taowu.backend.net.MediaHeaders

// ================================================================
// 抓取参数（应用级设置，SharedPreferences 持久化）。
// 目的：把先前散落在各处的硬编码常量集中起来，运行时可调，避免僵化。
// 运行期读取：MediaResolver（跳数/静态扫描）、Downloader（并发/重试/Range）、
//            Throttle（闸门开关/并发上限）、MediaHeaders（UA）、UI（打标开关）。
// ================================================================
object AppSettings {

    private const val FILE = "taowu_settings"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun bool(key: String, def: Boolean) = if (::sp.isInitialized) sp.getBoolean(key, def) else def
    private fun int(key: String, def: Int) = if (::sp.isInitialized) sp.getInt(key, def) else def
    private fun str(key: String, def: String) = if (::sp.isInitialized) sp.getString(key, def) ?: def else def

    private fun putBool(key: String, v: Boolean) { if (::sp.isInitialized) sp.edit().putBoolean(key, v).apply() }
    private fun putInt(key: String, v: Int) { if (::sp.isInitialized) sp.edit().putInt(key, v).apply() }
    private fun putStr(key: String, v: String) { if (::sp.isInitialized) sp.edit().putString(key, v).apply() }

    // ── 解析 ────────────────────────────────────────────────────
    /** 通用模式 iframe / meta-refresh / MacCMS 分集页 的最大跳转层数。 */
    var resolveMaxHops: Int
        get() = int("resolve_max_hops", 2).coerceIn(0, 6)
        set(v) = putInt("resolve_max_hops", v.coerceIn(0, 6))

    /** 通用模式是否启用页内八趟静态扫描。 */
    var staticScanEnabled: Boolean
        get() = bool("static_scan", true)
        set(v) = putBool("static_scan", v)

    /** 通用模式是否跟随 iframe / meta-refresh / MacCMS 分集页 深挖。 */
    var hopEnabled: Boolean
        get() = bool("hop_enabled", true)
        set(v) = putBool("hop_enabled", v)

    /** 解析/取页超时（秒）。 */
    var pageTimeoutSec: Int
        get() = int("page_timeout", 20).coerceIn(5, 120)
        set(v) = putInt("page_timeout", v.coerceIn(5, 120))

    /** 页面抓取失败（超时/非 2xx/网络异常）时的重试次数（0=只抓一次）。 */
    var parseRetry: Int
        get() = int("parse_retry", 2).coerceIn(0, 6)
        set(v) = putInt("parse_retry", v.coerceIn(0, 6))

    /** 重试的间隔基数（毫秒，按第几次重试线性退避：base×attempt）。 */
    var parseRetryBackoffMs: Int
        get() = int("parse_retry_backoff", 800).coerceIn(0, 5000)
        set(v) = putInt("parse_retry_backoff", v.coerceIn(0, 5000))

    /** 是否启用页面内的混淆/多重编码解码（base64↔URL 百分号等，MacCMS encrypt 系）。 */
    var decodeEnabled: Boolean
        get() = bool("decode_enabled", true)
        set(v) = putBool("decode_enabled", v)

    /** 解码最大轮数（base64 与百分号可反复套多层，层数上限）。 */
    var decodeMaxRounds: Int
        get() = int("decode_max_rounds", 8).coerceIn(2, 12)
        set(v) = putInt("decode_max_rounds", v.coerceIn(2, 12))

    /** 一条线路至少要有这么多集才算"完整"，少于此数的线路被丢弃（电影/单集不受影响）。 */
    var minLineEpisodes: Int
        get() = int("min_line_episodes", 2).coerceIn(1, 9)
        set(v) = putInt("min_line_episodes", v.coerceIn(1, 9))

    /** 通用分集聚类最少锚点数（少于此数不成族）。 */
    var seriesAnchorMin: Int
        get() = int("series_anchor_min", 2).coerceIn(2, 20)
        set(v) = putInt("series_anchor_min", v.coerceIn(2, 20))

    /**
     * 额外的分集链接识别正则（可选，匹配**锚点路径**，需 3 个捕获组：①分组 ②线路 ③集号）。
     * 用于适配非标准 MacCMS 形态，例如 `^playGV(\d+)-(\d+)-(\d+)$`。留空=不启用。
     */
    var extraEpisodeLinkRegex: String
        get() = str("extra_ep_link_regex", "")
        set(v) = putStr("extra_ep_link_regex", v)

    /** 广告过滤：解析候选轨道与浏览器请求时，拦掉广告域 / 广告流（如 preroll、VAST、广告 CDN）。 */
    var adFilterEnabled: Boolean
        get() = bool("ad_filter", true)
        set(v) = putBool("ad_filter", v)

    /** 广告过滤的额外关键字（域名 / 路径子串，逗号或空格分隔，命中即拦）。 */
    var adFilterExtra: String
        get() = str("ad_filter_extra", "")
        set(v) = putStr("ad_filter_extra", v)

    // ── 文字采集 ────────────────────────────────────────────────
    /** 文字采集用的默认字段模板名（空=用列表第一个）。 */
    var fieldTemplate: String
        get() = str("field_template", "")
        set(v) = putStr("field_template", v)

    /** 采集结果保留上限。 */
    var collectMax: Int
        get() = int("collect_max", 500).coerceIn(1, 100000)
        set(v) = putInt("collect_max", v.coerceIn(1, 100000))

    /** 内置浏览器是否「适配屏幕」（把没做手机适配的页面缩小到手机屏宽，整宽可见）。 */
    var browserFit: Boolean
        get() = bool("browser_fit", false)
        set(v) = putBool("browser_fit", v)

    // ── 下载 ────────────────────────────────────────────────────
    /** HLS/DASH 分片并发窗口。 */
    var downloadConcurrency: Int
        get() = int("dl_concurrency", 12).coerceIn(1, 32)
        set(v) = putInt("dl_concurrency", v.coerceIn(1, 32))

    /** 整部抓取时同时下载的集数（PC: VIDEO_SERIES_PARALLEL=3；多集命中同一 CDN 时由 host 闸门排队）。 */
    var seriesParallel: Int
        get() = int("series_parallel", 3).coerceIn(1, 8)
        set(v) = putInt("series_parallel", v.coerceIn(1, 8))

    /** 失败重试次数。 */
    var downloadRetry: Int
        get() = int("dl_retry", 2).coerceIn(0, 8)
        set(v) = putInt("dl_retry", v.coerceIn(0, 8))

    /** Range 多连接分段数。 */
    var rangeSplits: Int
        get() = int("range_splits", 6).coerceIn(1, 16)
        set(v) = putInt("range_splits", v.coerceIn(1, 16))

    /** 触发 Range 多连接的最小文件大小（MB）。 */
    var rangeMinMb: Int
        get() = int("range_min_mb", 8).coerceIn(1, 512)
        set(v) = putInt("range_min_mb", v.coerceIn(1, 512))

    /** 是否启用 Range 多连接加速（关则直链单流+续传）。 */
    var rangeEnabled: Boolean
        get() = bool("range_enabled", true)
        set(v) = putBool("range_enabled", v)

    /** 分片/直链请求超时（秒）。 */
    var segmentTimeoutSec: Int
        get() = int("seg_timeout", 30).coerceIn(5, 120)
        set(v) = putInt("seg_timeout", v.coerceIn(5, 120))

    /**
     * 解密方式（HLS 分片）：
     *  · auto      = 按清单 `#EXT-X-KEY:METHOD` 自动判定（默认）
     *  · aes128 / aes256 / aes256ctr / cbcs = 强制按该方式解密（清单声明缺失/写错时的兜底）
     *  · off       = 不解密，分片原样落盘
     */
    var decryptMode: String
        get() = str("decrypt_mode", "auto")
        set(v) = putStr("decrypt_mode", v)

    // ── 节流 ────────────────────────────────────────────────────
    /** 全局 + 单 host 闸门总开关。 */
    var throttleEnabled: Boolean
        get() = bool("throttle_enabled", true)
        set(v) = putBool("throttle_enabled", v)

    /** 单 host 并发上限。 */
    var hostConcurrency: Int
        get() = int("host_conc", 12).coerceIn(1, 32)
        set(v) = putInt("host_conc", v.coerceIn(1, 32))

    /** 全局并发上限。 */
    var globalConcurrency: Int
        get() = int("global_conc", 24).coerceIn(1, 64)
        set(v) = putInt("global_conc", v.coerceIn(1, 64))

    /** 自适应并发控制器开关。 */
    var adaptiveEnabled: Boolean
        get() = bool("adaptive_enabled", true)
        set(v) = putBool("adaptive_enabled", v)

    // ── 请求头 ──────────────────────────────────────────────────
    /** 全局 User-Agent（空则用内置默认）。 */
    var userAgent: String
        get() = str("user_agent", "").ifEmpty { MediaHeaders.DEFAULT_UA }
        set(v) = putStr("user_agent", v)

    /** 全局附加 Referer 覆盖（空 = 用各页自身）。 */
    var refererOverride: String
        get() = str("referer_override", "")
        set(v) = putStr("referer_override", v)

    // ── 播放 / 解码 ──────────────────────────────────────────────
    /**
     * 视频解码模式：
     *  · auto = 硬解优先，硬解解不了时用 FFmpeg 软解兜底（默认，兼容 10bit H.264）
     *  · soft = 强制 FFmpeg 软解（硬解解 10bit 出绿屏/花屏时用）
     *  · hard = 仅硬解（不用软解，省电；10bit 会播放失败）
     */
    var videoDecodeMode: String
        get() = str("video_decode_mode", "auto")
        set(v) = putStr("video_decode_mode", v)

    // ── 后处理 ──────────────────────────────────────────────────
    /** 视频下载后写元数据（旁挂 .info.json）。 */
    var videoMetaTagging: Boolean
        get() = bool("video_meta_tag", true)
        set(v) = putBool("video_meta_tag", v)

    /** 全部恢复默认。 */
    fun resetAll() {
        if (::sp.isInitialized) sp.edit().clear().apply()
    }
}

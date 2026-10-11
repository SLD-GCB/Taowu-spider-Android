package com.taowu.backend

import com.taowu.backend.model.PageResolve
import com.taowu.backend.model.Route
import com.taowu.backend.model.Track
import com.taowu.backend.net.MediaHeaders
import com.taowu.backend.net.NetEngine
import com.taowu.backend.parse.MediaUtil
import com.taowu.backend.parse.SiteAdapters
import com.taowu.backend.parse.StaticExtract
import com.taowu.backend.registry.RecipeRegistry
import com.taowu.backend.registry.dedupeTracks
import com.taowu.backend.run.RecipeEngine
import com.taowu.backend.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 解析模式：通用（页内扫描）/ 配方（站点规则）。UI 下拉框切换。 */
enum class ResolveMode { GENERAL, RECIPE }

/**
 * 解析编排。
 *  · GENERAL：通用静态扫描（页内八趟 + iframe/refresh 有界跳转 + 站点适配器）
 *  · RECIPE ：配方引擎（站点规则）
 * 两种模式由调用方显式选择，互不降级。
 */
class MediaResolver(
    private val registry: RecipeRegistry,
    private val engine: RecipeEngine,
    private val net: NetEngine,
) {
    private val maxHops: Int get() = if (AppSettings.hopEnabled) AppSettings.resolveMaxHops else 0

    private class WalkAcc {
        val visited = HashSet<String>()
        val byKey = LinkedHashMap<String, Track>()
        val notes = ArrayList<String>()
        var pageTitle: String = ""
    }

    suspend fun resolve(
        url: String,
        mode: ResolveMode = ResolveMode.GENERAL,
        html0: String? = null,
        finalUrl0: String? = null,
    ): PageResolve = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return@withContext PageResolve(trimmed, Route.NONE, emptyList(), listOf("仅支持 http/https URL"))
        }
        when (mode) {
            ResolveMode.RECIPE -> resolveRecipe(trimmed)
            ResolveMode.GENERAL -> resolveGeneral(trimmed, html0, finalUrl0)
        }
    }

    // ── 配方模式 ────────────────────────────────────────────────
    private fun resolveRecipe(url: String): PageResolve {
        val entry = registry.forUrl(url)
            ?: return PageResolve(url, Route.NONE, emptyList(), listOf("未命中配方（换通用模式试试）"))
        val tracks = try { entry.resolve(url) } catch (e: Exception) { emptyList() }
        if (tracks.isEmpty()) {
            return PageResolve(url, Route.NONE, emptyList(), listOf("配方「${entry.name}」未产出轨道"))
        }
        return PageResolve(url, Route.RECIPE, reindex(dropAds(dedupeTracks(tracks))), listOf("配方：${entry.name}"))
    }

    // ── 通用模式（静态扫描 + 适配器 + iframe 跳转） ─────────────
    private suspend fun resolveGeneral(url: String, html0: String?, finalUrl0: String?): PageResolve {
        val acc = WalkAcc()
        acc.notes.add("通用静态扫描")
        val html = if (html0 != null) html0 else fetchPage(url, url)?.text
        val finalUrl = finalUrl0?.takeIf { it.isNotEmpty() } ?: url
        if (html.isNullOrEmpty()) {
            acc.notes.add("页面获取失败（已重试 ${AppSettings.parseRetry} 次）")
            return PageResolve(url, Route.NONE, emptyList(), acc.notes)
        }
        walkStatic(url, html, finalUrl, 0, acc)
        if (acc.byKey.isEmpty()) {
            acc.notes.add("未发现可下载的视频")
            return PageResolve(url, Route.NONE, emptyList(), acc.notes)
        }
        val title = acc.pageTitle
        val tracks = acc.byKey.values.map { if (it.titleHint.isEmpty() && title.isNotEmpty()) it.copy(titleHint = title) else it }
        return PageResolve(url, Route.STATIC, reindex(dropAds(dedupeTracks(tracks))), acc.notes)
    }

    private suspend fun walkStatic(u: String, html: String?, finalUrl: String, depth: Int, acc: WalkAcc) {
        val k = MediaUtil.canonKey(u)
        if (!acc.visited.add(k)) return
        if (html.isNullOrEmpty()) return
        if (acc.pageTitle.isEmpty()) acc.pageTitle = StaticExtract.extractTitle(html, finalUrl)

        if (AppSettings.staticScanEnabled) {
            val (vc, vnotes) = StaticExtract.extractVideoCandidates(html, finalUrl)
            acc.notes.addAll(vnotes)
            for (c in vc) acc.byKey.putIfAbsent(MediaUtil.canonKey(c.url), c.toTrack("video"))
        }

        // 站点适配器（拿页面 HTML 直接解析）
        addAdapterCandidates(SiteAdapters.apply(finalUrl, html, finalUrl), finalUrl, "站点适配", acc)

        if (acc.byKey.isNotEmpty() || depth >= maxHops) return
        // 下一跳：meta refresh / 播放器 iframe；都没有时——若是 MacCMS 详情页（有 /p/{id}-{线路}-{集} 链接），
        // 跟第一个分集播放页（通用形态：详情页 → 播放页 → m3u8）
        val tgt = StaticExtract.hopTarget(html, finalUrl)
            ?: com.taowu.backend.parse.EpisodePlan.firstMacCmsAnchor(html, finalUrl)
            ?: return
        if (acc.visited.contains(MediaUtil.canonKey(tgt))) return
        val r = fetchPage(tgt, finalUrl) ?: return
        walkStatic(tgt, r.text, r.finalUrl.ifEmpty { tgt }, depth + 1, acc)
    }

    /** 抓页面：按设置重试（失败=null/空文本）并逐次线性退避。 */
    private suspend fun fetchPage(url: String, referer: String): com.taowu.backend.net.TextResponse? {
        val attempts = AppSettings.parseRetry.coerceIn(0, 6) + 1
        val base = AppSettings.parseRetryBackoffMs.coerceIn(0, 5000).toLong()
        var last: com.taowu.backend.net.TextResponse? = null
        for (i in 0 until attempts) {
            val r = try {
                net.getText(url, MediaHeaders.headers(url, referer, "page"), emptyMap(), AppSettings.pageTimeoutSec)
            } catch (e: Exception) {
                null
            }
            if (r != null && !r.text.isNullOrEmpty()) return r
            last = r
            if (i < attempts - 1 && base > 0) kotlinx.coroutines.delay(base * (i + 1))
        }
        return last
    }

    /** 把适配器给出的候选 URL 归一、分类后并入 byKey。 */
    private fun addAdapterCandidates(ad: SiteAdapters.Result, finalUrl: String, src: String, acc: WalkAcc) {
        for (raw in ad.candidates) {
            val u2 = MediaUtil.normalize(raw, finalUrl) ?: continue
            val cls = MediaUtil.classifyVideo(u2) ?: continue
            val mediaClass = if (cls.first == "m3u8" || cls.first == "dash") "" else "video"
            val defaultTitle = if (cls.second == "m3u8") "hls" else cls.second
            acc.byKey.putIfAbsent(
                MediaUtil.canonKey(u2),
                Track(0, mediaClass, cls.first, u2, cls.second, src, finalUrl, titleHint = defaultTitle),
            )
        }
    }

    private fun reindex(tracks: List<Track>): List<Track> =
        tracks.mapIndexed { i, t -> t.copy(index = i + 1) }

    /** 广告过滤：按设置剔除广告域 / 广告流的候选轨道（避免把广告当视频抓/播）。 */
    private fun dropAds(tracks: List<Track>): List<Track> {
        if (!AppSettings.adFilterEnabled) return tracks
        val extra = AdFilter.parseExtra(AppSettings.adFilterExtra)
        return tracks.filterNot { AdFilter.isAd(it.url, extra) }
    }

    private fun com.taowu.backend.parse.Candidate.toTrack(defaultClass: String): Track {
        val mediaClass = if (kind == "m3u8" || kind == "dash") "" else defaultClass
        return Track(
            index = 0, mediaClass = mediaClass, kind = kind, url = url, ext = ext,
            src = src, pageUrl = pageUrl, titleHint = titleHint,
        )
    }
}

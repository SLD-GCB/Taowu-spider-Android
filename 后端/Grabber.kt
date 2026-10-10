package com.taowu.backend.grab

import com.taowu.backend.ResolveMode
import com.taowu.backend.MediaResolver
import com.taowu.backend.crypto.StreamDecrypt
import com.taowu.backend.download.DownloadResult
import com.taowu.backend.download.Downloader
import com.taowu.backend.download.ErrCode
import com.taowu.backend.download.Naming
import com.taowu.backend.media.VideoMeta
import com.taowu.backend.model.PageResolve
import com.taowu.backend.model.Route
import com.taowu.backend.model.Track
import com.taowu.backend.net.MediaHeaders
import com.taowu.backend.net.NetEngine
import com.taowu.backend.net.TextResponse
import com.taowu.backend.parse.EpisodePlan
import com.taowu.backend.parse.MediaUtil
import com.taowu.backend.parse.StaticExtract
import com.taowu.backend.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ================================================================
// 通用视频抓取编排层（移植 video_grab.process_single_page / run_batch / process_series /
//   _download_episode / _find_existing_video / _cand_output_ext / _prune_sibling_dupes /
//   write_metadata_json / probe_m3u8_format）。
// 工作流：贴 URL → 解析候选 → 逐候选下载（命名/打标/去重/断点跳过）→ 会话记录。
// ================================================================

data class GrabRecord(
    val index: Int?,
    val line: Int?,
    val label: String,
    val pageUrl: String,
    val kind: String,
    val url: String,
    val status: String,      // ok | fail | stopped | skipped | unsupported
    val localPath: String,
    val bytesCount: Long,
    val error: String,
    val existing: Boolean = false,
)

data class GrabPage(
    val url: String,
    var title: String,
    var error: String,
    val candidates: List<GrabRecord>,
    val records: MutableList<GrabRecord>,
)

data class BatchSummary(
    val pages: List<GrabPage>,
    val ok: Int,
    val fail: Int,
    val skip: Int,
    val stopped: Int,
    val metaPath: String,
)

data class SeriesResult(
    val mode: String,        // series | none
    val title: String,
    val url: String,
    val reason: String,
    val summary: BatchSummary,
)

class Grabber(
    private val resolver: MediaResolver,
    private val downloader: Downloader,
    private val net: NetEngine,
    /** 日志回调（UI 可设置以显示进度）。 */
    var logger: (String) -> Unit = {},
) {
    private val videoOutExts = listOf("ts", "mp4", "flv", "webm", "mov", "m4v", "mkv")

    /** 单个页面：探测 → 逐个下载全部候选 → 返回记录。 */
    suspend fun processSinglePage(
        url: String,
        videoDir: File,
        mode: ResolveMode,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
    ): GrabPage {
        val page = GrabPage(url, "", "", emptyList(), ArrayList())
        val resp = getText(url, url)
        val html = resp?.text
        val finalUrl = resp?.finalUrl?.takeIf { it.isNotEmpty() } ?: url
        val title = if (!html.isNullOrEmpty()) StaticExtract.extractTitle(html, finalUrl) else url
        page.title = title
        val mmeta = if (!html.isNullOrEmpty()) VideoMeta.extract(html, finalUrl) else VideoMeta.Meta()

        val pr = resolver.resolve(url, mode, html, finalUrl)
        logger(pr.notes.joinToString("；"))
        val tracks = pr.tracks
        if (tracks.isEmpty()) {
            page.error = "未解析到视频(m3u8/直链)"
            logger("$url → ${page.error}")
            return page
        }

        val candidates = tracks.map { GrabRecord(it.index, null, title, it.pageUrl, it.kind, it.url, "pending", "", 0, "") }
        val page2 = GrabPage(url, title, "", candidates, page.records)

        logger("页面解析到 ${tracks.size} 个候选，开始下载：$title")
        val total = tracks.size
        val nameNum = HashMap<String, Int>()
        for ((i, t) in tracks.withIndex()) {
            val num = (nameNum[t.url] ?: 0) + 1
            nameNum[t.url] = num
            val referer = t.pageUrl.ifEmpty { url }
            val ext = when (t.kind) {
                "m3u8" -> if (probeM3u8Format(t.url, referer, t.cookies) == "cmaf") "mp4" else "ts"
                "dash" -> "mp4"
                else -> t.ext.ifEmpty { "mp4" }
            }
            val dest = Naming.destPathFor(videoDir, title, t.url, ext, num)
            logger("[${i + 1}/$total] ${t.kind} 下载中 → ${dest.name}")
            val res = try {
                downloader.download(t, dest, referer = referer, onProgress = { cur, tot ->
                    onProgress(cur, tot, "${t.kind} ${dest.name}")
                })
            } catch (e: Exception) {
                DownloadResult("fail", null, 0, e.message, ErrCode.UNKNOWN)
            }
            val finalPath = res.path ?: ""
            if (res.status == "ok" && res.path != null) {
                if (AppSettings.videoMetaTagging) VideoMeta.tag(File(finalPath), mmeta)
                if (mmeta.coverUrl.isNotEmpty()) {
                    val f = File(finalPath)
                    saveCover(File(f.parentFile, f.nameWithoutExtension + ".jpg"), mmeta.coverUrl, finalUrl)
                }
                logger("   ✓ ${File(finalPath).name} ${Naming.fmtSize(res.bytes)}")
            } else {
                logger("   ✗ ${res.error ?: "下载失败"}")
            }
            page2.records.add(
                GrabRecord(i + 1, null, title, referer, t.kind, t.url, res.status,
                    finalPath, res.bytes, res.error ?: ""),
            )
        }
        return page2
    }

    /** 批量：URL 逐个串行，结束后统一写元数据 JSON。 */
    suspend fun runBatch(
        urls: List<String>,
        videoDir: File,
        mode: ResolveMode,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
    ): BatchSummary {
        val pages = ArrayList<GrabPage>()
        val all = ArrayList<GrabRecord>()
        logger("视频抓取任务开始，共 ${urls.size} 个 URL")
        for ((i, u) in urls.withIndex()) {
            logger("\n[${i + 1}/${urls.size}] 页面：$u")
            val p = processSinglePage(u, videoDir, mode, onProgress)
            pages.add(p)
            all.addAll(p.records)
        }
        val metaPath = writeMetadataJson(videoDir, all)
        val ok = all.count { it.status == "ok" }
        val fail = all.count { it.status == "fail" }
        val skip = all.count { it.status == "skipped" || it.status == "unsupported" }
        val stopped = all.count { it.status == "stopped" }
        logger("全部完成：成功 $ok / 失败 $fail / 跳过 $skip / 停止 $stopped，元数据见 $metaPath")
        return BatchSummary(pages, ok, fail, skip, stopped, metaPath)
    }

    /** 整部：详情页/任一分集页 → 解析分集 → 逐集（多线路故障切换）下载。 */
    suspend fun processSeries(
        seedUrl: String,
        videoDir: File,
        mode: ResolveMode,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
        /** 每集进度：index, label, done, total, bytes, finished。 */
        onEpisode: ((Int, String, Long, Long, Long, Boolean) -> Unit)? = null,
    ): SeriesResult {
        val resp = getText(seedUrl, seedUrl)
        val html = resp?.text ?: ""
        val final = resp?.finalUrl?.takeIf { it.isNotEmpty() } ?: seedUrl
        val plan = EpisodePlan.parse(html, final)
        if (plan.matched == "none" || plan.episodes.isEmpty()) {
            return SeriesResult("none", plan.title, seedUrl, plan.reason.ifEmpty { "未识别到分集列表" }, emptySummary())
        }
        // 过滤"只有 1 集"的不完整线路
        val (planEps, dropped) = EpisodePlan.dropIncompleteLines(plan.episodes)
        if (dropped > 0) logger("已过滤 $dropped 条不完整线路（仅有 1 集）")
        val title = Naming.fsClean(plan.title, 60).ifEmpty { "未命名动漫" }
        val folder = File(videoDir, title).apply { mkdirs() }
        logger("识别整部：${plan.title}（${plan.matched} · ${plan.lines} 线路 · ${plan.episodes.size} 锚点）")

        // 封面：从种子页抽取 og:image/JSON-LD image，存到该部目录
        val seedMeta = if (html.isNotEmpty()) VideoMeta.extract(html, final) else VideoMeta.Meta()
        writeFolderInfo(folder, plan.title, seedMeta.coverUrl)
        saveCover(File(folder, "cover.jpg"), seedMeta.coverUrl, final)

        val byNum = LinkedHashMap<Any, MutableList<EpisodePlan.Episode>>()
        for (e in planEps) {
            val key: Any = e.num ?: ("u:${e.url}")
            byNum.getOrPut(key) { ArrayList() }.add(e)
        }
        val numKeys = byNum.keys.filterIsInstance<Int>().sorted()
        val unkKeys = byNum.keys.filter { it !is Int }
        val targetKeys = numKeys + unkKeys
        val total = targetKeys.size
        logger("共 $total 集进入下载队列，落盘目录：${folder.absolutePath}")

        val records = ArrayList<GrabRecord>()
        val parallel = AppSettings.seriesParallel.coerceIn(1, 8)
        logger("整部并发：$parallel 集同时下载（单 host 由闸门限流）")

        fun taskInfo(i: Int, key: Any): Triple<List<EpisodePlan.Episode>, String, String> {
            val cands = byNum[key]!!.sortedBy { it.line ?: 0 }
            val first = cands.first()
            val num = first.num
            val labelRaw = first.label.ifEmpty { if (num != null) "第%02d集".format(num) else "" }
            var base = Naming.fsClean(labelRaw, 60)
            if (base.isEmpty()) base = if (num != null) "第%02d集".format(num) else "ep%02d".format(i + 1)
            val disp = if (num != null) "第%02d集".format(num) else base
            return Triple(cands, base, disp)
        }

        if (parallel <= 1) {
            // 串行：日志按顺序、易读
            for ((i, key) in targetKeys.withIndex()) {
                val (cands, base, disp) = taskInfo(i, key)
                logger("[${i + 1}/$total] $disp")
                val rec = downloadEpisode(cands, folder, base, mode, true, disp) { d, tt, b ->
                    onEpisode?.invoke(i, disp, d, tt, b, false)
                }
                onEpisode?.invoke(i, disp, 0, 0, 0, true)
                records.add(rec)
                onProgress((i + 1).toLong(), total.toLong(), disp)
            }
        } else {
            // 多集并行（PC: VIDEO_SERIES_PARALLEL）——同 host 的请求会被 Throttle 闸门自动排队限流
            val sem = Semaphore(parallel)
            var finished = 0L
            val lock = Any()
            val done = coroutineScope {
                targetKeys.mapIndexed { i, key ->
                    async {
                        sem.withPermit {
                            val (cands, base, disp) = taskInfo(i, key)
                            logger("[${i + 1}/$total] $disp")
                            val rec = downloadEpisode(cands, folder, base, mode, true, disp) { d, tt, b ->
                                onEpisode?.invoke(i, disp, d, tt, b, false)
                            }
                            onEpisode?.invoke(i, disp, 0, 0, 0, true)
                            synchronized(lock) {
                                finished++
                                onProgress(finished, total.toLong(), disp)
                            }
                            rec
                        }
                    }
                }.awaitAll()
            }
            records.addAll(done)
        }
        val metaPath = writeMetadataJson(folder, records)
        val ok = records.count { it.status == "ok" }
        val fail = records.count { it.status == "fail" }
        val skip = records.count { it.status == "skipped" || it.status == "unsupported" }
        val stopped = records.count { it.status == "stopped" }
        val page = GrabPage(seedUrl, plan.title, "", emptyList(), records)
        return SeriesResult("series", plan.title, seedUrl, "", BatchSummary(listOf(page), ok, fail, skip, stopped, metaPath))
    }

    /** 一集（含多条线路候选）。 */
    data class EpisodeItem(val num: Int?, val label: String, val candidates: List<EpisodePlan.Episode>)

    /** 解析分集列表（供 UI 展示：每集有哪些线路）。返回 (动漫名, 分集列表)。 */
    suspend fun planEpisodes(url: String): Pair<String, List<EpisodeItem>> {
        val resp = getText(url, url)
        val html = resp?.text ?: ""
        val final = resp?.finalUrl?.takeIf { it.isNotEmpty() } ?: url
        val plan = EpisodePlan.parse(html, final)
        val (eps, _) = EpisodePlan.dropIncompleteLines(plan.episodes)
        val byNum = LinkedHashMap<Any, MutableList<EpisodePlan.Episode>>()
        for (e in eps) {
            val k: Any = e.num ?: ("u:${e.url}")
            byNum.getOrPut(k) { ArrayList() }.add(e)
        }
        val keys = byNum.keys.filterIsInstance<Int>().sorted() + byNum.keys.filter { it !is Int }
        val items = ArrayList<EpisodeItem>()
        for (k in keys) {
            val cands = byNum[k]!!.sortedBy { it.line ?: 0 }
            val first = cands.first()
            val label = first.label.ifEmpty { if (first.num != null) "第%02d集".format(first.num) else "未知集" }
            items.add(EpisodeItem(first.num, label, cands))
        }
        return plan.title to items
    }

    /** 一个"链接/线路"选项：线路名 + 该线路的分集。 */
    data class SeriesLineInfo(val line: Int?, val name: String, val episodes: List<EpisodePlan.Episode>)
    data class SeriesPlanInfo(
        val title: String,
        val url: String,
        val lines: List<SeriesLineInfo>,
        val episodeCount: Int,
        /** 被过滤掉的不完整线路数（只有 1 集的线路）。 */
        val droppedLines: Int = 0,
    )

    /** 解析整部并按线路分组：一个线路 = 一个选项（点进去看该线路有多少集）。 */
    suspend fun planSeries(url: String): SeriesPlanInfo {
        val resp = getText(url, url)
        val html = resp?.text ?: ""
        val final = resp?.finalUrl?.takeIf { it.isNotEmpty() } ?: url
        val plan = EpisodePlan.parse(html, final)
        // 过滤"只有 1 集"的不完整线路
        val (eps, dropped) = EpisodePlan.dropIncompleteLines(plan.episodes)
        val byLine = LinkedHashMap<Int?, MutableList<EpisodePlan.Episode>>()
        for (e in eps) byLine.getOrPut(e.line) { ArrayList() }.add(e)
        val lines = byLine.entries
            .sortedBy { it.key ?: Int.MAX_VALUE }
            .map { (ln, list) ->
                SeriesLineInfo(ln, lineName(ln), list.sortedWith(compareBy({ it.num ?: Int.MAX_VALUE }, { it.url })))
            }
        val count = eps.mapNotNull { it.num }.toSortedSet().size
        if (dropped > 0) logger("已过滤 $dropped 条不完整线路（仅有 1 集）")
        return SeriesPlanInfo(plan.title, url, lines, count, dropped)
    }

    /** 线路的分集 → EpisodeItem（每集仅含本线路一个候选），供播放上下文与逐集下载。 */
    fun lineItems(line: SeriesLineInfo): List<EpisodeItem> =
        line.episodes.map { e ->
            EpisodeItem(e.num, e.label.ifEmpty { if (e.num != null) "第%02d集".format(e.num) else "未知集" }, listOf(e))
        }

    private fun lineName(ln: Int?): String {
        if (ln == null) return "默认线路"
        val cn = "一二三四五六七八九十"
        return if (ln in 1..10) "线路${cn[ln - 1]}" else "线路$ln"
    }

    /** 解析某集的一条线路 → 轨道（按线路顺序试，返回首个有结果的）。 */
    suspend fun resolveEpisode(item: EpisodeItem, mode: ResolveMode): List<Track> {
        for (c in item.candidates) {
            val pr = try {
                resolver.resolve(c.url, mode)
            } catch (e: Exception) {
                null
            } ?: continue
            if (pr.tracks.isNotEmpty()) return pr.tracks
        }
        return emptyList()
    }

    /** 下载某一集（落到 videoDir/{动漫名}/{集标签}.{ext}）。 */
    suspend fun downloadEpisodeItem(
        title: String,
        item: EpisodeItem,
        videoDir: File,
        mode: ResolveMode,
        onEp: ((Long, Long, Long) -> Unit)? = null,
    ): GrabRecord {
        val folder = File(videoDir, Naming.fsClean(title, 60).ifEmpty { "未命名动漫" }).apply { mkdirs() }
        val base = Naming.fsClean(item.label, 60)
            .ifEmpty { if (item.num != null) "第%02d集".format(item.num) else "ep" }
        return downloadEpisode(item.candidates, folder, base, mode, true, item.label, onEp)
    }

    /** 逐条线路尝试把某集下载到 folder/{base}.{ext}。 */
    private suspend fun downloadEpisode(
        cands: List<EpisodePlan.Episode>,
        folder: File,
        base: String,
        mode: ResolveMode,
        skipExisting: Boolean,
        disp: String,
        onEp: ((Long, Long, Long) -> Unit)? = null,
    ): GrabRecord {
        var lastCode = ErrCode.UNKNOWN
        var lastErr = ""
        for (cand in cands) {
            val page = cand.url
            logger("   源线路${cand.line ?: "-"}：$page")
            if (skipExisting) {
                findExistingVideo(folder, base)?.let { ex ->
                    logger("   ✓ 已存在，跳过 → ${ex.name}")
                    return GrabRecord(cand.num, cand.line, disp, page, "", "", "ok", ex.absolutePath, ex.length(), "", true)
                }
            }
            val pr = try {
                resolver.resolve(page, mode)
            } catch (e: Exception) {
                PageResolve(page, Route.NONE, emptyList(), emptyList())
            }
            if (pr.tracks.isEmpty()) {
                logger("   ✗ 未解析到视频流，切换线路")
                continue
            }
            for (t in pr.tracks) {
                val ext = candOutputExt(t)
                val dest = File(folder, "$base.$ext")
                var lastBytes = 0L
                var lastDone = 0L
                var lastTotal = 0L
                val res = try {
                    downloader.download(
                        t, dest, referer = t.pageUrl.ifEmpty { page },
                        onProgress = { d, tot ->
                            lastDone = d; lastTotal = tot; onEp?.invoke(d, tot, lastBytes)
                        },
                        onBytes = { b ->
                            lastBytes = b; onEp?.invoke(lastDone, lastTotal, b)
                        },
                    )
                } catch (e: Exception) {
                    DownloadResult("fail", null, 0, e.message, ErrCode.UNKNOWN)
                }
                if (res.status == "ok" && res.path != null) {
                    val real = File(res.path)
                    if (real.absolutePath != dest.absolutePath) dest.delete()
                    pruneSiblingDupes(folder, base, real)
                    val em = episodeMeta(page)
                    if (em.coverUrl.isNotEmpty()) saveCover(File(folder, "cover.jpg"), em.coverUrl, page)
                    if (AppSettings.videoMetaTagging) VideoMeta.tag(real, em)
                    logger("   ✓ ${real.name} ${Naming.fmtSize(res.bytes)}")
                    return GrabRecord(cand.num, cand.line, disp, page, t.kind, t.url, "ok", real.absolutePath, res.bytes, "")
                }
                lastErr = res.error ?: "下载失败"
                lastCode = res.errorCode ?: ErrCode.NETWORK
                dest.delete()
            }
        }
        logger("   ✗ ${lastErr.ifEmpty { "所有线路均下载失败" }}")
        return GrabRecord(null, null, disp, "", "", "", "fail", "", 0, lastErr.ifEmpty { "所有线路均失败" })
    }

    private suspend fun episodeMeta(page: String): VideoMeta.Meta {
        if (page.isEmpty()) return VideoMeta.Meta()
        val t = getText(page, page) ?: return VideoMeta.Meta()
        return VideoMeta.extract(t.text ?: "", page)
    }

    /** 拉取封面图存到 dest（已存在则跳过）。失败静默。 */
    private suspend fun saveCover(dest: File, coverUrl: String, referer: String) {
        if (coverUrl.isEmpty()) return
        if (dest.exists() && dest.length() > 0) return
        val bytes = try {
            net.getBytes(coverUrl, MediaHeaders.headers(coverUrl, referer, "direct"), emptyMap(), 20)
        } catch (e: Exception) {
            null
        }
        if (bytes != null && bytes.isNotEmpty()) {
            try { dest.parentFile?.mkdirs(); dest.writeBytes(bytes) } catch (e: Exception) { /* 忽略 */ }
        }
    }

    /** 该部目录的 info.json（标题 + 封面地址）。 */
    private fun writeFolderInfo(folder: File, title: String, coverUrl: String) {
        try {
            val json = buildJsonObject {
                put("title", title)
                put("cover_url", coverUrl)
            }
            File(folder, "info.json").writeText(json.toString())
        } catch (e: Exception) {
            // 忽略
        }
    }

    // 网络/阻塞调用一律切到 IO 线程：Grabber 的入口方法常被 UI 的 LaunchedEffect /
    // rememberCoroutineScope（主线程）直接调用，若在主线程跑 OkHttp execute() 会抛
    // NetworkOnMainThreadException 并被吞掉（表现为"分集识别为空、只剩第一条轨道"）。
    // 失败（null/空文本）按设置重试并逐次线性退避——缓解"有时候抓不到页面"。
    private suspend fun getText(url: String, referer: String): TextResponse? = withContext(Dispatchers.IO) {
        val attempts = AppSettings.parseRetry.coerceIn(0, 6) + 1
        val base = AppSettings.parseRetryBackoffMs.coerceIn(0, 5000).toLong()
        var last: TextResponse? = null
        for (i in 0 until attempts) {
            val r = try {
                net.getText(url, MediaHeaders.headers(url, referer, "page"), emptyMap(), AppSettings.pageTimeoutSec)
            } catch (e: Exception) {
                null
            }
            if (r != null && !r.text.isNullOrEmpty()) return@withContext r
            last = r
            if (i < attempts - 1 && base > 0) kotlinx.coroutines.delay(base * (i + 1))
        }
        last
    }

    private suspend fun probeM3u8Format(url: String, referer: String, cookies: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val h = MediaHeaders.headers(url, referer, "manifest")
            var txt = net.getRaw(url, h, cookies, AppSettings.pageTimeoutSec) ?: return@withContext ""
            var pl = StreamDecrypt.parseHlsMedia(txt, url)
            if (pl.kind == "master") {
                val best = pl.variants.maxWithOrNull(compareBy({ it.resolution }, { it.bandwidth })) ?: return@withContext ""
                txt = net.getRaw(best.uri, h, cookies, AppSettings.pageTimeoutSec) ?: return@withContext ""
                pl = StreamDecrypt.parseHlsMedia(txt, best.uri)
            }
            if (pl.singleFile || pl.hasMap) "cmaf" else "ts"
        }

    private fun candOutputExt(t: Track): String = when (t.kind) {
        "m3u8" -> "ts"
        "dash" -> "mp4"
        else -> t.ext.ifEmpty { "mp4" }
    }

    private fun findExistingVideo(folder: File, base: String): File? {
        for (ext in videoOutExts) {
            val f = File(folder, "$base.$ext")
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    private fun pruneSiblingDupes(folder: File, base: String, keep: File) {
        for (ext in MediaUtil.VIDEO_EXT_DIRECT + listOf("ts", "m3u8")) {
            val f = File(folder, "$base.$ext")
            if (f.absolutePath != keep.absolutePath && f.exists()) f.delete()
        }
    }

    private fun writeMetadataJson(dir: File, records: List<GrabRecord>): String {
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val target = File(dir, "video_$ts.json")
        val arr = records.map { r ->
            buildJsonObject {
                r.index?.let { put("index", it) } ?: put("index", JsonNull)
                r.line?.let { put("line", it) } ?: put("line", JsonNull)
                put("label", r.label); put("page_url", r.pageUrl)
                put("kind", r.kind); put("url", r.url); put("status", r.status)
                put("local_path", r.localPath); put("bytes", r.bytesCount); put("error", r.error)
            }
        }
        val payload = buildJsonObject {
            put("generated_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("items", JsonArray(arr))
        }
        try {
            target.writeText(payload.toString())
        } catch (e: Exception) {
            logger("元数据写入失败：${e.message}")
        }
        return target.absolutePath
    }

    private fun emptySummary() = BatchSummary(emptyList(), 0, 0, 0, 0, "")
}

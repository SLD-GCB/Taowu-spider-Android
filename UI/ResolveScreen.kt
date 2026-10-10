package com.taowu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taowu.GrabTasks
import com.taowu.backend.BackendContainer
import com.taowu.backend.ResolveMode
import com.taowu.backend.grab.Grabber
import com.taowu.backend.model.PageResolve
import com.taowu.backend.model.Track
import com.taowu.backend.parse.EpisodePlan
import com.taowu.backend.save.SavedSources
import com.taowu.ui.theme.UiColors
import kotlinx.coroutines.launch

private sealed interface ResolveUiState {
    data object Loading : ResolveUiState
    data class Done(val result: PageResolve) : ResolveUiState
    data class Failed(val message: String) : ResolveUiState
}

/**
 * 解析页：
 *  · 命中整部 → 按「链接/线路」列出选项（每项显示该线路多少集），点开看分集，逐项/逐集可 观看/下载；
 *  · 单条页面 → 展示候选轨道，可播放/下载。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveScreen(
    container: BackendContainer,
    url: String,
    mode: ResolveMode = ResolveMode.GENERAL,
    onBack: () -> Unit,
    onPlay: (Track, PlayContext?) -> Unit,
    onSeries: () -> Unit = {},
) {
    var state by remember { mutableStateOf<ResolveUiState>(ResolveUiState.Loading) }
    var series by remember { mutableStateOf<Grabber.SeriesPlanInfo?>(null) }
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    val epStatus = remember { mutableStateMapOf<String, String>() }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val gs by GrabTasks.state.collectAsState()
    var statusText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf("") }

    LaunchedEffect(url, mode) {
        state = ResolveUiState.Loading
        statusText = ""
        savedHint = ""
        epStatus.clear(); expanded.clear()
        val pr: ResolveUiState = try {
            ResolveUiState.Done(container.resolver.resolve(url, mode))
        } catch (e: Exception) {
            ResolveUiState.Failed(e.message ?: "解析异常")
        }
        // 无论成败都尝试识别整部（详情页里没有流，但有多条线路/多集链接）
        val sp = try {
            container.grabber.planSeries(url)
        } catch (e: Exception) {
            null
        }
        series = sp
        state = pr
    }

    // ── 线路/分集操作 ───────────────────────────────────────────
    fun playLine(li: Int, line: Grabber.SeriesLineInfo, ei: Int) {
        if (busy) return
        val items = container.grabber.lineItems(line)
        val item = items.getOrNull(ei) ?: return
        busy = true
        statusText = "解析 ${item.label}…"
        scope.launch {
            val tr = container.grabber.resolveEpisode(item, mode)
            busy = false
            if (tr.isNotEmpty()) {
                statusText = "播放 ${item.label}（kind=${tr[0].kind}）"
                onPlay(tr[0], PlayContext(title(series), url, mode, items, ei))
            } else {
                statusText = "${line.name} ${item.label} 未解析到流"
            }
        }
    }

    fun downloadEpisode(li: Int, line: Grabber.SeriesLineInfo, ei: Int) {
        val items = container.grabber.lineItems(line)
        val item = items.getOrNull(ei) ?: return
        epStatus["$li:$ei"] = "排队中…"
        val ok = GrabTasks.start(context, "line:$url#${line.line}#$li", "${line.name} ${item.label}") { _, _ ->
            epStatus["$li:$ei"] = "下载中…"
            val rec = container.grabber.downloadEpisodeItem(title(series), item, container.downloadDir, mode)
            epStatus["$li:$ei"] = if (rec.status == "ok") "已下载" else "失败"
            if (rec.status == "ok") "已下载：${rec.localPath}" else "失败：${rec.error}"
        }
        if (!ok) statusText = "已有抓取任务在运行"
    }

    fun downloadLine(li: Int, line: Grabber.SeriesLineInfo) {
        val ok = GrabTasks.start(context, "line:$url#${line.line}", line.name) { report, _ ->
            val items = container.grabber.lineItems(line)
            var okCount = 0
            for ((ei, item) in items.withIndex()) {
                report((ei + 1).toLong(), items.size.toLong(), "下载 ${item.label}（${ei + 1}/${items.size}）…")
                epStatus["$li:$ei"] = "下载中…"
                val rec = try {
                    container.grabber.downloadEpisodeItem(title(series), item, container.downloadDir, mode)
                } catch (e: Exception) {
                    com.taowu.backend.grab.GrabRecord(null, line.line, item.label, "", "", "", "fail", "", 0, e.message ?: "失败")
                }
                epStatus["$li:$ei"] = if (rec.status == "ok") "已下载" else "失败"
                if (rec.status == "ok") okCount++
            }
            "${line.name} 下载完成：成功 $okCount / ${items.size}"
        }
        if (!ok) statusText = "已有抓取任务在运行"
    }

    fun startDownload(track: Track) {
        val ok = GrabTasks.start(context, "track:$url#${track.url}", track.titleHint.ifEmpty { "下载" }) { report, _ ->
            val dest = com.taowu.backend.download.Naming.destPathFor(
                container.downloadDir, track.titleHint, track.url, mediaExt(track), track.index,
            )
            val res = container.downloader.download(track, dest, referer = track.pageUrl, onProgress = { d, t ->
                report(d, t, "下载中 $d / $t")
            })
            when (res.status) {
                "ok" -> "已下载：${res.path}（${res.bytes / 1024}KB）"
                "skipped" -> "已跳过：${res.error}"
                "unsupported" -> "不支持：${res.error}"
                else -> "下载失败：${res.error}"
            }
        }
        if (!ok) statusText = "已有抓取任务在运行"
    }

    val curTracks = (state as? ResolveUiState.Done)?.result?.tracks ?: emptyList()
    val saveEpisodes = toSavedEpisodes(series)
    // 是否"集数齐全"（按页面实际情况判断，见 SavedSources.isComplete）
    val saveComplete = SavedSources.isComplete(saveEpisodes, curTracks.isNotEmpty(), series?.title ?: "")
    val canSave = url.startsWith("http") && saveComplete

    fun saveSource() {
        if (!canSave) return
        val title = com.taowu.backend.download.Naming.bookTitle(
            series?.title?.takeIf { it.isNotEmpty() }
                ?: curTracks.firstOrNull()?.titleHint?.takeIf { it.isNotEmpty() }
                ?: url
        )
        // 单集（仅 1 集）不写进分集清单，只有直链轨时只存轨
        val epsToSave = if (saveEpisodes.size >= 2) saveEpisodes else if (saveEpisodes.size == 1) saveEpisodes else emptyList()
        // 有分集时**不再存那条"顺手抓到的 m3u8"**（那只是第 1 集的流，属于混入的 HLS）
        val tracksToSave = if (saveEpisodes.isNotEmpty()) emptyList() else curTracks.map {
            SavedSources.SavedTrack(it.kind, it.url, it.ext, it.mediaClass, it.titleHint, it.pageUrl)
        }
        val item = SavedSources.Item(
            id = url,
            title = title,
            pageUrl = url,
            savedAt = System.currentTimeMillis(),
            mode = if (mode == ResolveMode.RECIPE) "RECIPE" else "GENERAL",
            tracks = tracksToSave,
            episodes = epsToSave,
        )
        val ok = SavedSources.add(context, item)
        savedHint = if (ok) "已保存 ✓" else "保存失败"
        statusText = if (ok) "已保存解析源：$title（可到「内容 → 已解析」在线看）" else "保存解析源失败"
    }

    val isSeries = series?.let { it.lines.size > 1 || it.episodeCount > 1 } == true

    Scaffold(
        containerColor = UiColors.bg,
        topBar = {
            TopAppBar(
                title = { Text(if (isSeries) "整部 · 线路" else "解析结果") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← 返回") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = UiColors.surface,
                    titleContentColor = UiColors.text,
                    navigationIconContentColor = UiColors.text,
                ),
            )
        },
        bottomBar = {
            // 固定底栏：不随上方集数滑动
            Column(Modifier.fillMaxWidth().background(UiColors.bg)) {
                HorizontalDivider(color = UiColors.surface)
                if (url.startsWith("http") && !saveComplete) {
                    Text(
                        "该页只解析出第 1 集（疑似没抓全），不保存解析源",
                        style = MaterialTheme.typography.bodySmall,
                        color = UiColors.error,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
                Button(
                    onClick = { saveSource() },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) { Text(if (savedHint.isNotEmpty()) savedHint else "保存解析源") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(url, style = MaterialTheme.typography.bodySmall, color = UiColors.muted)
            // 只显示"本页发起"的任务状态，避免上一页残留串台
            val mine = gs.key.startsWith("line:$url") || gs.key.startsWith("track:$url")
            val shown = (if (mine) gs.text else "").ifEmpty { statusText }
            if (shown.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        shown,
                        style = MaterialTheme.typography.bodySmall,
                        color = UiColors.link,
                        modifier = Modifier.weight(1f),
                    )
                    if (gs.active) {
                        TextButton(onClick = { GrabTasks.stop() }) { Text("停止", color = UiColors.error) }
                    }
                }
            }

            if (isSeries) {
                val sp = series!!
                LinesSection(
                    plan = sp,
                    expanded = expanded,
                    epStatus = epStatus,
                    busy = busy || (mine && gs.active),
                    onToggle = { li -> expanded[li] = !(expanded[li] ?: false) },
                    onWatchLine = { li, line -> playLine(li, line, 0) },
                    onDownloadLine = { li, line -> downloadLine(li, line) },
                    onWatchEp = { li, line, ei -> playLine(li, line, ei) },
                    onDownloadEp = { li, line, ei -> downloadEpisode(li, line, ei) },
                    onSeries = onSeries,
                )
            } else {
                when (val s = state) {
                    is ResolveUiState.Loading -> LoadingBlock()
                    is ResolveUiState.Failed -> Text("解析失败：${s.message}", color = UiColors.error)
                    is ResolveUiState.Done -> {
                        val pr = s.result
                        Text(
                            "route = ${pr.route}　·　${pr.notes.joinToString("；")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = UiColors.muted,
                        )
                        if (pr.tracks.isEmpty()) {
                            Text("未发现可播放的音频/视频（若为详情页，试试「整部抓取」或「分集列表」）", color = UiColors.error)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(pr.tracks) { t ->
                                    TrackRow(
                                        track = t,
                                        downloading = gs.active,
                                        onPlay = { onPlay(t, null) },
                                        onDownload = { startDownload(t) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun title(sp: Grabber.SeriesPlanInfo?): String =
    sp?.title?.ifEmpty { "" } ?: ""

@Composable
private fun LoadingBlock() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("解析中…", color = UiColors.muted, modifier = Modifier.padding(top = 12.dp))
    }
}

/** 线路选项列表：每个线路一项（显示集数），点开看分集。 */
@Composable
private fun LinesSection(
    plan: Grabber.SeriesPlanInfo,
    expanded: Map<Int, Boolean>,
    epStatus: Map<String, String>,
    busy: Boolean,
    onToggle: (Int) -> Unit,
    onWatchLine: (Int, Grabber.SeriesLineInfo) -> Unit,
    onDownloadLine: (Int, Grabber.SeriesLineInfo) -> Unit,
    onWatchEp: (Int, Grabber.SeriesLineInfo, Int) -> Unit,
    onDownloadEp: (Int, Grabber.SeriesLineInfo, Int) -> Unit,
    onSeries: () -> Unit,
) {
    Text(
        com.taowu.backend.download.Naming.bookTitle(plan.title).ifEmpty { plan.url } +
            "　·　${plan.lines.size} 个选项　·　共 ${plan.episodeCount} 集" +
            if (plan.droppedLines > 0) "（已过滤 ${plan.droppedLines} 条仅有 1 集的不完整线路）" else "",
        color = UiColors.text,
        fontWeight = FontWeight.SemiBold,
    )
    TextButton(onClick = onSeries, enabled = !busy) { Text("整部下载（全部线路）") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(plan.lines) { li, line ->
            Card(
                colors = CardDefaults.cardColors(containerColor = UiColors.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(li) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                line.name,
                                color = UiColors.text,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${line.episodes.size} 集　·　${if (expanded[li] == true) "收起" else "展开看分集"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = UiColors.muted,
                            )
                        }
                        TextButton(onClick = { onWatchLine(li, line) }, enabled = !busy) { Text("观看") }
                        TextButton(onClick = { onDownloadLine(li, line) }, enabled = !busy) { Text("下载") }
                    }
                    if (expanded[li] == true) {
                        HorizontalDivider(color = UiColors.bg)
                        for ((ei, ep) in line.episodes.withIndex()) {
                            val st = epStatus["$li:$ei"]
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    ep.label.ifEmpty { "第${ei + 1}集" },
                                    color = UiColors.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (st != null) {
                                    Text(
                                        st,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (st == "已下载") UiColors.link else UiColors.error,
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                }
                                TextButton(onClick = { onWatchEp(li, line, ei) }, enabled = !busy) { Text("观看") }
                                TextButton(onClick = { onDownloadEp(li, line, ei) }, enabled = !busy) { Text("下载") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    downloading: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = UiColors.surface),
        modifier = Modifier.fillMaxWidth().clickable { onPlay() },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                track.titleHint.ifEmpty { "（无标题）" },
                fontWeight = FontWeight.SemiBold,
                color = UiColors.text,
            )
            Text(
                "#${track.index}　${track.mediaClass.ifEmpty { "清单" }}　kind=${track.kind}　ext=${track.ext.ifEmpty { "-" }}",
                style = MaterialTheme.typography.bodySmall,
                color = UiColors.muted,
            )
            if (track.note.isNotEmpty()) {
                Text(track.note, style = MaterialTheme.typography.bodySmall, color = UiColors.muted)
            }
            Text(track.url, style = MaterialTheme.typography.bodySmall, color = UiColors.link)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDownload, enabled = !downloading) {
                    Text(if (downloading) "下载中…" else "下载")
                }
                TextButton(onClick = onPlay) { Text("播放") }
            }
        }
    }
}

private fun mediaExt(track: Track): String = when {
    // 清单类必须以落盘容器为准：m3u8→.ts、dash→.mp4（track.ext 对清单是 "m3u8"/"mpd"，不能用）
    track.kind == "m3u8" -> "ts"
    track.kind == "dash" -> "mp4"
    track.ext.isNotEmpty() -> track.ext
    else -> "mp4"
}

/** 整部分集计划 → 存档分集（按集号归并各线路候选）。 */
private fun toSavedEpisodes(sp: Grabber.SeriesPlanInfo?): List<SavedSources.SavedEpisode> {
    if (sp == null) return emptyList()
    val byNum = LinkedHashMap<Int?, MutableList<EpisodePlan.Episode>>()
    for (line in sp.lines) for (e in line.episodes) byNum.getOrPut(e.num) { ArrayList() }.add(e)
    return byNum.entries
        .sortedBy { it.key ?: Int.MAX_VALUE }
        .map { (num, eps) ->
            val label = eps.first().label.ifEmpty { if (num != null) "第%02d集".format(num) else "集" }
            SavedSources.SavedEpisode(label, num, eps.map { it.url }.distinct())
        }
}

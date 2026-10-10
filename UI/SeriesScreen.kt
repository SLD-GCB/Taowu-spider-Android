package com.taowu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.taowu.ui.theme.UiColors

/**
 * 整部抓取：先列出该番全部集数（可上下滑动），底部【整部抓取】按钮固定；
 * 点按钮后在**前台服务**里逐集下载（退到后台也继续），列表与通知实时显示进度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    container: BackendContainer,
    url: String,
    mode: ResolveMode,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val st by GrabTasks.state.collectAsState()

    var title by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<Grabber.EpisodeItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var hasRun by remember { mutableStateOf(false) }
    var localMsg by remember { mutableStateOf("") }

    // 进页先识别整部 → 列出全部集
    LaunchedEffect(url, mode) {
        loading = true
        val (t, list) = container.grabber.planEpisodes(url)
        title = t
        items = list
        loading = false
    }

    // 只有"当前这一部"的任务进度才用（避免上一部的残留进度串到下一部）
    val mine = st.key == url
    val running = st.active && mine
    val doneCount = if (mine) st.done.toInt() else 0

    fun start() {
        if (running) return
        hasRun = true
        localMsg = ""
        val ok = GrabTasks.start(context, url, com.taowu.backend.download.Naming.bookTitle(title).ifEmpty { url }) { report, log ->
            container.grabber.logger = log
            val r = container.grabber.processSeries(
                url, container.downloadDir, mode,
                onProgress = report,
                onEpisode = { i, label, d, t, b, fin -> GrabTasks.reportEpisode(i, label, d, t, b, fin) },
            )
            if (r.mode == "none") "未识别到分集：${r.reason}"
            else "完成：成功 ${r.summary.ok} / 失败 ${r.summary.fail} / 跳过 ${r.summary.skip}"
        }
        if (!ok) {
            localMsg = "已有抓取任务在运行"
            hasRun = false
        }
    }

    Scaffold(
        containerColor = UiColors.bg,
        topBar = {
            TopAppBar(
                title = { Text("整部抓取") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← 返回") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = UiColors.surface,
                    titleContentColor = UiColors.text,
                    navigationIconContentColor = UiColors.text,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── 顶部信息（不滚动） ──
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(url, style = MaterialTheme.typography.bodySmall, color = UiColors.muted, maxLines = 1)
                Text(
                    (com.taowu.backend.download.Naming.bookTitle(title).ifEmpty { "（识别中…）" }) +
                        (if (items.isEmpty()) "" else "　·　共 ${items.size} 集" + (if (hasRun) "（已完成 $doneCount）" else "")),
                    color = UiColors.text,
                    fontWeight = FontWeight.SemiBold,
                )
                val msg = (if (mine) st.text else "").ifEmpty { localMsg }
                if (msg.isNotEmpty()) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = UiColors.link, maxLines = 3)
                }
            }

            // ── 中间：分集列表（可上下滑动） ──
            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("识别分集中…", color = UiColors.muted)
                }
            } else if (items.isEmpty()) {
                Text(
                    "未识别到分集列表（试试换模式，或贴分集播放页）",
                    color = UiColors.error,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(items) { idx, it ->
                        val ep = if (mine) st.eps[idx] else null
                        val stat = when {
                            ep?.finished == true -> "✓ 已下载"
                            ep != null && (ep.done > 0 || ep.bytes > 0) -> epProgressText(ep)
                            mine && running && idx < doneCount -> "✓ 已下载"
                            else -> "${it.candidates.size} 条线路"
                        }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = UiColors.surface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(it.label.ifEmpty { "第${idx + 1}集" }, color = UiColors.text, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        stat,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (stat.startsWith("✓") || stat == "下载中…") UiColors.link else UiColors.muted,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 底部：整部抓取 / 停止（固定不动） ──
            HorizontalDivider(color = UiColors.surface)
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (st.active) {
                    // 有任务在跑 → 随时可停（协作式取消）
                    Button(
                        onClick = { GrabTasks.stop() },
                        colors = ButtonDefaults.buttonColors(containerColor = UiColors.error),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            when {
                                st.text == "正在停止…" -> "正在停止…"
                                mine -> "停止抓取（$doneCount/${items.size}）"
                                else -> "停止抓取"
                            }
                        )
                    }
                } else {
                    Button(
                        onClick = { start() },
                        enabled = !loading && items.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (hasRun) "重新整部抓取" else "整部抓取")
                    }
                }
            }
        }
    }
}

/** 单集进度文案：百分比 · 已下载大小 · 速度。 */
private fun epProgressText(e: GrabTasks.EpStat): String {
    val pct = if (e.total > 0) " ${(e.done * 100 / e.total).toInt()}%" else ""
    val size = fmtSize(e.bytes)
    val sp = if (e.speed > 0) " · ${fmtSize(e.speed.toLong())}/s" else ""
    return "下载中$pct · $size$sp"
}

private fun fmtSize(n: Long): String = when {
    n >= 1024L * 1024 -> "%.1fMB".format(n / 1024.0 / 1024.0)
    n >= 1024 -> "%.1fKB".format(n / 1024.0)
    else -> "${n}B"
}


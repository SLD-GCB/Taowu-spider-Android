package com.taowu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taowu.GrabTasks
import com.taowu.backend.BackendContainer
import com.taowu.backend.ResolveMode
import com.taowu.backend.grab.Grabber
import com.taowu.backend.model.Track
import com.taowu.ui.theme.UiColors
import kotlinx.coroutines.launch

/** 分集列表：解析详情页 → 列出每集（及可用线路数），支持逐集播放/下载、整部下载。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodesScreen(
    container: BackendContainer,
    url: String,
    mode: ResolveMode,
    onBack: () -> Unit,
    onPlay: (Track, PlayContext) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val gs by GrabTasks.state.collectAsState()
    var title by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<Grabber.EpisodeItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(url, mode) {
        loading = true
        status = ""
        val (t, list) = container.grabber.planEpisodes(url)
        title = t
        items = list
        loading = false
    }

    fun playEp(index: Int, item: Grabber.EpisodeItem) {
        if (busy) return
        busy = true
        status = "解析 ${item.label}…"
        scope.launch {
            val tracks = container.grabber.resolveEpisode(item, mode)
            busy = false
            if (tracks.isNotEmpty()) {
                status = "播放 ${item.label}（kind=${tracks[0].kind}）"
                onPlay(tracks[0], PlayContext(title.ifEmpty { url }, url, mode, items, index))
            } else {
                status = "${item.label} 未解析到流"
            }
        }
    }

    fun downloadEp(item: Grabber.EpisodeItem) {
        val ok = GrabTasks.start(context, "ep:$url#${item.label}", item.label) { _, _ ->
            val rec = container.grabber.downloadEpisodeItem(title, item, container.downloadDir, mode)
            if (rec.status == "ok") "已下载：${rec.localPath}" else "失败：${rec.error}"
        }
        if (!ok) status = "已有抓取任务在运行"
    }

    Scaffold(
        containerColor = UiColors.bg,
        topBar = {
            TopAppBar(
                title = { Text("分集列表") },
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title.ifEmpty { url }, fontWeight = FontWeight.SemiBold, color = UiColors.text)
            Text(
                when (mode) {
                    ResolveMode.GENERAL -> "模式：通用"
                    ResolveMode.RECIPE -> "模式：配方"
                } + "　共 ${items.size} 集",
                style = MaterialTheme.typography.bodySmall, color = UiColors.muted,
            )
            // 只显示"本页发起"的任务状态，避免上一页残留串台
            val mine = gs.key.startsWith("ep:$url")
            val shown = (if (mine) gs.text else "").ifEmpty { status }
            if (shown.isNotEmpty()) Text(shown, style = MaterialTheme.typography.bodySmall, color = UiColors.link)

            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text("解析分集中…", color = UiColors.muted)
                }
            } else if (items.isEmpty()) {
                Text("未识别到分集列表（试试换模式，或贴分集播放页）", color = UiColors.error)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(items) { idx, it ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = UiColors.surface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(it.label, color = UiColors.text, fontWeight = FontWeight.SemiBold)
                                    Text("${it.candidates.size} 条线路", style = MaterialTheme.typography.bodySmall, color = UiColors.muted)
                                }
                                TextButton(onClick = { playEp(idx, it) }, enabled = !busy) { Text("播放") }
                                TextButton(onClick = { downloadEp(it) }, enabled = !busy) { Text("下载") }
                            }
                        }
                    }
                }
            }
        }
    }
}

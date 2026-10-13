package com.taowu.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taowu.GrabTasks
import com.taowu.backend.BackendContainer
import com.taowu.backend.ResolveMode
import com.taowu.backend.download.Naming
import com.taowu.backend.grab.Grabber
import com.taowu.backend.model.Track
import com.taowu.backend.parse.EpisodePlan
import com.taowu.backend.save.SavedSources
import com.taowu.ui.theme.UiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val IMG_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/** 一部（一个下载目录）/ 单个文件。 */
private data class ContentGroup(val name: String, val dir: File, val files: List<File>)

/**
 * 内容页，分两个页签：
 *  · 已下载：本地文件（整部 → 分集，可离线看）；
 *  · 已解析：保存下来的解析源（在线看，点进去即播）。
 */
@Composable
fun ContentScreen(container: BackendContainer, onPlay: (Track, PlayContext?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    var pendingDelSrc by remember { mutableStateOf<SavedSources.Item?>(null) }
    var status by remember { mutableStateOf("") }
    var pendingExportFile by remember { mutableStateOf<File?>(null) }
    var pendingExportGroup by remember { mutableStateOf<ContentGroup?>(null) }

    // 导出·单文件：系统「另存为」（按扩展名给对 mime，保留原文件名）
    val exportFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        val src = pendingExportFile ?: return@rememberLauncherForActivityResult
        pendingExportFile = null
        val uri = res.data?.data
        if (res.resultCode != Activity.RESULT_OK || uri == null) {
            status = "已取消导出"
            return@rememberLauncherForActivityResult
        }
        status = "导出中…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    } ?: error("无法写入")
                }.isSuccess
            }
            status = if (ok) "已导出：${src.name}（${fmtSize(src.length())}）" else "导出失败：${src.name}"
        }
    }

    // 导出·整部：选一个目录，把全部集复制进去
    val exportDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val g = pendingExportGroup ?: return@rememberLauncherForActivityResult
        pendingExportGroup = null
        if (uri == null) {
            status = "已取消导出"
            return@rememberLauncherForActivityResult
        }
        status = "导出中…"
        scope.launch {
            val (okCount, failCount) = withContext(Dispatchers.IO) { copyGroupToTree(context, uri, g) }
            status = "已导出 $okCount 集" + if (failCount > 0) "（失败 $failCount）" else ""
        }
    }

    fun exportFile(f: File) {
        pendingExportFile = f
        exportFileLauncher.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeOfName(f.name)
                putExtra(Intent.EXTRA_TITLE, f.name)
            },
        )
    }

    fun exportGroup(g: ContentGroup) {
        pendingExportGroup = g
        exportDirLauncher.launch(null)
    }

    val dir = container.downloadDir
    val (groups, loose) = remember(refresh) { scanContent(dir) }
    val saved = remember(refresh) { SavedSources.load(context) }
    val gt by GrabTasks.state.collectAsState()

    // 抓取结束 → 自动刷新列表（只在"从进行中变为结束"时刷一次，避免每次进页面都重扫）
    var wasActive by remember { mutableStateOf(false) }
    LaunchedEffect(gt.active) {
        if (gt.active) {
            wasActive = true
        } else if (wasActive) {
            wasActive = false
            refresh++
        }
    }

    fun modeOf(item: SavedSources.Item) =
        if (item.mode == "RECIPE") ResolveMode.RECIPE else ResolveMode.GENERAL

    fun playSavedTrack(item: SavedSources.Item, t: SavedSources.SavedTrack) {
        onPlay(
            Track(0, t.mediaClass, t.kind, t.url, t.ext, "saved", t.pageUrl, titleHint = t.titleHint),
            null,
        )
    }

    fun playSavedEpisode(item: SavedSources.Item, idx: Int) {
        val eps = item.episodes
        val items = eps.map { e ->
            Grabber.EpisodeItem(e.num, e.label, e.candidates.map { u -> EpisodePlan.Episode(e.num, e.label, u, null) })
        }
        val target = items.getOrNull(idx) ?: return
        status = "解析 ${target.label}…"
        scope.launch {
            val tr = try {
                container.grabber.resolveEpisode(target, modeOf(item))
            } catch (e: Exception) {
                emptyList()
            }
            status = if (tr.isNotEmpty()) {
                "播放 ${target.label}"
            } else {
                "${target.label} 未解析到流"
            }
            if (tr.isNotEmpty()) {
                onPlay(tr[0], PlayContext(item.title, item.pageUrl, modeOf(item), items, idx))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(UiColors.bg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
        ) {
            Text(
                "内容",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = UiColors.text,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { refresh++ }) { Text("刷新") }
        }

        TabRow(
            selectedTabIndex = tab,
            containerColor = UiColors.surface,
            contentColor = UiColors.text,
        ) {
            listOf("已下载", "已解析").forEachIndexed { i, name ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i; status = "" },
                    text = {
                        Text(
                            name + if (i == 0) "（${groups.size + loose.size}）" else "（${saved.size}）",
                            color = if (tab == i) UiColors.link else UiColors.muted,
                            fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                )
            }
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = UiColors.link, modifier = Modifier.padding(top = 8.dp))
            }

            if (tab == 0) {
                // ── 已下载（本地） ──
                // 实时抓取进度：只显示在「已下载」这一栏
                if (gt.active) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = UiColors.surface),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "正在下载：${gt.title}",
                                color = UiColors.text,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "已完成 ${gt.done}/${gt.total}" + if (gt.text.isNotEmpty()) "　·　${gt.text}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = UiColors.link,
                                maxLines = 1,
                            )
                            for (e in gt.eps.entries.filter { !it.value.finished }.sortedBy { it.key }.map { it.value }) {
                                Text(
                                    "　${e.label}　${epProgress(e)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = UiColors.muted,
                                    maxLines = 1,
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(onClick = { GrabTasks.stop() }, modifier = Modifier.weight(1f)) {
                                    Text("停止", color = UiColors.error)
                                }
                            }
                        }
                    }
                }
                if (groups.isEmpty() && loose.isEmpty()) {
                    if (!gt.active) EmptyHint("暂无下载内容", "在「捕获」里解析后点「下载」，整部会归到各自目录里。")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        items(groups, key = { it.dir.absolutePath }) { g ->
                            val open = g.dir.absolutePath in expanded
                            Card(
                                colors = CardDefaults.cardColors(containerColor = UiColors.surface),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                expanded = if (open) expanded - g.dir.absolutePath
                                                else expanded + g.dir.absolutePath
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Cover(findCover(g.dir), Modifier.width(52.dp).height(72.dp))
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                            Text(Naming.bookTitle(g.name), color = UiColors.text, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "${g.files.size} 集 · ${fmtSize(g.files.sumOf { it.length() })}" +
                                                    if (open) " · 收起" else " · 点开看全部",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = UiColors.muted,
                                            )
                                        }
                                    }
                                    if (open) {
                                        for (f in g.files) {
                                            EpisodeRow(
                                                label = f.nameWithoutExtension,
                                                sub = fmtSize(f.length()),
                                                onPlay = {
                                                    val i = g.files.indexOf(f).coerceAtLeast(0)
                                                    onPlay(localTrack(f), PlayContext.ofLocal(g.name, g.files, i))
                                                },
                                                onDelete = { pendingDelete = f },
                                            )
                                        }
                                    }
                                    // 文字在上、按钮在下（对齐大司农传输页卡片）
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OutlinedButton(onClick = {
                                            val first = g.files.firstOrNull() ?: return@OutlinedButton
                                            onPlay(localTrack(first), PlayContext.ofLocal(g.name, g.files, 0))
                                        }, modifier = Modifier.weight(1f)) { Text("观看") }
                                        OutlinedButton(
                                            onClick = { exportGroup(g) },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("导出") }
                                        OutlinedButton(
                                            onClick = { pendingDelete = g.dir },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("删除", color = UiColors.error) }
                                    }
                                }
                            }
                        }
                        items(loose, key = { it.absolutePath }) { f ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = UiColors.surface),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onPlay(localTrack(f), null) }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Cover(findCover(f.parentFile, f.nameWithoutExtension), Modifier.width(52.dp).height(72.dp))
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                            Text(f.name, color = UiColors.text, fontWeight = FontWeight.SemiBold)
                                            Text(fmtSize(f.length()), style = MaterialTheme.typography.bodySmall, color = UiColors.muted)
                                        }
                                    }
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OutlinedButton(
                                            onClick = { exportFile(f) },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("导出") }
                                        OutlinedButton(
                                            onClick = { pendingDelete = f },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("删除", color = UiColors.error) }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // ── 已解析（在线） ──
                if (saved.isEmpty()) {
                    EmptyHint("暂无解析源", "在「捕获 → 解析」结果页点底部「保存解析源」，就会出现在这里。")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        items(saved, key = { it.id }) { item ->
                            val key = "s:" + item.id
                            val open = key in expanded
                            Card(
                                colors = CardDefaults.cardColors(containerColor = UiColors.surface),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                expanded = if (open) expanded - key else expanded + key
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                Naming.bookTitle(item.title).ifEmpty { item.pageUrl },
                                                color = UiColors.text,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                summaryOf(item) + " · " + fmtTime(item.savedAt) +
                                                    if (open) " · 收起" else " · 点开看明细",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = UiColors.muted,
                                            )
                                        }
                                    }
                                    if (open) {
                                        if (item.episodes.isNotEmpty()) {
                                            for ((i, e) in item.episodes.withIndex()) {
                                                EpisodeRow(
                                                    label = e.label.ifEmpty { "第${i + 1}集" },
                                                    sub = "${e.candidates.size} 条线路",
                                                    onPlay = { playSavedEpisode(item, i) },
                                                    onDelete = null,
                                                )
                                            }
                                        }
                                        if (item.tracks.isNotEmpty()) {
                                            for (t in item.tracks) {
                                                EpisodeRow(
                                                    label = t.titleHint.ifEmpty { "${t.kind} 直链" },
                                                    sub = "${t.kind} · ${t.ext.ifEmpty { "-" }}",
                                                    onPlay = { playSavedTrack(item, t) },
                                                    onDelete = null,
                                                )
                                            }
                                        }
                                    }
                                    // 文字在上、按钮在下（对齐大司农传输页卡片）
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OutlinedButton(onClick = {
                                            // 有分集优先按分集播（在线解析该集）；否则播存档直链
                                            if (item.episodes.isNotEmpty()) playSavedEpisode(item, 0)
                                            else item.tracks.firstOrNull()?.let { playSavedTrack(item, it) }
                                        }, modifier = Modifier.weight(1f)) { Text("观看") }
                                        OutlinedButton(
                                            onClick = { pendingDelSrc = item },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("删除", color = UiColors.error) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除下载（整部/单集）
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (target.isDirectory) "删除整部？" else "删除该集？") },
            text = {
                Text(
                    if (target.isDirectory) "将删除「${target.name}」及其中全部集（不可恢复）。"
                    else "将删除「${target.name}」（不可恢复）。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (target.isDirectory) target.deleteRecursively() else target.delete()
                    pendingDelete = null
                    refresh++
                }) { Text("删除", color = UiColors.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    // 删除解析源
    pendingDelSrc?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelSrc = null },
            title = { Text("删除该解析源？") },
            text = { Text("将删除「${Naming.bookTitle(item.title).ifEmpty { item.pageUrl }}」（只删存档，不影响已下载文件）。") },
            confirmButton = {
                TextButton(onClick = {
                    SavedSources.delete(context, item.id)
                    pendingDelSrc = null
                    status = "已删除：${item.title}"
                    refresh++
                }) { Text("删除", color = UiColors.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelSrc = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EmptyHint(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = UiColors.muted)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = UiColors.muted)
    }
}

@Composable
private fun EpisodeRow(label: String, sub: String, onPlay: () -> Unit, onDelete: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = UiColors.text, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = UiColors.muted)
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text("删除", color = UiColors.error) }
        }
    }
}

@Composable
private fun Cover(file: File?, modifier: Modifier = Modifier) {
    val bmp = rememberLocalImage(file)
    Box(
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(UiColors.bg),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("无封面", style = MaterialTheme.typography.labelSmall, color = UiColors.muted)
        }
    }
}

@Composable
private fun rememberLocalImage(file: File?): ImageBitmap? {
    var bmp by remember(file?.absolutePath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file?.absolutePath) {
        bmp = withContext(Dispatchers.IO) {
            if (file != null && file.exists() && file.length() > 0) {
                try {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                } catch (e: Exception) {
                    null
                }
            } else null
        }
    }
    return bmp
}

private fun summaryOf(item: SavedSources.Item): String {
    val parts = ArrayList<String>()
    if (item.tracks.isNotEmpty()) parts.add("${item.tracks.size} 条直链")
    if (item.episodes.isNotEmpty()) parts.add("${item.episodes.size} 集")
    return if (parts.isEmpty()) "空" else parts.joinToString(" · ")
}

private fun fmtTime(ms: Long): String =
    if (ms <= 0) "" else SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))

/** 单集进度文案：百分比 · 已下载大小 · 速度。 */
private fun epProgress(e: GrabTasks.EpStat): String {
    val pct = if (e.total > 0) " ${(e.done * 100 / e.total).toInt()}%" else ""
    val sp = if (e.speed > 0) " · ${fmtSize(e.speed.toLong())}/s" else ""
    return "下载中$pct · ${fmtSize(e.bytes)}$sp"
}

/** 找封面：优先 cover.jpg，其次目录里任意图片；loose 传 stem 时优先 <stem>.jpg。 */
private fun findCover(dir: File, stem: String? = null): File? {
    if (stem != null) {
        val f = File(dir, "$stem.jpg")
        if (f.exists() && f.length() > 0) return f
    }
    val c = File(dir, "cover.jpg")
    if (c.exists() && c.length() > 0) return c
    return dir.listFiles()?.firstOrNull { it.isFile && it.length() > 0 && it.extension.lowercase() in IMG_EXTS }
}

/** 扫描下载目录 → (整部目录组, 根目录散文件)。目录内有可播放文件才算一部。 */
private fun scanContent(dir: File): Pair<List<ContentGroup>, List<File>> {
    val groups = ArrayList<ContentGroup>()
    val loose = ArrayList<File>()
    dir.listFiles()?.forEach { f ->
        if (f.isDirectory) {
            val files = f.listFiles()
                ?.filter { it.isFile && it.length() > 0 && isPlayableName(it.name) }
                ?.sortedWith(episodeOrder)
                ?: emptyList()
            if (files.isNotEmpty()) groups.add(ContentGroup(f.name, f, files))
        } else if (f.isFile && f.length() > 0 && isPlayableName(f.name)) {
            loose.add(f)
        }
    }
    return groups.sortedBy { it.name } to loose.sortedByDescending { it.lastModified() }
}

private val episodeOrder = Comparator<File> { a, b -> naturalCompare(a.name, b.name) }

private fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var x = i
            while (x < a.length && a[x].isDigit()) x++
            var y = j
            while (y < b.length && b[y].isDigit()) y++
            val na = a.substring(i, x).trimStart('0').ifEmpty { "0" }
            val nb = b.substring(j, y).trimStart('0').ifEmpty { "0" }
            val c = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
            if (c != 0) return c
            i = x
            j = y
        } else {
            if (ca != cb) return ca.compareTo(cb)
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

private fun fmtSize(n: Long): String = when {
    n >= 1024L * 1024 -> "%.1fMB".format(n / 1024.0 / 1024.0)
    n >= 1024 -> "%.1fKB".format(n / 1024.0)
    else -> "${n}B"
}

private fun isPlayableName(name: String): Boolean {
    val n = name.lowercase()
    if (n.endsWith(".part") || n.endsWith(".idx") || n.endsWith(".dl") || n.contains(".rng_")) return false
    if (n.endsWith(".json")) return false
    val ext = n.substringAfterLast('.', "")
    if (ext in IMG_EXTS) return false
    return true
}

private fun localTrack(f: File): Track = Track(
    index = 0,
    mediaClass = "",
    kind = "direct",
    url = f.toURI().toString(),
    ext = f.extension,
    src = "local",
    pageUrl = "",
    titleHint = f.name,
)

/** 按扩展名给 SAF 用的 mime（决定「另存为」对话框里的类型）。 */
private fun mimeOfName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "mov" -> "video/quicktime"
    "flv" -> "video/x-flv"
    "ts" -> "video/mp2t"
    "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "aac" -> "audio/aac"
    else -> "application/octet-stream"
}

/**
 * 把「一部」的全部集复制进用户选的 SAF 目录（保留原文件名）。
 * 返回 (成功数, 失败数)；绝不抛异常。
 */
private fun copyGroupToTree(context: android.content.Context, treeUri: Uri, g: ContentGroup): Pair<Int, Int> {
    val resolver = context.contentResolver
    val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        ?: return 0 to g.files.size
    val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    var ok = 0
    var fail = 0
    for (f in g.files) {
        try {
            val target = DocumentsContract.createDocument(resolver, rootDoc, mimeOfName(f.name), f.name)
            if (target == null) { fail++; continue }
            val out = resolver.openOutputStream(target)
            if (out == null) { fail++; continue }
            out.use { o -> f.inputStream().use { it.copyTo(o) } }
            ok++
        } catch (e: Exception) {
            fail++
        }
    }
    return ok to fail
}

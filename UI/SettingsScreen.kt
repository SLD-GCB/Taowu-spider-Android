package com.taowu.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taowu.backend.BackendContainer
import com.taowu.backend.field.FieldSchema
import com.taowu.backend.registry.RecipeInfo
import com.taowu.backend.settings.AppSettings
import com.taowu.ui.theme.UiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val SETTINGS_TABS = listOf("媒体配方", "文字配方", "解析", "下载", "节流", "请求头", "后处理", "播放")

/** 视频解码模式下拉项：key → 显示名。 */
private val DECODE_OPTIONS = listOf(
    "auto" to "自动（硬解优先，软解兜底）",
    "soft" to "强制软解（10bit 花屏时）",
    "hard" to "仅硬解（省电，10bit 会失败）",
)

/** 解密方式下拉项：key → 显示名。 */
private val DECRYPT_OPTIONS = listOf(
    "auto" to "自动（按媒体判定）",
    "aes128" to "AES-128",
    "aes256" to "AES-256",
    "aes256ctr" to "AES-256-CTR",
    "cbcs" to "SAMPLE-AES (cbcs)",
    "off" to "关闭解密（原样落盘）",
)

/** 解析重试间隔下拉项（毫秒 → 显示名）。 */
private val BACKOFF_OPTIONS = listOf(
    "0" to "不等待",
    "300" to "0.3 秒",
    "800" to "0.8 秒",
    "1500" to "1.5 秒",
    "3000" to "3 秒",
)

/** 设置：选项卡（媒体配方 / 解析 / 下载 / 节流 / 请求头 / 后处理），左右滑动或点选项卡切换。 */
@Composable
fun SettingsScreen(container: BackendContainer) {
    var version by remember { mutableIntStateOf(0) }
    val bump: () -> Unit = { version++ }
    val pager = rememberPagerState(pageCount = { SETTINGS_TABS.size })
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(UiColors.bg),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = UiColors.text)
            Text(
                "梼杌 v5.3　·　已载入配方 ${container.loadedRecipeCount} 份",
                color = UiColors.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        ScrollableTabRow(
            selectedTabIndex = pager.currentPage,
            containerColor = UiColors.surface,
            contentColor = UiColors.text,
            edgePadding = 12.dp,
        ) {
            SETTINGS_TABS.forEachIndexed { i, name ->
                val selected = pager.currentPage == i
                Tab(
                    selected = selected,
                    onClick = { scope.launch { pager.animateScrollToPage(i) } },
                    text = {
                        Text(
                            name,
                            color = if (selected) UiColors.link else UiColors.muted,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                )
            }
        }

        // 滚动状态必须提到 key(version) 外面：否则每改一项设置就重建分页子树，滚动位置被甩回顶部
        val scrollStates = remember { List(SETTINGS_TABS.size) { ScrollState(0) } }

        key(version) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> RecipeTab(container, bump, scrollStates[0])
                    1 -> FieldTab(bump, scrollStates[1])
                    2 -> ParseTab(bump, scrollStates[2])
                    3 -> DownloadTab(bump, scrollStates[3])
                    4 -> ThrottleTab(bump, scrollStates[4])
                    5 -> HeaderTab(bump, scrollStates[5])
                    6 -> PostTab(bump, scrollStates[6])
                    else -> PlayTab(bump, scrollStates[7])
                }
            }
        }
    }
}

// ── 媒体配方 ────────────────────────────────────────────────────
@Composable
private fun RecipeTab(container: BackendContainer, onChanged: () -> Unit, scroll: ScrollState) {
    var status by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = "导入中…"
            val (name, text) = withContext(Dispatchers.IO) { readPickedRecipe(context, uri) }
            if (text == null) {
                status = "读取文件失败"
                return@launch
            }
            val err = withContext(Dispatchers.IO) { container.importRecipe(name, text) }
            status = if (err == null) "已导入：${name ?: "配方"}" else "导入失败：$err"
            onChanged()
        }
    }

    val shown = container.recipeInfos

    SettingsScroll(scroll) {
        // 导入 / 管理
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.weight(1f),
            ) { Text("导入配方…") }
            OutlinedButton(onClick = { status = "已重新载入"; onChanged() }, modifier = Modifier.weight(1f)) {
                Text("重新载入")
            }
        }
        Text(
            "支持导入 .json 配方（同 host_suffix 会覆盖内置）；用户配方可删除。",
            style = MaterialTheme.typography.bodySmall,
            color = UiColors.muted,
        )
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = UiColors.link)
        }

        if (shown.isEmpty()) {
            Text("（暂无配方）", color = UiColors.muted, style = MaterialTheme.typography.bodySmall)
        } else {
            for (r in shown) {
                RecipeCard(r, onDelete = if (r.source == "user") {
                    {
                        val ok = container.deleteRecipe(r.file)
                        status = if (ok) "已删除：${r.name}" else "删除失败：${r.name}"
                        onChanged()
                    }
                } else null)
            }
        }
    }
}

/** 读取用户选中的配方文件 → (显示名, 文本)。失败文本为 null。 */
private fun readPickedRecipe(context: Context, uri: Uri): Pair<String?, String?> {
    val name = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment
    val text = try {
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    } catch (e: Exception) {
        null
    }
    return name to text
}

// ── 解析 ────────────────────────────────────────────────────────
@Composable
private fun ParseTab(onChange: () -> Unit, scroll: ScrollState) {
    SettingsScroll(scroll) {
        ParamCard {
            IntSetting("最大跳转层数", AppSettings.resolveMaxHops, 0, 6) { AppSettings.resolveMaxHops = it; onChange() }
            BoolSetting("页内静态扫描（通用模式八趟）", AppSettings.staticScanEnabled) { AppSettings.staticScanEnabled = it; onChange() }
            BoolSetting("跟随 iframe / meta / 分集页跳转", AppSettings.hopEnabled) { AppSettings.hopEnabled = it; onChange() }
            IntSetting("页面请求超时（秒）", AppSettings.pageTimeoutSec, 5, 120) { AppSettings.pageTimeoutSec = it; onChange() }
        }

        // 抓取重试
        ParamCard {
            Text("抓取重试", color = UiColors.text, fontWeight = FontWeight.SemiBold)
            IntSetting("失败重试次数", AppSettings.parseRetry, 0, 6) { AppSettings.parseRetry = it; onChange() }
            ChoiceSetting("重试间隔基数", BACKOFF_OPTIONS, AppSettings.parseRetryBackoffMs.toString()) {
                AppSettings.parseRetryBackoffMs = it.toIntOrNull() ?: 800; onChange()
            }
            Text(
                "页面抓不到（超时/被拒/网络异常）时自动重试，逐次线性退避（基数×第几次）。这是「有时候解析不出来」的缓解项。",
                style = MaterialTheme.typography.bodySmall,
                color = UiColors.muted,
            )
        }

        // 混淆解码
        ParamCard {
            Text("混淆解码", color = UiColors.text, fontWeight = FontWeight.SemiBold)
            BoolSetting("启用混淆解码（base64 / 多重编码）", AppSettings.decodeEnabled) { AppSettings.decodeEnabled = it; onChange() }
            IntSetting("最大解码轮数", AppSettings.decodeMaxRounds, 2, 12) { AppSettings.decodeMaxRounds = it; onChange() }
            Text(
                "MacCMS encrypt 系把地址做成 base64↔URL 百分号 多重编码，开启后才能还原出真实 m3u8。解法太慢可调低轮数。",
                style = MaterialTheme.typography.bodySmall,
                color = UiColors.muted,
            )
        }

        // 分集识别
        ParamCard {
            Text("分集识别", color = UiColors.text, fontWeight = FontWeight.SemiBold)
            IntSetting("每线路最少集数", AppSettings.minLineEpisodes, 1, 9) { AppSettings.minLineEpisodes = it; onChange() }
            IntSetting("通用聚类最少锚点数", AppSettings.seriesAnchorMin, 2, 20) { AppSettings.seriesAnchorMin = it; onChange() }
            var rx by remember { mutableStateOf(AppSettings.extraEpisodeLinkRegex) }
            OutlinedTextField(
                value = rx,
                onValueChange = { rx = it; AppSettings.extraEpisodeLinkRegex = it.trim() },
                label = { Text("额外分集链接正则（可选）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "匹配分集**锚点路径**，需 3 个捕获组：分组 / 线路 / 集。\n" +
                    "例：匹配 playGV27113-1-1 →  ^playGV(\\d+)-(\\d+)-(\\d+)$　（留空=不启用）",
                style = MaterialTheme.typography.bodySmall,
                color = UiColors.muted,
            )
        }

        // 广告过滤
        ParamCard {
            Text("广告过滤", color = UiColors.text, fontWeight = FontWeight.SemiBold)
            BoolSetting("启用广告过滤", AppSettings.adFilterEnabled) { AppSettings.adFilterEnabled = it; onChange() }
            var adExtra by remember { mutableStateOf(AppSettings.adFilterExtra) }
            OutlinedTextField(
                value = adExtra,
                onValueChange = { adExtra = it; AppSettings.adFilterExtra = it },
                label = { Text("额外关键字（域名/路径子串，逗号或空格分隔）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "解析时剔除广告流（preroll/VAST/广告 CDN 等），避免把广告当视频抓下来；" +
                    "浏览器里也会拦掉广告域请求。命中规则：内置广告域名 + 路径广告词（整词）+ 上面的额外关键字。",
                style = MaterialTheme.typography.bodySmall,
                color = UiColors.muted,
            )
        }

        ResetButton(onChange)
    }
}

// ── 下载 ────────────────────────────────────────────────────────
@Composable
private fun DownloadTab(onChange: () -> Unit, scroll: ScrollState) {
    SettingsScroll(scroll) {
        ParamCard {
            ChoiceSetting("解密方式", DECRYPT_OPTIONS, AppSettings.decryptMode) { AppSettings.decryptMode = it; onChange() }
            Text(
                "HLS 分片解密方式的覆盖（DASH 的 cenc/ClearKey 按 MPD 自动）；选「关闭」则整体不解密、分片原样落盘。",
                style = MaterialTheme.typography.bodySmall,
                color = UiColors.muted,
            )
            IntSetting("分片并发数", AppSettings.downloadConcurrency, 1, 32) { AppSettings.downloadConcurrency = it; onChange() }
            IntSetting("整部同时下载集数", AppSettings.seriesParallel, 1, 8) { AppSettings.seriesParallel = it; onChange() }
            IntSetting("失败重试次数", AppSettings.downloadRetry, 0, 8) { AppSettings.downloadRetry = it; onChange() }
            BoolSetting("启用 Range 多连接加速", AppSettings.rangeEnabled) { AppSettings.rangeEnabled = it; onChange() }
            IntSetting("Range 分段数", AppSettings.rangeSplits, 1, 16) { AppSettings.rangeSplits = it; onChange() }
            IntSetting("Range 生效最小大小（MB）", AppSettings.rangeMinMb, 1, 512) { AppSettings.rangeMinMb = it; onChange() }
            IntSetting("分片/直链超时（秒）", AppSettings.segmentTimeoutSec, 5, 120) { AppSettings.segmentTimeoutSec = it; onChange() }
        }
        ResetButton(onChange)
    }
}

// ── 节流 ────────────────────────────────────────────────────────
@Composable
private fun ThrottleTab(onChange: () -> Unit, scroll: ScrollState) {
    SettingsScroll(scroll) {
        ParamCard {
            BoolSetting("启用全局+单host 闸门", AppSettings.throttleEnabled) { AppSettings.throttleEnabled = it; onChange() }
            IntSetting("单 host 并发上限", AppSettings.hostConcurrency, 1, 32) { AppSettings.hostConcurrency = it; onChange() }
            IntSetting("全局并发上限", AppSettings.globalConcurrency, 1, 64) { AppSettings.globalConcurrency = it; onChange() }
            BoolSetting("自适应并发（按吞吐自动升降）", AppSettings.adaptiveEnabled) { AppSettings.adaptiveEnabled = it; onChange() }
        }
        ResetButton(onChange)
    }
}

// ── 请求头 ──────────────────────────────────────────────────────
@Composable
private fun HeaderTab(onChange: () -> Unit, scroll: ScrollState) {
    SettingsScroll(scroll) {
        ParamCard {
            var ua by remember { mutableStateOf(AppSettings.userAgent) }
            OutlinedTextField(
                value = ua,
                onValueChange = { ua = it; AppSettings.userAgent = it },
                label = { Text("User-Agent") },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { AppSettings.userAgent = ""; onChange() }) { Text("恢复默认 UA") }
            var referer by remember { mutableStateOf(AppSettings.refererOverride) }
            OutlinedTextField(
                value = referer,
                onValueChange = { referer = it; AppSettings.refererOverride = it },
                label = { Text("强制 Referer（留空=用页面自身）") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ResetButton(onChange)
    }
}

// ── 后处理 ──────────────────────────────────────────────────────
@Composable
private fun PostTab(onChange: () -> Unit, scroll: ScrollState) {
    SettingsScroll(scroll) {
        ParamCard {
            BoolSetting("视频下载后写元数据", AppSettings.videoMetaTagging) { AppSettings.videoMetaTagging = it; onChange() }
        }
        ResetButton(onChange)
    }
}

// ── 播放 ────────────────────────────────────────────────────────
@Composable
private fun PlayTab(onChange: () -> Unit, scroll: ScrollState) {
    SettingsScroll(scroll) {
        ParamCard {
            Text(
                "自动=硬解优先、软解兜底；强制软解用于硬解花屏；仅硬解最省电但 10bit 会失败",
                style = MaterialTheme.typography.bodySmall,
                color = UiColors.muted,
                maxLines = 2,
            )
            ChoiceSetting("视频解码", DECODE_OPTIONS, AppSettings.videoDecodeMode) { AppSettings.videoDecodeMode = it; onChange() }
        }
        ResetButton(onChange)
    }
}

// ── 文字配方（文字抓取的 JSON 配置：数据表模板 + 字段规则）────────────
@Composable
private fun FieldTab(onChange: () -> Unit, scroll: ScrollState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(FieldSchema.templateList()) }
    var userCfgs by remember { mutableStateOf(FieldSchema.userConfigs()) }

    fun refresh() {
        list = FieldSchema.templateList()
        userCfgs = FieldSchema.userConfigs()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = "导入中…"
            val (name, text) = withContext(Dispatchers.IO) { readPickedRecipe(context, uri) }
            if (text == null) { status = "读取文件失败"; return@launch }
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val stem = (name ?: "fieldcfg").substringBeforeLast(".json")
                        .replace(Regex("[^A-Za-z0-9_\\u4e00-\\u9fa5.-]"), "_").ifEmpty { "cfg_${System.currentTimeMillis()}" }
                    File(FieldSchema.userDir(context), "$stem.json").writeText(text)
                    null
                }.getOrElse { it.message ?: "写入失败" }
            }
            FieldSchema.reload(context)
            refresh()
            status = if (err == null) "已导入：${name ?: "配置"}（模板 ${list.size} · 规则 ${FieldSchema.ruleCount()}）" else "导入失败：$err"
            onChange()
        }
    }

    SettingsScroll(scroll) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.weight(1f),
            ) { Text("导入文字配方…") }
            OutlinedButton(
                onClick = { FieldSchema.reload(context); refresh(); status = "已重新载入" },
                modifier = Modifier.weight(1f),
            ) { Text("重新载入") }
        }
        Text(
            "文字抓取用的 JSON 配置：\n" +
                "· 含 columns[] 的当作「数据表模板」；\n" +
                "· {规则名:{...}} 的当作「字段规则」片段并入规则库。\n" +
                "同名用户配置覆盖内置；用户导入的可删除，内置的只读。",
            style = MaterialTheme.typography.bodySmall,
            color = UiColors.muted,
        )
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = UiColors.link)
        }

        ParamCard {
            Text("文字采集", color = UiColors.text, fontWeight = FontWeight.SemiBold)
            ChoiceSetting(
                "默认字段模板",
                list.map { it.name to it.name },
                AppSettings.fieldTemplate.ifBlank { list.firstOrNull()?.name ?: "" },
            ) { AppSettings.fieldTemplate = it; onChange() }
            IntSetting("采集结果上限", AppSettings.collectMax, 1, 100000) { AppSettings.collectMax = it; onChange() }
        }

        Text("数据表模板（${list.size}）", color = UiColors.text, fontWeight = FontWeight.SemiBold)
        if (list.isEmpty()) {
            Text("（暂无模板）", color = UiColors.muted, style = MaterialTheme.typography.bodySmall)
        } else {
            for (t in list) {
                Card(colors = CardDefaults.cardColors(containerColor = UiColors.surface), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.name, color = UiColors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(
                                if (t.source == "user") "用户" else "内置",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (t.source == "user") UiColors.link else UiColors.muted,
                            )
                            if (t.source == "user") {
                                TextButton(onClick = {
                                    File(FieldSchema.userDir(context), t.file).delete()
                                    FieldSchema.reload(context)
                                    refresh()
                                    status = "已删除：${t.name}"
                                    onChange()
                                }) { Text("删除", color = UiColors.error) }
                            }
                        }
                        Text("${t.engine.ifBlank { "-" }} · ${t.columns.size} 列", style = MaterialTheme.typography.bodySmall, color = UiColors.link)
                        if (t.desc.isNotEmpty()) {
                            Text(t.desc, style = MaterialTheme.typography.bodySmall, color = UiColors.muted, maxLines = 2)
                        }
                    }
                }
            }
        }

        Text("字段规则", color = UiColors.text, fontWeight = FontWeight.SemiBold)
        Text("规则库共 ${FieldSchema.ruleCount()} 条规则", style = MaterialTheme.typography.bodySmall, color = UiColors.muted)
        val ruleCfgs = userCfgs.filter { it.kind == "rules" }
        if (ruleCfgs.isEmpty()) {
            Text("（无用户规则片段）", color = UiColors.muted, style = MaterialTheme.typography.bodySmall)
        } else {
            for (c in ruleCfgs) {
                Card(colors = CardDefaults.cardColors(containerColor = UiColors.surface), modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(c.label, color = UiColors.text, modifier = Modifier.weight(1f))
                        Text("用户", style = MaterialTheme.typography.bodySmall, color = UiColors.link)
                        TextButton(onClick = {
                            File(FieldSchema.userDir(context), c.file).delete()
                            FieldSchema.reload(context)
                            refresh()
                            status = "已删除规则片段：${c.file}"
                            onChange()
                        }) { Text("删除", color = UiColors.error) }
                    }
                }
            }
        }
    }
}

// ── 公共组件 ────────────────────────────────────────────────────
@Composable
private fun SettingsScroll(state: ScrollState, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(state)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun ParamCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = UiColors.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun ResetButton(onChange: () -> Unit) {
    OutlinedButton(
        onClick = { AppSettings.resetAll(); onChange() },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) { Text("恢复全部默认") }
}

@Composable
private fun RecipeCard(r: RecipeInfo, onDelete: (() -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = UiColors.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, color = UiColors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    if (r.source == "user") "用户" else "内置",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.source == "user") UiColors.link else UiColors.muted,
                )
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除", color = UiColors.error) }
                }
            }
            if (r.hosts.isNotEmpty()) {
                Text(r.hosts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = UiColors.link)
            }
            if (r.desc.isNotEmpty()) {
                Text(r.desc, style = MaterialTheme.typography.bodySmall, color = UiColors.muted, maxLines = 3)
            }
        }
    }
}

@Composable
private fun IntSetting(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    var v by remember { mutableIntStateOf(value) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = UiColors.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { val n = (v - 1).coerceAtLeast(min); v = n; onChange(n) }, enabled = v > min) { Text("−") }
        Text("$v", color = UiColors.text, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { val n = (v + 1).coerceAtMost(max); v = n; onChange(n) }, enabled = v < max) { Text("+") }
    }
}

@Composable
private fun BoolSetting(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(value) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = UiColors.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = v, onCheckedChange = { v = it; onChange(it) })
    }
}

/** 下拉选择设置项：options 为 (key → 显示名)。 */
@Composable
private fun ChoiceSetting(
    label: String,
    options: List<Pair<String, String>>,
    value: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value }?.second ?: value
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 标签固定横排、不换行（否则窄屏被下拉挤成竖排）；下拉靠右，太长则省略号。
        Text(
            label,
            color = UiColors.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(end = 8.dp),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            OutlinedButton(onClick = { open = true }) {
                Text("$current ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((k, v) in options) {
                    DropdownMenuItem(text = { Text(v) }, onClick = { onSelect(k); open = false })
                }
            }
        }
    }
}

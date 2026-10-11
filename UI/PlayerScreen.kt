package com.taowu.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.taowu.backend.BackendContainer
import com.taowu.backend.PlayPositions
import com.taowu.backend.model.Track
import com.taowu.ui.playback.PlayerFactory
import com.taowu.ui.theme.UiColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 播放页：Media3 播放直链/本地文件；下方「四集一排」网格 + 上一集/下一集；顶部全屏按钮。
 * · 一集播完自动接下一集；
 * · 全屏/普通都有「分集列表」按钮，弹窗实时显示"看到哪一集、看到第几秒"；
 * · 退出记录播放位置，下次进入自动续播（看完则清零）。
 */
@Composable
fun PlayerScreen(
    container: BackendContainer,
    track: Track,
    series: PlayContext?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()

    val ctx = series
    var currentTrack by remember(track.url) { mutableStateOf(track) }
    var currentIndex by remember(ctx?.index ?: 0) { mutableIntStateOf(ctx?.index ?: 0) }
    var resolving by remember { mutableStateOf(false) }
    var errText by remember { mutableStateOf("") }
    var fullscreen by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    // 硬解失败后自动用软解重试本集（每集只重试一次）
    var forceSoft by remember(currentTrack.url, currentIndex) { mutableStateOf(false) }

    /** 进度记忆的键：整部按"标题#集标签"，单条按直链。 */
    fun posKey(t: Track, idx: Int): String =
        if (ctx != null && ctx.size > 0) "s:${ctx.title}#${ctx.label(idx)}" else "u:${t.url}"

    fun switchTo(idx: Int) {
        val c = ctx ?: return
        if (resolving || idx == currentIndex || idx < 0 || idx >= c.size) return
        c.localAt(idx)?.let { f ->
            errText = ""
            currentTrack = localTrack(f)
            currentIndex = idx
            return
        }
        val item = c.itemAt(idx) ?: return
        resolving = true
        errText = ""
        scope.launch {
            val tracks = try {
                container.grabber.resolveEpisode(item, c.mode)
            } catch (e: Exception) {
                emptyList()
            }
            if (tracks.isNotEmpty()) {
                currentTrack = tracks[0]
                currentIndex = idx
            } else {
                errText = "${c.label(idx)} 未解析到流"
            }
            resolving = false
        }
    }

    val player = remember(currentTrack.url, currentIndex, forceSoft) {
        val start = PlayPositions.get(context, posKey(currentTrack, currentIndex))
        PlayerFactory.build(context, currentTrack, start, preferSoftware = forceSoft)
    }

    DisposableEffect(currentTrack.url, currentIndex, forceSoft) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 硬解解不了（如 10bit）→ 本集自动改用 FFmpeg 软解重试一次（DRM 不能软解，排除）
                if (isDecodingError(error) && !forceSoft && !currentTrack.isDrmCommercial) {
                    forceSoft = true
                    errText = "硬解不支持该画质（常见于 10bit），已切到软解重试…"
                } else {
                    errText = friendlyPlaybackError(error)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    PlayPositions.clear(context, posKey(currentTrack, currentIndex))
                    val c = ctx
                    if (c != null && currentIndex < c.size - 1) switchTo(currentIndex + 1)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // 记住看到哪（看完的不记）
            runCatching {
                val pos = player.currentPosition
                val dur = player.duration
                if (pos > 0 && (dur <= 0 || pos < dur - 1500)) {
                    PlayPositions.put(context, posKey(currentTrack, currentIndex), pos)
                }
            }
            // 平滑退出：先停并清媒体，release 延后到主线程下一轮，避免退出那一下卡顿
            runCatching { player.stop() }
            runCatching { player.clearMediaItems() }
            Handler(Looper.getMainLooper()).post { runCatching { player.release() } }
        }
    }

    // 每 3 秒记一次进度（防中途被杀）
    LaunchedEffect(currentTrack.url, currentIndex, forceSoft) {
        while (true) {
            delay(3000)
            runCatching {
                val pos = player.currentPosition
                val dur = player.duration
                if (pos > 0 && (dur <= 0 || pos < dur - 1500)) {
                    PlayPositions.put(context, posKey(currentTrack, currentIndex), pos)
                }
            }
        }
    }

    fun goFullscreen(on: Boolean) {
        fullscreen = on
        activity?.requestedOrientation = if (on) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val window = activity?.window ?: return
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val window = activity?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val hasSeries = ctx != null && ctx.size > 0
    val epLabel = if (hasSeries) ctx!!.label(currentIndex) else ""
    val titleText = ctx?.title?.ifEmpty { currentTrack.titleHint } ?: currentTrack.titleHint.ifEmpty { currentTrack.url }

    @Composable
    fun Player(modifier: Modifier) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    useController = true
                }
            },
            // 关键：切集时 player 是新实例，必须重新绑定（否则画面停在旧播放器上不动）
            update = { view -> if (view.player !== player) view.player = player },
            onRelease = { it.player = null },
            modifier = modifier,
        )
    }

    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Player(Modifier.fillMaxSize())
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasSeries) {
                    OutlinedButton(onClick = { showPicker = true }) { Text("分集列表") }
                }
                OutlinedButton(onClick = { goFullscreen(false) }) { Text("退出全屏") }
            }
        }
        if (showPicker && ctx != null) {
            EpisodePickerDialog(
                ctx = ctx,
                current = currentIndex,
                player = player,
                onSelect = { showPicker = false; switchTo(it) },
                onDismiss = { showPicker = false },
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(UiColors.bg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Box(Modifier.weight(1f))
            if (hasSeries) {
                TextButton(onClick = { showPicker = true }) { Text("分集列表") }
            }
            TextButton(onClick = { goFullscreen(true) }) { Text("⛶ 全屏") }
        }
        // 第二行：动漫名（只取书名号那串）· 第N集
        Text(
            run {
                val name = com.taowu.backend.download.Naming.bookTitle(titleText)
                if (epLabel.isEmpty()) name else "$name · $epLabel"
            },
            color = UiColors.text,
            maxLines = 1,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        )

        Player(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )

        if (errText.isNotEmpty()) {
            Text(
                "播放错误：$errText",
                color = UiColors.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
        }

        if (hasSeries) {
            val c = ctx!!
            HorizontalDivider(color = UiColors.surface)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { switchTo(currentIndex - 1) }, enabled = currentIndex > 0 && !resolving) {
                    Text("上一集")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "第 ${currentIndex + 1} / ${c.size} 集",
                        color = UiColors.text,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (resolving) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(14.dp))
                            Text("解析中…", color = UiColors.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick = { switchTo(currentIndex + 1) }, enabled = currentIndex < c.size - 1 && !resolving) {
                    Text("下一集")
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(c.size) { idx -> EpisodeCell(c, idx, idx == currentIndex) { switchTo(idx) } }
            }
        }
    }

    if (showPicker && ctx != null) {
        EpisodePickerDialog(
            ctx = ctx,
            current = currentIndex,
            player = player,
            onSelect = { showPicker = false; switchTo(it) },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun EpisodeCell(ctx: PlayContext, idx: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (selected) UiColors.link else UiColors.surface, RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ctx.label(idx),
            color = if (selected) UiColors.onAccent else UiColors.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** 分集列表弹窗：高亮当前集，并实时显示"看到第几秒/共多长"。 */
@Composable
private fun EpisodePickerDialog(
    ctx: PlayContext,
    current: Int,
    player: ExoPlayer,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            posMs = runCatching { player.currentPosition }.getOrDefault(0L)
            durMs = runCatching { player.duration }.getOrDefault(0L)
            delay(500)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分集列表") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "第 ${current + 1}/${ctx.size} 集 · ${ctx.label(current)}" +
                        "　已看 ${fmtClock(posMs)}" + (if (durMs > 0) "/${fmtClock(durMs)}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = UiColors.link,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ctx.size) { idx -> EpisodeCell(ctx, idx, idx == current) { onSelect(idx) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun fmtClock(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 是否解码类失败（硬解解不了的编码/画质，如 10bit H.264）。 */
private fun isDecodingError(e: androidx.media3.common.PlaybackException): Boolean =
    e.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ||
        e.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
        e.errorCodeName.contains("DECODING")

/** 把常见播放错误码翻成人话。 */
private fun friendlyPlaybackError(e: androidx.media3.common.PlaybackException): String = when {
    isDecodingError(e) ->
        "本机硬解不支持该编码/画质（常见于 10bit 番）。下载不受影响；可到 设置 → 播放 改成「强制软解」再试。"
    e.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "服务器拒绝访问（可能需要 Referer/Cookie）"
    e.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        e.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "网络连接失败/超时"
    else -> "${e.errorCodeName}: ${e.message ?: ""}"
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    else -> (this as? ContextWrapper)?.baseContext?.findActivity()
}

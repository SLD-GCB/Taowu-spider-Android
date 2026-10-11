package com.taowu.ui.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.taowu.backend.model.Track
import com.taowu.backend.settings.AppSettings
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * Media3/ExoPlayer 封装。
 * 网络流：把 Referer/UA/Cookie 设为数据源默认请求属性（对清单+分片+密钥同时生效）。
 * 本地文件：用 DefaultDataSource，直接播 file://。
 */
object PlayerFactory {

    /**
     * @param preferSoftware 强制用 FFmpeg 软解（硬解解 10bit 出绿屏/花屏、或硬解初始化失败时由上层重试触发）。
     *   否则按设置 AppSettings.videoDecodeMode：auto=硬解优先软解兜底 / soft=强制软解 / hard=仅硬解。
     */
    fun build(
        context: Context,
        track: Track,
        startPositionMs: Long = 0L,
        preferSoftware: Boolean = false,
    ): ExoPlayer {
        val ua = AppSettings.userAgent
        val isRemote = track.url.startsWith("http")
        val dsFactory: DataSource.Factory = if (isRemote) {
            val headers = LinkedHashMap<String, String>()
            headers["User-Agent"] = ua
            if (track.pageUrl.isNotEmpty()) headers["Referer"] = track.pageUrl
            if (track.cookies.isNotEmpty()) {
                headers["Cookie"] = track.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
            // 包一层 DefaultDataSource：既走 http，也能兜住 file/asset 等
            DefaultDataSource.Factory(context, http)
        } else {
            DefaultDataSource.Factory(context)
        }

        // 扩展渲染器模式：ON=平台(硬解)优先、平台解不了时用 FFmpeg 兜底；PREFER=强制软解；OFF=不用软解。
        val estMode = if (preferSoftware) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        } else {
            when (AppSettings.videoDecodeMode) {
                "soft" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                "hard" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            }
        }
        val renderersFactory = NextRenderersFactory(context)
            .setExtensionRendererMode(estMode)
            .setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .build()

        var builder = MediaItem.Builder().setUri(track.url)
        when (track.kind) {
            "m3u8" -> builder = builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            "dash" -> builder = builder.setMimeType(MimeTypes.APPLICATION_MPD)
            else -> mimeForFile(track.url)?.let { builder = builder.setMimeType(it) }
        }
        player.setMediaItem(builder.build())
        if (startPositionMs > 0L) player.seekTo(startPositionMs)
        player.prepare()
        player.playWhenReady = true
        return player
    }

    /** 本地文件按扩展名给 MIME —— 否则 ExoPlayer 可能认不出容器（尤其 .ts）。 */
    private fun mimeForFile(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".ts") || path.endsWith(".m2ts") -> MimeTypes.VIDEO_MP2T
            path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            path.endsWith(".mov") -> MimeTypes.VIDEO_MP4
            path.endsWith(".flv") -> MimeTypes.VIDEO_FLV
            path.endsWith(".m4a") -> MimeTypes.AUDIO_MP4
            path.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
            path.endsWith(".aac") -> MimeTypes.AUDIO_AAC
            path.endsWith(".ogg") || path.endsWith(".opus") -> MimeTypes.AUDIO_OGG
            path.endsWith(".wav") -> MimeTypes.AUDIO_WAV
            path.endsWith(".flac") -> MimeTypes.AUDIO_FLAC
            else -> null
        }
    }
}

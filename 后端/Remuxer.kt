package com.taowu.backend.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * 音视频转封装/合成，用 Media3 Transformer 替代桌面的 ffmpeg。
 *  · remux：单文件转封装（如 HLS 落的 .ts → .mp4）
 *  · merge：视频轨 + 音频轨合成单文件（DASH 分轨 / 复合轨 parts）
 * 已兼容的 H.264/AAC 等多为转封装不重编码；失败返回 false（调用方兜底）。
 */
object Remuxer {

    /** 单文件转封装到 output（.mp4 容器）。 */
    suspend fun remux(context: Context, input: File, output: File): Boolean =
        combine(context, listOf(input), output)

    /** 视频轨 + 音频轨合成单文件。 */
    suspend fun merge(context: Context, video: File, audio: File, output: File): Boolean =
        combine(context, listOf(video, audio), output)

    /** 1 路转封装 / 多路合成，统一入口。 */
    suspend fun combine(context: Context, files: List<File>, output: File): Boolean =
        run(context, files.map { MediaItem.fromUri(Uri.fromFile(it)) }, output)

    /** 多路素材 → 单文件（每路一条 sequence，Transformer 并行混流）。 */
    private suspend fun run(context: Context, mediaItems: List<MediaItem>, output: File): Boolean =
        suspendCancellableCoroutine { cont ->
            if (mediaItems.isEmpty()) {
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }
            val sequences = mediaItems.map {
                EditedMediaItemSequence.Builder(EditedMediaItem.Builder(it).build()).build()
            }
            val transformer = try {
                Transformer.Builder(context).build()
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }
            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            try {
                output.parentFile?.mkdirs()
                val composition = Composition.Builder(sequences).build()
                transformer.start(composition, output.absolutePath)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
            cont.invokeOnCancellation { try { transformer.cancel() } catch (_: Exception) {} }
        }
}

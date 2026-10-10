package com.taowu.ui

import com.taowu.backend.ResolveMode
import com.taowu.backend.grab.Grabber
import java.io.File

/**
 * 播放上下文：承载"整部/多集 + 当前第几集"，供播放页在视频下方展示分集并即时切集。
 *  · items 非空 = 联网番剧（每集再解析出流）；
 *  · locals 非空 = 本地已下载文件（直接切文件播放）；
 *  · 两者都空 = 单条轨道，不显示选集。
 */
data class PlayContext(
    val title: String,
    val seedUrl: String = "",
    val mode: ResolveMode = ResolveMode.GENERAL,
    val items: List<Grabber.EpisodeItem> = emptyList(),
    val index: Int = 0,
    val locals: List<File> = emptyList(),
) {
    val size: Int get() = if (locals.isNotEmpty()) locals.size else items.size
    val isSeries: Boolean get() = size > 1

    fun label(i: Int): String =
        if (locals.isNotEmpty()) locals.getOrNull(i)?.nameWithoutExtension ?: "第${i + 1}集"
        else items.getOrNull(i)?.label?.ifEmpty { "第${i + 1}集" } ?: "第${i + 1}集"

    fun localAt(i: Int): File? = locals.getOrNull(i)
    fun itemAt(i: Int): Grabber.EpisodeItem? = items.getOrNull(i)

    companion object {
        /** 本地已下载的整部 → 播放上下文。 */
        fun ofLocal(title: String, files: List<File>, index: Int = 0): PlayContext =
            PlayContext(title = title, index = index, locals = files)
    }
}

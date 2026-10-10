package com.taowu.backend.download

import java.io.File

// ================================================================
// 落盘命名规整（移植 video_grab._fs_clean / ensure_unique_path / _dest_path_for / _fmt_size）。
// 保留 CJK：safe_name 会把中文压成 '-'，不适用目录/分集名，故单独一套。
// ================================================================
object Naming {

    private val ILLEGAL = Regex("""[<>:"/\\|?*\u0000-\u001f\u007f]+""")
    private val WS = Regex("\\s+")

    /** Windows 安全文件名：保留 CJK，剔除非法字符与控制符，压空白去首尾点/空格。 */
    fun fsClean(name: String?, maxLen: Int = 80): String {
        if (name.isNullOrEmpty()) return ""
        var s = name.trim()
        s = ILLEGAL.replace(s, " ")
        s = WS.replace(s, " ").trim(' ', '.')
        if (s.length > maxLen) s = s.take(maxLen).trimEnd(' ', '.')
        return s
    }

    /** 只取标题里书名号那串（《…》）当番剧名；没有书名号则原样返回。 */
    private val BOOK_TITLE = Regex("""《[^》]{1,80}》""")
    fun bookTitle(title: String?): String {
        val t = title ?: return ""
        return BOOK_TITLE.find(t)?.value ?: t
    }

    /** 重名时追加 -2/-3，避免覆盖。 */    fun ensureUniquePath(path: File): File {
        if (!path.exists()) return path
        val name = path.name
        val dot = name.lastIndexOf('.')
        val root = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) {
            val cand = File(path.parentFile, "$root-$i$ext")
            if (!cand.exists()) return cand
            i++
        }
    }

    fun fmtSize(n: Long): String = when {
        n >= 1024L * 1024 -> "%.1fMB".format(n / 1024.0 / 1024.0)
        n >= 1024 -> "%.1fKB".format(n / 1024.0)
        else -> "${n}B"
    }

    /** 按标题+扩展名定路径（标题空/纯符号时回退媒体 URL 文件名主体；冲突去重）。 */
    fun destPathFor(dir: File, title: String, mediaUrl: String, ext: String, num: Int = 1): File {
        var base = fsClean(title)
        if (base.isEmpty() || base.all { it in "-_." }) {
            val stub = mediaUrl.substringAfterLast('/').substringBefore('?')
                .replace(Regex("""\.(m3u8|ts|mp4|flv|webm|mov|m4v|mkv)$""", RegexOption.IGNORE_CASE), "")
            base = fsClean(stub.ifEmpty { "video" })
        }
        if (base.isEmpty() || base.all { it in "-_." }) base = "video"
        base = base.take(80)
        if (num > 1) base = "$base-$num"
        val e = ext.ifEmpty { "mp4" }
        return ensureUniquePath(File(dir, "$base.$e"))
    }
}

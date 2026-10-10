package com.taowu.backend.download

import com.taowu.backend.crypto.DecryptError
import com.taowu.backend.crypto.HlsSegment
import com.taowu.backend.crypto.StreamDecrypt
import com.taowu.backend.crypto.UnsupportedScheme
import com.taowu.backend.model.Track
import com.taowu.backend.net.MediaHeaders
import com.taowu.backend.net.NetEngine
import com.taowu.backend.registry.netlocOf
import com.taowu.backend.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

// ================================================================
// 下载栈（对齐 video_grab）：HLS（分片并发+AES解密+有序写入+.part/.idx 续传）
//   直链（Range 多连接并行 + 段级续传 + 落 .part）
//   DASH（视频/音频分轨 + 合成 + CENC 逐片解密）
//   复合轨（parts 多路 + 合成）；403/429 归因重试。
// ================================================================

data class DownloadResult(
    val status: String,          // ok | fail | skipped | unsupported
    val path: String?,
    val bytes: Long,
    val error: String? = null,
    val errorCode: String? = null,
)

object ErrCode {
    const val NETWORK = "NETWORK"
    const val HTTP_STATUS = "HTTP_STATUS"
    const val HOTLINK_AUTH = "HOTLINK_AUTH"
    const val EMPTY = "EMPTY"
    const val HLS_MANIFEST = "HLS_MANIFEST"
    const val HLS_SEGMENT = "HLS_SEGMENT"
    const val AES_KEY = "AES_KEY"
    const val ENCRYPT_UNSUPPORTED = "ENCRYPT_UNSUPPORTED"
    const val DRM_PROTECTED = "DRM_PROTECTED"
    const val REMUX = "FFMPEG_REMUX"
    const val UNKNOWN = "UNKNOWN"
}

private fun rangeSplits(): Int = AppSettings.rangeSplits
private fun rangeMinBytes(): Long = AppSettings.rangeMinMb * 1024L * 1024L
private fun retryCount(): Int = AppSettings.downloadRetry

/** 用户选择的解密方式覆盖（HLS 分片）：auto 原样返回；其余强制 METHOD。 */
private fun applyDecryptMode(enc: com.taowu.backend.crypto.HlsEnc?): com.taowu.backend.crypto.HlsEnc? {
    if (enc == null) return null
    val forced = when (AppSettings.decryptMode) {
        "aes128" -> "AES-128"
        "aes256" -> "AES-256"
        "aes256ctr" -> "AES-256-CTR"
        "cbcs" -> "SAMPLE-AES"
        else -> return enc
    }
    return enc.copy(method = forced)
}

/** 下载进度回调：onProgress(done,total)=完成比例；onBytes(cumulativeBytes)=已写字节（算速度用）。 */
class DlListener(
    val onProgress: (Long, Long) -> Unit = { _, _ -> },
    val onBytes: (Long) -> Unit = {},
)

class Downloader(
    private val net: NetEngine,
    /** 合成钩子：多路文件 → 单文件（接 Media3 Transformer）。null = 不做合成。 */
    private val mux: (suspend (files: List<File>, out: File) -> Boolean)? = null,
) {
    private fun headers(track: Track, referer: String, purpose: String? = null): Map<String, String> {
        val ref = referer.ifEmpty { track.pageUrl }
        return MediaHeaders.headers(track.url, ref, purpose, AppSettings.userAgent)
    }

    private fun keyFetch(headers: Map<String, String>, cookies: Map<String, String>): (String, String, Map<String, String>?) -> ByteArray? =
        { url, referer, ck ->
            val h = LinkedHashMap(headers)
            if (referer.isNotEmpty()) h["Referer"] = referer
            net.getBytes(url, h, ck ?: cookies, 20)
        }

    /** 带 403/429 归因重试的二进制抓取（在全局+host 闸门内，含健康记账）。 */
    private suspend fun fetchBin(
        url: String, h: Map<String, String>, cookies: Map<String, String>, timeout: Int,
    ): ByteArray? {
        val netloc = netlocOf(url)
        return Throttle.fetch(netloc) {
            var out: ByteArray? = null
            for (attempt in 0..retryCount()) {
                val r = net.getBytesDetailed(url, h, cookies, timeout) ?: continue
                if (r.status in 200..299) {
                    out = r.bytes
                    Throttle.markHealth(netloc, true)
                    Throttle.hostGate(netloc).recordBytes(r.bytes.size.toLong())
                    break
                }
                Throttle.markHealth(netloc, false)
                if (DlRecover.classify(r.status, r.bytes) == "ratelimit") {
                    delay((DlRecover.backoffSeconds() * 1000).toLong())
                } else {
                    break
                }
            }
            out
        }
    }

    /** 下载一条轨道到 dest。 */
    suspend fun download(
        track: Track,
        dest: File,
        referer: String = "",
        concurrency: Int = AppSettings.downloadConcurrency,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        onBytes: (Long) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        val l = DlListener(onProgress, onBytes)
        try {
            if (track.parts.isNotEmpty()) {
                return@withContext downloadComposite(track, dest, referer, concurrency, l)
            }
            downloadInner(track, dest, referer, concurrency, l)
        } catch (e: UnsupportedScheme) {
            DownloadResult("unsupported", null, 0, e.message, ErrCode.ENCRYPT_UNSUPPORTED)
        } catch (e: DecryptError) {
            DownloadResult("fail", null, 0, e.message, ErrCode.AES_KEY)
        } catch (e: Exception) {
            DownloadResult("fail", null, 0, e.message ?: "下载异常", ErrCode.UNKNOWN)
        }
    }

    private suspend fun downloadInner(
        track: Track, dest: File, referer: String, concurrency: Int, l: DlListener,
    ): DownloadResult = when (track.kind) {
        "m3u8" -> downloadHls(track, dest, referer, concurrency, l)
        "dash" -> downloadDash(track, dest, referer, concurrency, l)
        else -> downloadDirect(track, dest, referer, concurrency, l)
    }

    // ── 复合轨 ──────────────────────────────────────────────────
    private suspend fun downloadComposite(
        track: Track, dest: File, referer: String, concurrency: Int, l: DlListener,
    ): DownloadResult {
        val dir = dest.parentFile ?: return DownloadResult("fail", null, 0, "目标目录无效", ErrCode.UNKNOWN)
        dir.mkdirs()
        val files = ArrayList<File>()
        for ((i, p) in track.parts.withIndex()) {
            val ext = p.ext.ifEmpty { if (p.kind == "m3u8") "ts" else "bin" }
            val f = File(dir, dest.nameWithoutExtension + ".part$i.$ext")
            val sub = Track(0, p.mediaClass, p.kind, p.url, p.ext, "part", track.pageUrl, titleHint = track.titleHint)
            val r = downloadInner(sub, f, referer, concurrency, l)
            if (r.status != "ok") {
                files.forEach { it.delete() }
                return DownloadResult("fail", null, 0, "子件下载失败(${p.mediaClass})：${r.error}", r.errorCode)
            }
            files.add(f)
        }
        if (files.isEmpty()) return DownloadResult("fail", null, 0, "无子件", ErrCode.UNKNOWN)
        return combineAndFinish(files, dest, l)
    }

    private suspend fun combineAndFinish(
        files: List<File>, dest: File, l: DlListener,
    ): DownloadResult {
        if (files.size == 1) {
            if (dest.exists()) dest.delete()
            return if (files[0].renameTo(dest) || run { files[0].copyTo(dest, true); files[0].delete(); true }) {
                DownloadResult("ok", dest.absolutePath, dest.length())
            } else {
                DownloadResult("fail", null, 0, "落盘失败", ErrCode.UNKNOWN)
            }
        }
        val m = mux ?: return DownloadResult("unsupported", null, 0, "无合成器（需视频+音频合并）", ErrCode.REMUX)
        l.onProgress(0, 1)
        val ok = try { m(files, dest) } catch (e: Exception) { false }
        val written = dest.length()
        files.forEach { it.delete() }
        return if (ok && written > 0) DownloadResult("ok", dest.absolutePath, written)
        else DownloadResult("fail", null, 0, "合成失败", ErrCode.REMUX)
    }

    // ── HLS（有序写入 + .part/.idx 续传） ───────────────────────
    private suspend fun downloadHls(
        track: Track, dest: File, referer: String, concurrency: Int, l: DlListener,
    ): DownloadResult {
        val h = headers(track, referer, "manifest")
        val manifest = net.getRaw(track.url, h, track.cookies, AppSettings.pageTimeoutSec)
            ?: return DownloadResult("fail", null, 0, "拉取清单失败", ErrCode.HLS_MANIFEST)

        var pt = StreamDecrypt.parseHlsMedia(manifest, track.url)
        if (pt.kind == "master") {
            val best = pt.variants.maxWithOrNull(compareBy({ it.resolution }, { it.bandwidth }))
                ?: return DownloadResult("fail", null, 0, "主清单无有效变体", ErrCode.HLS_MANIFEST)
            val sub = net.getRaw(best.uri, headers(track, referer, "manifest"), track.cookies, AppSettings.pageTimeoutSec)
                ?: return DownloadResult("fail", null, 0, "拉取子清单失败", ErrCode.HLS_MANIFEST)
            pt = StreamDecrypt.parseHlsMedia(sub, best.uri)
        }
        val segs = pt.segments
        if (segs.isEmpty()) return DownloadResult("fail", null, 0, "清单无分片", ErrCode.HLS_MANIFEST)

        val initBytes = pt.map?.uri?.takeIf { it.isNotEmpty() }?.let { net.getBytes(it, h, track.cookies, AppSettings.pageTimeoutSec) }
        val kf = keyFetch(h, track.cookies)

        dest.parentFile?.mkdirs()
        val partial = File(dest.absolutePath + ".part")
        val idx = File(dest.absolutePath + ".idx")
        val fp = tsFingerprint(segs)
        var (flushed, fileBytes) = resumeState(partial, idx, fp, segs.size)

        val raf = RandomAccessFile(partial, "rw")
        try {
            if (flushed > 0) {
                raf.seek(fileBytes)
            } else {
                fileBytes = 0L
                if (initBytes != null) { raf.write(initBytes); fileBytes += initBytes.size }
            }
            var i = flushed
            val window = concurrency.coerceAtLeast(1)
            while (i < segs.size) {
                val end = minOf(i + window, segs.size)
                val batch = coroutineScope {
                    (i until end).map { k ->
                        val seg = segs[k]
                        async {
                            val data = fetchBin(seg.uri, headers(track, referer, "segment"), track.cookies, AppSettings.segmentTimeoutSec)
                            when {
                                data == null -> null
                                AppSettings.decryptMode == "off" -> data      // 关闭解密：原样落盘
                                else -> StreamDecrypt.decryptSegment(
                                    data, applyDecryptMode(seg.enc), seg.seq, referer = referer,
                                    cookies = track.cookies, fetch = kf, initBytes = initBytes,
                                )
                            }
                        }
                    }.map { it.await() }
                }
                for (d in batch) {
                    if (d == null) {
                        raf.close()
                        return DownloadResult("fail", null, 0, "分片下载失败（已保留续传点）", ErrCode.HLS_SEGMENT)
                    }
                    raf.write(d)
                    fileBytes += d.size
                }
                i = end
                saveIndex(idx, fp, segs.size, i, fileBytes)
                l.onProgress(i.toLong(), segs.size.toLong())
                l.onBytes(fileBytes)
            }
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
        if (dest.exists()) dest.delete()
        if (!partial.renameTo(dest)) { partial.copyTo(dest, true); partial.delete() }
        idx.delete()
        return DownloadResult("ok", dest.absolutePath, dest.length())
    }

    // ── 直链（Range 多连接 / 单流 + .part 续传） ─────────────────
    private suspend fun downloadDirect(
        track: Track, dest: File, referer: String, concurrency: Int, l: DlListener,
    ): DownloadResult {
        val h = headers(track, referer, "direct")
        val total = net.probeTotalSize(track.url, h, track.cookies, AppSettings.segmentTimeoutSec)
        if (AppSettings.rangeEnabled && total >= rangeMinBytes()) {
            val r = rangeParallel(track, dest, h, total, l)
            if (r.status == "ok" || r.status == "skipped" || r.status == "unsupported") return r
        }
        return directSingle(track, dest, h, l)
    }

    private suspend fun directSingle(
        track: Track, dest: File, h: Map<String, String>, l: DlListener,
    ): DownloadResult {
        val part = File(dest.absolutePath + ".part")
        val resumeAt = if (part.exists()) part.length() else 0L
        val res = net.getBytesDetailed(track.url, h, track.cookies, AppSettings.segmentTimeoutSec, if (resumeAt > 0) resumeAt else -1)
            ?: return DownloadResult("fail", null, 0, "下载失败（网络）", ErrCode.NETWORK)
        if (res.status !in 200..299) {
            val code = if (res.status == 429 || res.status == 401 || res.status in listOf(403, 406, 451)) ErrCode.HOTLINK_AUTH else ErrCode.HTTP_STATUS
            return DownloadResult("fail", null, 0, DlRecover.hint(res.status, res.bytes), code)
        }
        val body = res.bytes
        if (body.isEmpty()) return DownloadResult("fail", null, 0, "空文件", ErrCode.EMPTY)
        dest.parentFile?.mkdirs()
        val append = resumeAt > 0 && res.status == 206
        if (append) {
            RandomAccessFile(part, "rw").use { it.seek(resumeAt); it.write(body) }
        } else {
            part.writeBytes(body)
        }
        val len = part.length()
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) { part.copyTo(dest, true); part.delete() }
        l.onProgress(len, len)
        l.onBytes(len)
        return DownloadResult("ok", dest.absolutePath, dest.length())
    }

    private fun rangeSpans(total: Long, splits: Int): List<Pair<Long, Long>> {
        val t = total
        if (t <= 0) return emptyList()
        val s = splits.coerceAtLeast(1)
        if (s <= 1) return listOf(0L to t - 1)
        val chunk = (t + s - 1) / s
        val out = ArrayList<Pair<Long, Long>>()
        for (k in 0 until s) {
            val start = k * chunk
            val end = minOf(t - 1, (k + 1) * chunk - 1)
            if (start <= end) out.add(start to end)
        }
        return out
    }

    private suspend fun rangeParallel(
        track: Track, dest: File, h: Map<String, String>, total: Long, l: DlListener,
    ): DownloadResult {
        val spans = rangeSpans(total, rangeSplits())
        if (spans.isEmpty()) return DownloadResult("fail", null, 0, "Range: 文件为空", ErrCode.EMPTY)
        val dir = dest.parentFile ?: return DownloadResult("fail", null, 0, "目标目录无效", ErrCode.UNKNOWN)
        dir.mkdirs()
        val frag = spans.indices.map { File(dir, "${dest.name}.rng_%02d".format(it)) }
        val meta = File(dir, "${dest.name}.rng_meta.json")
        val srcId = sha1Hex(track.url + "#" + total).take(16)
        val reuseOk = meta.exists() && runCatching { meta.readText().contains(srcId) }.getOrDefault(false)
        if (!reuseOk) frag.forEach { it.delete() }
        runCatching { meta.writeText("{\"id\":\"$srcId\",\"total\":$total}") }

        var doneBytes = 0L
        val lock = Any()
        val sem = Semaphore(rangeSplits())
        val okFlags = coroutineScope {
            spans.mapIndexed { k, span ->
                async {
                    sem.withPermit {
                        val n = fetchRange(track, h, span, frag[k])
                        synchronized(lock) { doneBytes += n; l.onProgress(doneBytes, total); l.onBytes(doneBytes) }
                        n >= 0
                    }
                }
            }.map { it.await() }
        }
        if (okFlags.any { !it }) {
            spans.forEachIndexed { k, span ->
                val exp = span.second - span.first + 1
                if (frag[k].exists() && frag[k].length() != exp) frag[k].delete()
            }
            return DownloadResult("fail", null, 0, "Range 分段下载失败", ErrCode.NETWORK)
        }
        for ((k, span) in spans.withIndex()) {
            val exp = span.second - span.first + 1
            if (!frag[k].exists() || frag[k].length() != exp) {
                return DownloadResult("fail", null, 0, "段 $k 校验失败", ErrCode.NETWORK)
            }
        }
        val tmp = File(dest.absolutePath + ".part")
        tmp.delete()
        try {
            tmp.outputStream().buffered().use { out -> for (f in frag) f.inputStream().use { it.copyTo(out) } }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
        } catch (e: Exception) {
            tmp.delete()
            return DownloadResult("fail", null, 0, "拼接失败：${e.message}", ErrCode.NETWORK)
        }
        frag.forEach { it.delete() }
        meta.delete()
        if (!dest.exists() || dest.length() != total) {
            dest.delete()
            return DownloadResult("fail", null, 0, "拼接结果校验失败", ErrCode.NETWORK)
        }
        return DownloadResult("ok", dest.absolutePath, total)
    }

    /** 下载第 k 段；已有完整段直接复用（跨次续传）。失败返回 -1。 */
    private suspend fun fetchRange(track: Track, h: Map<String, String>, span: Pair<Long, Long>, f: File): Long {
        val (start, end) = span
        val exp = end - start + 1
        if (f.exists() && f.length() == exp) return exp
        f.delete()
        repeat(retryCount() + 1) {
            val b = net.getBytesDetailed(track.url, h, track.cookies, AppSettings.segmentTimeoutSec, start, end)?.let {
                if (it.status == 206 || it.status == 200) it.bytes else null
            }
            if (b != null && b.size.toLong() == exp) {
                f.writeBytes(b)
                return exp
            }
        }
        f.delete()
        return -1
    }

    // ── DASH ────────────────────────────────────────────────────
    private suspend fun downloadDash(
        track: Track, dest: File, referer: String, concurrency: Int, l: DlListener,
    ): DownloadResult {
        val h = headers(track, referer, "manifest")
        val mpd = net.getRaw(track.url, h, track.cookies, AppSettings.pageTimeoutSec)
            ?: return DownloadResult("fail", null, 0, "拉取 MPD 失败", ErrCode.NETWORK)
        val probe = StreamDecrypt.probeMpd(mpd)
        var cencKey: ByteArray? = null
        if (probe.cenc && AppSettings.decryptMode != "off") {
            val info = com.taowu.backend.crypto.ClearKey.parse(mpd)
            cencKey = info.laurl?.let { com.taowu.backend.crypto.ClearKey.fetchKey(net, it, info.kidHex, h) }
            if (cencKey == null) {
                return DownloadResult("unsupported", null, 0, "DASH CENC：无可用 ClearKey（缺 Laurl/密钥）", ErrCode.ENCRYPT_UNSUPPORTED)
            }
        }
        val tracks = try {
            DashParser.parseTracks(mpd, track.url)
        } catch (e: Exception) {
            return DownloadResult("fail", null, 0, "MPD 解析失败：${e.message}", ErrCode.UNKNOWN)
        }
        val v = tracks.video
        val a = tracks.audio
        val dir = dest.parentFile ?: return DownloadResult("fail", null, 0, "目标目录无效", ErrCode.UNKNOWN)
        dir.mkdirs()

        if (v != null && a != null) {
            val vf = File(dir, dest.nameWithoutExtension + ".v.mp4")
            val af = File(dir, dest.nameWithoutExtension + ".a.mp4")
            if (!fetchDashTrack(v, h, track.cookies, vf, concurrency, l, cencKey)) {
                vf.delete(); af.delete()
                return DownloadResult("fail", null, 0, "视频轨下载失败", ErrCode.NETWORK)
            }
            if (!fetchDashTrack(a, h, track.cookies, af, concurrency, DlListener(), cencKey)) {
                vf.delete(); af.delete()
                return DownloadResult("fail", null, 0, "音频轨下载失败", ErrCode.NETWORK)
            }
            return combineAndFinish(listOf(vf, af), dest, l)
        }

        val one = v ?: a ?: return DownloadResult("fail", null, 0, "MPD 无可下载分片", ErrCode.UNKNOWN)
        if (!fetchDashTrack(one, h, track.cookies, dest, concurrency, l, cencKey)) {
            return DownloadResult("fail", null, 0, "分片下载失败", ErrCode.NETWORK)
        }
        return DownloadResult("ok", dest.absolutePath, dest.length())
    }

    private suspend fun fetchDashTrack(
        t: DashParser.DashTrack, h: Map<String, String>, cookies: Map<String, String>,
        dest: File, concurrency: Int, l: DlListener,
        key: ByteArray? = null,
    ): Boolean {
        if (t.mediaUrls.isEmpty()) return false
        val initBytes = t.initUrl?.takeIf { it.isNotEmpty() }?.let { net.getBytes(it, h, cookies, AppSettings.segmentTimeoutSec) }
        val sem = Semaphore(concurrency.coerceAtLeast(1))
        val total = t.mediaUrls.size.toLong()
        var done = 0L
        val parts = coroutineScope {
            t.mediaUrls.map { u ->
                async {
                    sem.withPermit {
                        var b = net.getBytes(u, h, cookies, AppSettings.segmentTimeoutSec)
                        if (b != null && key != null) {
                            b = try {
                                com.taowu.backend.crypto.CencDecryptor.decrypt(b, initBytes, key)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        done++
                        l.onProgress(done, total)
                        b
                    }
                }
            }.map { it.await() }
        }
        if (parts.any { it == null }) return false
        dest.parentFile?.mkdirs()
        var written = 0L
        dest.outputStream().buffered().use { fos ->
            if (initBytes != null) { fos.write(initBytes); written += initBytes.size }
            for (p in parts) { fos.write(p!!); written += p.size }
        }
        l.onBytes(written)
        return dest.length() > 0
    }

    // ── 续传辅助 ────────────────────────────────────────────────
    private fun tsFingerprint(segs: List<HlsSegment>): String {
        if (segs.isEmpty()) return ""
        val h = MessageDigest.getInstance("SHA-1")
        h.update(segs.size.toString().toByteArray())
        h.update((segs.first().uri).toByteArray())
        h.update("|".toByteArray())
        h.update(segs.last().uri.toByteArray())
        return hex(h.digest()).take(16)
    }

    private fun resumeState(partial: File, idx: File, fp: String, total: Int): Pair<Int, Long> {
        try {
            if (idx.exists()) {
                val m = Json.parseToJsonElement(idx.readText()).jsonObject
                val fp2 = m["fp"]?.jsonPrimitive?.contentOrNull
                val total2 = m["total"]?.jsonPrimitive?.intOrNull
                val flushed = m["flushed"]?.jsonPrimitive?.intOrNull
                val bytes = m["bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                if (fp2 == fp && total2 == total && flushed != null && flushed in 0..total) {
                    if (flushed > 0 && (!partial.exists() || partial.length() != bytes)) return 0 to 0L
                    return flushed to bytes
                }
            }
        } catch (_: Exception) {
        }
        partial.delete()
        idx.delete()
        return 0 to 0L
    }

    private fun saveIndex(idx: File, fp: String, total: Int, flushed: Int, bytes: Long) {
        try {
            idx.writeText("{\"fp\":\"$fp\",\"total\":$total,\"flushed\":$flushed,\"bytes\":$bytes}")
        } catch (_: Exception) {
        }
    }

    private fun sha1Hex(s: String): String = hex(MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)))

    private fun hex(b: ByteArray): String = buildString { for (x in b) append("%02x".format(x)) }
}

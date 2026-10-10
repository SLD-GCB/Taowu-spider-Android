package com.taowu.backend.crypto

// ================================================================
// fMP4 加密元数据解析（ISO/IEC 23001-7）：tenc(默认 IV/pattern/KID) + senc(逐样本 IV/子样本)
//   + tfhd/trun(样本偏移与大小)。cbcs / cenc 两个解密器共用本模块。
// ================================================================
object Mp4Crypto {

    data class Box(val type: String, val start: Int, val dataStart: Int, val end: Int)

    /** 解析结果：pattern(crypt/skip，cenc 时为 0) + constIV + 逐样本 (偏移,大小,IV,子样本)。 */
    data class Info(
        val ivSize: Int,
        val crypt: Int,
        val skip: Int,
        val constantIv: ByteArray?,
        val samples: List<Sample>,
    )

    data class Sample(val offset: Int, val size: Int, val iv: ByteArray?, val subsamples: List<Pair<Int, Int>>?)

    private class Tenc(val ivSize: Int, val crypt: Int, val skip: Int, val constantIv: ByteArray?)

    /** 解析 media（可含多个 moof）；init 提供 tenc（可与 media 同一份）。 */
    fun parse(media: ByteArray, init: ByteArray?): Info? {
        val tencSrc = init ?: media
        val tencBox = locateTenc(tencSrc) ?: return null
        val tenc = parseTenc(tencSrc, tencBox)
        val samples = ArrayList<Sample>()
        val moofs = listBoxes(media, 0, media.size).filter { it.type == "moof" }
        for (moof in moofs) {
            for (traf in childBoxes(media, moof).filter { it.type == "traf" }) {
                val sencBox = childBoxes(media, traf).firstOrNull { it.type == "senc" }
                val sencSamples = sencBox?.let { parseSenc(media, it, tenc.ivSize) } ?: emptyList()
                val truns = childBoxes(media, traf).filter { it.type == "trun" }
                val tfhd = childBoxes(media, traf).firstOrNull { it.type == "tfhd" }
                val defaultSampleSize = tfhd?.let { parseTfhdDefaultSize(media, it) } ?: 0
                val refs = parseTruns(media, truns, defaultSampleSize, moof.start)
                for (i in refs.indices) {
                    val (absOff, size) = refs[i]
                    if (size <= 0 || absOff < 0 || absOff + size > media.size) continue
                    val s = sencSamples.getOrNull(i)
                    samples.add(Sample(absOff, size, s?.iv, s?.subsamples))
                }
            }
        }
        return Info(tenc.ivSize, tenc.crypt, tenc.skip, tenc.constantIv, samples)
    }

    // ── tenc ────────────────────────────────────────────────────
    private fun locateTenc(d: ByteArray): Box? {
        val moov = listBoxes(d, 0, d.size).firstOrNull { it.type == "moov" } ?: return null
        for (trak in childBoxes(d, moov).filter { it.type == "trak" }) {
            val mdia = childBoxes(d, trak).firstOrNull { it.type == "mdia" } ?: continue
            val minf = childBoxes(d, mdia).firstOrNull { it.type == "minf" } ?: continue
            val stbl = childBoxes(d, minf).firstOrNull { it.type == "stbl" } ?: continue
            val stsd = childBoxes(d, stbl).firstOrNull { it.type == "stsd" } ?: continue
            val entries = listBoxes(d, stsd.dataStart + 8, stsd.end)
            for (entry in entries) {
                findSinfHeuristic(d, entry)?.let { sinf ->
                    val schi = childBoxes(d, sinf).firstOrNull { it.type == "schi" } ?: return@let null
                    childBoxes(d, schi).firstOrNull { it.type == "tenc" }
                }?.let { return it }
            }
        }
        return null
    }

    private fun findSinfHeuristic(d: ByteArray, entry: Box): Box? {
        val target = "sinf".toByteArray(Charsets.US_ASCII)
        var i = entry.dataStart
        while (i + 8 <= entry.end) {
            var match = true
            for (k in 0..3) if (d[i + 4 + k] != target[k]) { match = false; break }
            if (match) {
                val size = u32(d, i).toInt()
                if (size >= 8 && i + size <= entry.end) return Box("sinf", i, i + 8, i + size)
            }
            i++
        }
        return null
    }

    private fun parseTenc(d: ByteArray, tenc: Box): Tenc {
        val p = tenc.dataStart
        val version = u8(d, p)
        var q = p + 4
        var ivSize = 0
        var crypt = 0
        var skip = 0
        var constantIv: ByteArray? = null
        if (version == 0) {
            q += 3; q += 1
            ivSize = u8(d, q); q += 1
            q += 16
        } else {
            q += 1
            val cs = u8(d, q); q += 1
            crypt = (cs shr 4) and 0x0F
            skip = cs and 0x0F
            q += 1
            ivSize = u8(d, q); q += 1
            q += 16
            if (q < tenc.end) {
                val civSize = u8(d, q); q += 1
                if (civSize > 0 && q + civSize <= tenc.end) constantIv = d.copyOfRange(q, q + civSize)
            }
        }
        return Tenc(ivSize, crypt, skip, constantIv)
    }

    // ── senc ────────────────────────────────────────────────────
    private class SampleEnc(val iv: ByteArray?, val subsamples: List<Pair<Int, Int>>?)

    private fun parseSenc(d: ByteArray, senc: Box, ivSize: Int): List<SampleEnc> {
        val p = senc.dataStart
        val flags = (u8(d, p + 1) shl 16) or (u8(d, p + 2) shl 8) or u8(d, p + 3)
        val useSubsamples = (flags and 0x2) != 0
        var q = p + 4
        val count = u32(d, q).toInt(); q += 4
        val out = ArrayList<SampleEnc>(count)
        for (i in 0 until count) {
            var iv: ByteArray? = null
            if (ivSize == 16 && q + 16 <= senc.end) {
                iv = d.copyOfRange(q, q + 16); q += 16
            } else if (ivSize == 8 && q + 8 <= senc.end) {
                val b = ByteArray(16); System.arraycopy(d, q, b, 8, 8); iv = b; q += 8
            }
            var subs: List<Pair<Int, Int>>? = null
            if (useSubsamples && q + 2 <= senc.end) {
                val n = u16(d, q); q += 2
                val list = ArrayList<Pair<Int, Int>>(n)
                for (j in 0 until n) {
                    if (q + 6 > senc.end) break
                    val clear = u16(d, q); q += 2
                    val prot = u32(d, q).toInt(); q += 4
                    list.add(clear to prot)
                }
                subs = list
            }
            out.add(SampleEnc(iv, subs))
        }
        return out
    }

    // ── tfhd / trun ─────────────────────────────────────────────
    private fun parseTfhdDefaultSize(d: ByteArray, tfhd: Box): Int {
        val p = tfhd.dataStart
        val flags = (u8(d, p + 1) shl 16) or (u8(d, p + 2) shl 8) or u8(d, p + 3)
        var q = p + 4 + 4
        if (flags and 0x000001 != 0) q += 8
        if (flags and 0x000002 != 0) q += 4
        if (flags and 0x000008 != 0) q += 4
        if (flags and 0x000010 != 0) return u32(d, q).toInt()
        return 0
    }

    private fun parseTruns(d: ByteArray, truns: List<Box>, defaultSampleSize: Int, moofStart: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (trun in truns) {
            val p = trun.dataStart
            val flags = (u8(d, p + 1) shl 16) or (u8(d, p + 2) shl 8) or u8(d, p + 3)
            var q = p + 4
            val sampleCount = u32(d, q).toInt(); q += 4
            var dataOffset = 0
            if (flags and 0x000001 != 0) { dataOffset = u32(d, q).toInt(); q += 4 }
            if (flags and 0x000004 != 0) q += 4
            var abs = moofStart + dataOffset
            for (i in 0 until sampleCount) {
                if (flags and 0x000100 != 0) q += 4
                val size = if (flags and 0x000200 != 0) { val s = u32(d, q).toInt(); q += 4; s } else defaultSampleSize
                if (flags and 0x000400 != 0) q += 4
                if (flags and 0x000800 != 0) q += 4
                out.add(abs to size)
                abs += size
            }
        }
        return out
    }

    // ── box 工具 ────────────────────────────────────────────────
    fun listBoxes(d: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = start
        while (p + 8 <= end) {
            var size = u32(d, p).toInt()
            val type = String(d, p + 4, 4, Charsets.US_ASCII)
            var dataStart = p + 8
            if (size == 1) {
                if (p + 16 > end) break
                val large = u64(d, p + 8)
                dataStart = p + 16
                size = if (large > Int.MAX_VALUE) end - p else large.toInt()
            } else if (size == 0) {
                size = end - p
            }
            if (size < 8 || p + size > end) break
            out.add(Box(type, p, dataStart, p + size))
            p += size
        }
        return out
    }

    fun childBoxes(d: ByteArray, box: Box): List<Box> = listBoxes(d, box.dataStart, box.end)

    fun u8(d: ByteArray, p: Int): Int = d[p].toInt() and 0xFF
    fun u16(d: ByteArray, p: Int): Int = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
    fun u32(d: ByteArray, p: Int): Long =
        ((d[p].toLong() and 0xFF) shl 24) or ((d[p + 1].toLong() and 0xFF) shl 16) or
            ((d[p + 2].toLong() and 0xFF) shl 8) or (d[p + 3].toLong() and 0xFF)
    fun u64(d: ByteArray, p: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[p + i].toLong() and 0xFF)
        return v
    }
}

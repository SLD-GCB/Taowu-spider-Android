package com.taowu.backend.crypto

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ================================================================
// 音视频流解密引擎（移植自 streamdecrypt.py）。
// 形态探测(probe) → 清单解析(KEY 轮换/IV/media-sequence) → key 获取(raw/hex/base64/json)
//   → 分片解密(AES-128-CBC) → 容器 sanity。
// 商业 DRM 只识别、跳过，绝不解密。
// ================================================================

object Scheme {
    const val PLAIN = "plain"
    const val HLS_AES128 = "hls-aes128"
    const val HLS_AES256 = "hls-aes256"
    const val HLS_SAMPLE_AES = "hls-sample-aes"
    const val DASH_CENC = "dash-cenc"
    const val DRM = "drm"
    const val UNKNOWN = "unknown"
}

open class DecryptError(message: String) : Exception(message)
class UnsupportedScheme(message: String) : DecryptError(message)

private val DRM_PSSH_SYSIDS = mapOf(
    "edef8ba979d64acea3c827dcd51d21ed" to "Widevine",
    "9a04f07998404286ab92e65be0885f95" to "PlayReady",
    "94ce86fb07ff4f43adb893d2fa968ca2" to "FairPlay",
)
private const val CLEARKEY_SYSID = "1077efefc0b24d02ace33c1e52e2fb4b"

private val DRM_KEYFORMAT_MAP = mapOf(
    "com.apple.streamingkeydelivery" to "FairPlay",
    "com.apple.streamingkeydelivery.v2" to "FairPlay",
    "com.microsoft.playready" to "PlayReady",
    "com.widevine" to "Widevine",
    "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" to "Widevine",
    "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95" to "PlayReady",
    "urn:uuid:94ce86fb-07ff-4f43-adb8-93d2fa968ca2" to "FairPlay",
)
private val KEYFORMAT_NONCOMMERCIAL = setOf(
    "", "identity", "org.w3.clearkey", "urn:uuid:1077efef-c0b2-4d02-ace3-3c1e52e2fb4b",
)
private val DRM_URI_SCHEMES = mapOf("skd://" to "FairPlay", "skr://" to "FairPlay")

// ── 数据模型 ─────────────────────────────────────────────────────
data class HlsEnc(
    val method: String,
    val uri: String,
    val ivHex: String,
    val keyformat: String,
    val keyid: String,
)

data class HlsSegment(val uri: String, val seq: Int, val enc: HlsEnc?)
data class HlsVariant(val uri: String, val bandwidth: Int, val resolution: Int)
data class HlsMap(val uri: String, val byterange: String)

data class HlsPlaylist(
    val kind: String,
    val variants: List<HlsVariant>,
    val segments: List<HlsSegment>,
    val mediaSequence: Int,
    val hasMap: Boolean,
    val map: HlsMap?,
    val singleFile: Boolean,
    val targetDuration: Double,
)

data class DrmProbe(
    val commercial: Boolean,
    val drmSystems: List<String>,
    val sampleAes: Boolean,
    val keys: Int,
    val unknownKeyformats: List<String>,
)

data class MpdProbe(val scheme: String, val drmSystems: List<String>, val cenc: Boolean, val clearkey: Boolean)

/** key 拉取钩子（默认用 NetEngine.getBytes）。 */
typealias KeyFetch = (url: String, referer: String, cookies: Map<String, String>?) -> ByteArray?

object StreamDecrypt {

    // ── key 缓存 ────────────────────────────────────────────────
    private val keyCache = HashMap<String, ByteArray>()
    private val lock = Any()

    fun resetKeyCache() = synchronized(lock) { keyCache.clear() }

    private fun cacheKey(uri: String, referer: String, cookieFp: String) = "$uri\u0000$referer\u0000$cookieFp"

    // ── 工具 ────────────────────────────────────────────────────
    private fun hexToBytes(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i < s.length) {
            out[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }

    private fun bytesToHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02x".format(x.toInt() and 0xFF))
        return sb.toString()
    }

    /** urljoin(base, uri)。 */
    fun resolveUrl(baseUrl: String, uri: String): String {
        val u = uri.trim().trim('"')
        return try {
            java.net.URI(baseUrl).resolve(u).toString()
        } catch (e: Exception) {
            u
        }
    }

    private fun seqIv(seq: Int): ByteArray {
        val b = ByteArray(16)
        var v = seq
        for (i in 15 downTo 0) {
            b[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return b
    }

    // ── 容器 sanity ─────────────────────────────────────────────
    fun sanityTs(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val head = data.copyOfRange(0, minOf(data.size, 8 * 188))
        val limit = minOf(head.size, 376)
        for (i in 0 until limit) {
            if (head[i] != 0x47.toByte() || i % 188 != 0) continue
            var hits = 0
            var j = i + 188
            while (j < head.size) {
                if (head[j] != 0x47.toByte()) break
                hits++
                j += 188
            }
            if (hits >= 2) return true
        }
        return false
    }

    fun sanityFmp4(data: ByteArray): Boolean {
        if (data.size < 12) return false
        val box = String(data, 4, 4, Charsets.US_ASCII)
        if (box in setOf("ftyp", "moov", "styp", "sidx", "free", "mdat")) return true
        val first = String(data, 0, minOf(4, data.size), Charsets.US_ASCII)
        return first == "ftyp" || first == "moov"
    }

    fun sanityMedia(data: ByteArray, hint: String = ""): String {
        if (data.isEmpty()) return ""
        if (data[0] == '<'.code.toByte() || data[0] == '{'.code.toByte() || data[0] == '['.code.toByte()) {
            if (data.size < 4096) {
                val head = String(data.copyOfRange(0, minOf(data.size, 512)), Charsets.ISO_8859_1).lowercase()
                if (head.contains("<html") || head.contains("<!doctype") || head.startsWith("<")) return "text"
            }
        }
        if (sanityTs(data)) return "ts"
        if (sanityFmp4(data)) return "mp4"
        if (data.size >= 3 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) return "audio"
        if (data.size > 4 && data[0] == 0xFF.toByte() && (data[1].toInt() and 0xF0) == 0xF0) return "audio"
        if (data.size >= 4 && String(data, 0, 4, Charsets.US_ASCII) == "OggS") return "audio"
        return ""
    }

    // ── key 载体 coerce（支持 16B / 32B） ──────────────────────
    private val HEX_RE16 = Regex("^[0-9a-fA-F]{32}$")
    private val HEX_RE32 = Regex("^[0-9a-fA-F]{64}$")
    private val KEY_JSON_FIELDS = listOf("key", "enc_key", "encKey", "key_hex", "keyHex", "data", "content", "k")

    private fun tryBase64(s: String): ByteArray? {
        val cleaned = s.filterNot { it.isWhitespace() }
        if (cleaned.length < 16) return null
        val variants = listOf(cleaned, cleaned.trimEnd('='), cleaned.replace("-", "+").replace("_", "/"))
        for (v in variants) {
            val padded = v + "=".repeat((4 - v.length % 4) % 4)
            try {
                val dec = java.util.Base64.getDecoder().decode(padded)
                if (dec.size == 16 || dec.size == 32) return dec
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** 从 key 响应体提取 key：raw / hex / base64 / JSON。支持 16 字节(AES-128) 与 32 字节(AES-256)。 */
    fun coerceKey(body: ByteArray, wantLen: Int = 0): ByteArray {
        if (body.isEmpty()) throw DecryptError("key 响应为空")
        if (body[0] == '{'.code.toByte() || body[0] == '['.code.toByte()) {
            try {
                val text = String(body, Charsets.UTF_8)
                val json = kotlinx.serialization.json.Json.parseToJsonElement(text)
                val candidates = ArrayList<kotlinx.serialization.json.JsonElement>()
                if (json is kotlinx.serialization.json.JsonObject) {
                    for (f in KEY_JSON_FIELDS) json[f]?.let { candidates.add(it) }
                } else if (json is kotlinx.serialization.json.JsonArray) {
                    for (it in json) if (it is kotlinx.serialization.json.JsonObject) {
                        for (f in KEY_JSON_FIELDS) it[f]?.let { c -> candidates.add(c) }
                    }
                }
                for (c in candidates) {
                    val s = (c as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                    if (!s.isNullOrEmpty()) {
                        val cand = try { coerceKey(s.toByteArray(Charsets.US_ASCII), wantLen) } catch (e: Exception) { null }
                        if (cand != null && cand.isNotEmpty()) return cand
                    }
                }
            } catch (_: Exception) {
            }
            throw DecryptError("JSON key 体未含可解析 key 字段")
        }
        // hex 文本（32 hex=16B / 64 hex=32B）
        if (body.size >= 32) {
            val s = String(body, Charsets.US_ASCII).trim()
            if (HEX_RE16.matches(s)) return hexToBytes(s)
            if (HEX_RE32.matches(s)) return hexToBytes(s)
        }
        // base64 文本（解出 16 或 32 字节）
        if (body.size >= 22) {
            tryBase64(String(body, Charsets.US_ASCII).trim())?.let { return it }
        }
        // raw 二进制：按期望长度优先，其次 32B，最后 16B
        if (wantLen in listOf(16, 32) && body.size >= wantLen) return body.copyOfRange(0, wantLen)
        if (body.size == 32) return body.copyOfRange(0, 32)
        if (body.size >= 16) return body.copyOfRange(0, 16)
        throw DecryptError("key 载体长度不足 16B 且无法识别格式")
    }

    // ── HLS media 解析 ──────────────────────────────────────────
    fun parseHlsMedia(text: String, baseUrl: String): HlsPlaylist {
        val lines = text.split("\n").map { it.trim() }
        val variants = ArrayList<HlsVariant>()
        val segments = ArrayList<HlsSegment>()
        var kind = "media"
        var mediaSequence = 0
        var hasMap = false
        var map: HlsMap? = null
        var singleFile = false
        var targetDuration = 0.0

        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            var prev = ""
            for (ln in lines) {
                if (ln.startsWith("#EXT-X-STREAM-INF")) {
                    prev = ln
                } else if (ln.isNotEmpty() && !ln.startsWith("#")) {
                    val bw = Regex("BANDWIDTH\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE).find(prev)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val resM = Regex("RESOLUTION\\s*=\\s*(\\d+)x(\\d+)", RegexOption.IGNORE_CASE).find(prev)
                    val res = if (resM != null) (resM.groupValues[1].toInt() * resM.groupValues[2].toInt()) else 0
                    variants.add(HlsVariant(resolveUrl(baseUrl, ln), bw, res))
                    prev = ""
                }
            }
            return HlsPlaylist("master", variants, emptyList(), 0, false, null, false, 0.0)
        }

        var enc: HlsEnc? = null
        var seq = 0
        for (ln in lines) {
            when {
                ln.startsWith("#EXT-X-MEDIA-SEQUENCE") -> {
                    Regex(":\\s*(\\d+)").find(ln)?.groupValues?.get(1)?.toIntOrNull()?.let {
                        seq = it; mediaSequence = it
                    }
                }
                ln.startsWith("#EXT-X-TARGETDURATION") -> {
                    Regex(":\\s*([\\d.]+)").find(ln)?.groupValues?.get(1)?.toDoubleOrNull()?.let { targetDuration = it }
                }
                ln.startsWith("#EXT-X-BYTERANGE") -> singleFile = true
                ln.startsWith("#EXT-X-MAP") -> {
                    hasMap = true
                    if (map == null) {
                        val mu = Regex("URI\\s*=\\s*\"([^\"]+)\"").find(ln)?.groupValues?.get(1)
                        val mb = Regex("BYTERANGE\\s*=\\s*([^,\\s]+)").find(ln)?.groupValues?.get(1)
                        map = HlsMap(mu?.let { resolveUrl(baseUrl, it) } ?: "", mb?.trim('"', '\'') ?: "")
                    }
                }
                ln.startsWith("#EXT-X-KEY") -> {
                    var method = "NONE"
                    var uri = ""
                    var ivHex = ""
                    var keyformat = ""
                    var keyid = ""
                    Regex("METHOD\\s*=\\s*([^,]+)").find(ln)?.groupValues?.get(1)?.let { method = it.trim().uppercase() }
                    Regex("URI\\s*=\\s*\"([^\"]+)\"").find(ln)?.groupValues?.get(1)?.let { uri = it }
                    Regex("IV\\s*=\\s*(0x[0-9A-Fa-f]+)").find(ln)?.groupValues?.get(1)?.let { ivHex = it }
                    Regex("KEYFORMAT\\s*=\\s*\"?([^,\"]+)").find(ln)?.groupValues?.get(1)?.let { keyformat = it.trim('"') }
                    Regex("KEYID\\s*=\\s*\"?([^,\"]+)").find(ln)?.groupValues?.get(1)?.let { keyid = it.trim('"') }
                    enc = if (method == "NONE") null
                    else HlsEnc(method, if (uri.isNotEmpty()) resolveUrl(baseUrl, uri) else "", ivHex, keyformat, keyid)
                }
                ln.startsWith("#EXTINF") -> {}
                ln.isNotEmpty() && !ln.startsWith("#") -> {
                    segments.add(HlsSegment(resolveUrl(baseUrl, ln), seq, enc?.copy()))
                    seq++
                }
            }
        }
        return HlsPlaylist(kind, variants, segments, mediaSequence, hasMap, map, singleFile, targetDuration)
    }

    // ── 探测 ────────────────────────────────────────────────────
    fun probeHls(text: String): String {
        val methods = ArrayList<String>()
        for (ln in text.split("\n")) {
            if (ln.startsWith("#EXT-X-KEY")) {
                Regex("METHOD\\s*=\\s*([A-Za-z0-9_-]+)").find(ln)?.groupValues?.get(1)?.let { methods.add(it.uppercase()) }
            }
        }
        if (methods.isEmpty()) return Scheme.PLAIN
        if (methods.all { it == "NONE" || it == "AES-128" }) {
            return if (methods.any { it == "AES-128" }) Scheme.HLS_AES128 else Scheme.PLAIN
        }
        if (methods.any { it == "SAMPLE-AES" || it == "SAMPLE-AES-CTR" }) return Scheme.HLS_SAMPLE_AES
        if (methods.any { it == "AES-256" || it == "AES-256-CTR" }) return Scheme.HLS_AES256
        return Scheme.UNKNOWN
    }

    /** KEYFORMAT（+ key URI scheme）→ 商业 DRM 名；自家可解或认不得 → ""。 */
    fun drmSystemFromKeyformat(keyformat: String, keyUri: String = ""): String {
        val kf = keyformat.trim().trim('"').lowercase()
        if (kf in KEYFORMAT_NONCOMMERCIAL) return ""
        DRM_KEYFORMAT_MAP[kf]?.let { return it }
        val u = keyUri.trim().lowercase()
        for ((sch, name) in DRM_URI_SCHEMES) if (u.startsWith(sch)) return name
        return ""
    }

    fun probeHlsDrm(text: String): DrmProbe {
        val drmSystems = ArrayList<String>()
        val unknown = ArrayList<String>()
        var sampleAes = false
        var keys = 0
        if (text.isNotEmpty()) {
            for (ln in text.split("\n")) {
                if (!ln.startsWith("#EXT-X-KEY")) continue
                val method = Regex("METHOD\\s*=\\s*([^,]+)").find(ln)?.groupValues?.get(1)?.trim()?.trim('"')?.uppercase() ?: ""
                if (method.isEmpty() || method == "NONE") continue
                keys++
                if (method == "SAMPLE-AES" || method == "SAMPLE-AES-CTR") sampleAes = true
                val kf = Regex("KEYFORMAT\\s*=\\s*\"?([^,\"]+)").find(ln)?.groupValues?.get(1)?.trim()?.trim('"') ?: ""
                val uri = Regex("URI\\s*=\\s*\"([^\"]+)\"").find(ln)?.groupValues?.get(1) ?: ""
                val name = drmSystemFromKeyformat(kf, uri)
                if (name.isNotEmpty()) {
                    if (name !in drmSystems) drmSystems.add(name)
                } else if (kf.isNotEmpty() && kf.trim().trim('"').lowercase() !in KEYFORMAT_NONCOMMERCIAL && kf !in unknown) {
                    unknown.add(kf)
                }
            }
        }
        return DrmProbe(drmSystems.isNotEmpty(), drmSystems, sampleAes, keys, unknown)
    }

    fun probeMpd(text: String): MpdProbe {
        if (!text.contains("<ContentProtection")) return MpdProbe(Scheme.PLAIN, emptyList(), false, false)
        val flat = text.lowercase().replace("-", "")
        val clearkey = flat.contains(CLEARKEY_SYSID)
        val systems = ArrayList<String>()
        for ((sysid, name) in DRM_PSSH_SYSIDS) if (flat.contains(sysid)) systems.add(name)
        return MpdProbe(Scheme.DASH_CENC, systems, true, clearkey)
    }

    fun mpdClearKeyCandidate(text: String): Boolean {
        val r = probeMpd(text)
        return r.cenc && r.drmSystems.isEmpty()
    }

    // ── 解密 ────────────────────────────────────────────────────
    fun segmentIv(enc: HlsEnc?, seq: Int): ByteArray {
        if (enc != null && enc.ivHex.isNotEmpty()) {
            var ivHex = enc.ivHex
            if (ivHex.lowercase().startsWith("0x")) ivHex = ivHex.substring(2)
            val b = hexToBytes(ivHex)
            if (b.size != 16) throw DecryptError("EXT-X-KEY IV 长度非 16 字节")
            return b
        }
        return seqIv(seq)
    }

    private fun stripPkcs7(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pad = data[data.size - 1].toInt() and 0xFF
        if (pad in 1..16 && pad <= data.size) {
            var ok = true
            for (i in data.size - pad until data.size) if ((data[i].toInt() and 0xFF) != pad) { ok = false; break }
            if (ok) return data.copyOfRange(0, data.size - pad)
        }
        return data
    }

    private fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun aesEcbDecryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    /** cbcs pattern 解密原语（SAMPLE-AES 族容器级样本解密时用）。 */
    fun decryptCbcsRange(
        data: ByteArray, key: ByteArray, iv: ByteArray,
        cryptByteBlocks: Int = 1, skipByteBlocks: Int = 9,
    ): ByteArray {
        if (data.isEmpty()) return data
        if (key.size != 16) throw DecryptError("cbcs key 须 16 字节")
        if (iv.size != 16) throw DecryptError("cbcs constant IV 须 16 字节")
        val crypt = cryptByteBlocks
        val skip = skipByteBlocks
        if (crypt < 0 || skip < 0 || (crypt == 0 && skip == 0)) {
            throw DecryptError("cbcs pattern 非法：crypt=$crypt skip=$skip")
        }
        val whole = data.size - (data.size % 16)
        if (skip == 0) {
            val head = aesCbcDecrypt(key, iv, data.copyOfRange(0, whole))
            return head + data.copyOfRange(whole, data.size)
        }
        val out = data.copyOf()
        val group = crypt + skip
        val nblock = whole / 16
        var blk = 0
        while (blk < nblock) {
            var chain = iv
            for (k in 0 until crypt) {
                if (blk >= nblock) break
                val pos = blk * 16
                val c = out.copyOfRange(pos, pos + 16)
                val dec = aesEcbDecryptBlock(key, c)
                val p = ByteArray(16)
                for (i in 0 until 16) p[i] = (dec[i].toInt() xor chain[i].toInt()).toByte()
                System.arraycopy(p, 0, out, pos, 16)
                chain = c
                blk++
            }
            blk += skip
        }
        return out
    }

    fun decryptCbcsSubsamples(
        sample: ByteArray, key: ByteArray, iv: ByteArray, subsamples: List<Pair<Int, Int>>,
        cryptByteBlocks: Int = 1, skipByteBlocks: Int = 9,
    ): ByteArray {
        val out = sample.copyOf()
        var off = 0
        for ((clearLen, encLen) in subsamples) {
            off += clearLen
            val end = off + encLen
            if (encLen != 0) {
                val sub = decryptCbcsRange(
                    out.copyOfRange(off, end), key, iv,
                    cryptByteBlocks = cryptByteBlocks, skipByteBlocks = skipByteBlocks,
                )
                System.arraycopy(sub, 0, out, off, sub.size)
            }
            off = end
        }
        return out
    }

    private fun aesCtrDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** 按 enc 解密单个分片。key 缺省时按 enc.uri 现取。
     *  支持 AES-128 / AES-256 / AES-256-CTR / SAMPLE-AES(cbcs 容器级)。
     *  initBytes：fMP4 初始化段（SAMPLE-AES 需要它拿 tenc）。 */
    fun decryptSegment(
        data: ByteArray, enc: HlsEnc?, seq: Int = 0, key: ByteArray? = null,
        referer: String = "", cookies: Map<String, String>? = null, fetch: KeyFetch? = null,
        initBytes: ByteArray? = null,
    ): ByteArray {
        if (enc == null || enc.method.isEmpty() || enc.method == "NONE") return data
        val iv: (HlsEnc) -> ByteArray = { segmentIv(it, seq) }
        return when (enc.method) {
            "AES-128" -> {
                val k = key ?: resolveAesKey(enc, referer, cookies, fetch, wantLen = 16)
                if (k.size != 16) throw DecryptError("AES-128 key 长度须 16 字节")
                if (data.size % 16 != 0) throw DecryptError("AES 分片长度非 16 的倍数（可能未整片加密或已损坏）")
                stripPkcs7(aesCbcDecrypt(k, iv(enc), data))
            }
            "AES-256" -> {
                val k = key ?: resolveAesKey(enc, referer, cookies, fetch, wantLen = 32)
                if (k.size != 32) throw DecryptError("AES-256 key 长度须 32 字节")
                if (data.size % 16 != 0) throw DecryptError("AES 分片长度非 16 的倍数（可能未整片加密或已损坏）")
                stripPkcs7(aesCbcDecrypt(k, iv(enc), data))
            }
            "AES-256-CTR" -> {
                val k = key ?: resolveAesKey(enc, referer, cookies, fetch, wantLen = 32)
                if (k.size != 32) throw DecryptError("AES-256-CTR key 长度须 32 字节")
                aesCtrDecrypt(k, iv(enc), data)
            }
            "SAMPLE-AES", "SAMPLE-AES-CTR" -> {
                val k = key ?: resolveAesKey(enc, referer, cookies, fetch, wantLen = 16)
                CbcsDecryptor.decrypt(data, initBytes, k)
            }
            else -> throw UnsupportedScheme("HLS 加密方式 ${enc.method} 不支持")
        }
    }

    /** 按 enc.uri 拉取 key；缓存键含 cookie 指纹。wantLen：期望 key 长度（0=自动）。 */
    fun resolveAesKey(
        enc: HlsEnc?, referer: String = "", cookies: Map<String, String>? = null,
        fetch: KeyFetch? = null, useCache: Boolean = true, wantLen: Int = 0,
    ): ByteArray {
        if (enc == null || enc.uri.isEmpty()) throw DecryptError("AES key URI 缺失")
        val f = fetch ?: throw DecryptError("缺少 key 拉取实现")
        val uri = enc.uri
        val cookieFp = (cookies ?: emptyMap()).toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }
        val ck = cacheKey(uri, referer, cookieFp)
        if (useCache) synchronized(lock) { keyCache[ck]?.let { return it } }
        val body = f(uri, referer, cookies) ?: throw DecryptError("取 key 失败：$uri")
        if (body.isEmpty()) throw DecryptError("key 响应为空")
        val key = coerceKey(body, wantLen)
        if (useCache) synchronized(lock) { keyCache[ck] = key }
        return key
    }
}

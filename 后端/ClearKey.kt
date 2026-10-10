package com.taowu.backend.crypto

import com.taowu.backend.net.NetEngine
import kotlinx.serialization.json.*

// ================================================================
// W3C ClearKey：从 MPD 的 ContentProtection 拿 KID/Laurl，按 ClearKey license 格式取内容密钥。
// 仅服务"密钥可达"的 cenc/ClearKey；商业 DRM（Widevine/PlayReady）走 CDM，不经此处。
// ================================================================
object ClearKey {

    data class Info(val kidHex: String?, val laurl: String?)

    /** 从 MPD 文本解析 default_KID 与 clearkey 的 Laurl。 */
    fun parse(mpd: String): Info {
        val kid = Regex("""cenc:default_KID\s*=\s*"([^"]+)"""").find(mpd)
            ?.groupValues?.get(1)?.replace("-", "")?.lowercase()
        val laurl = Regex("""<(?:dashif:)?Laurl[^>]*>([^<]+)</(?:dashif:)?Laurl>""", RegexOption.IGNORE_CASE)
            .find(mpd)?.groupValues?.get(1)?.trim()
        return Info(kid, laurl)
    }

    /** 取内容密钥：POST {kids:[b64url(KID)],type:temporary} → keys[0].k(base64url) → 16B。 */
    fun fetchKey(net: NetEngine, laurl: String, kidHex: String?, headers: Map<String, String>): ByteArray? {
        val kid = kidHex?.let { hexToBytes(it) } ?: return null
        if (kid.size != 16) return null
        val body = """{"kids":["${b64url(kid)}"],"type":"temporary"}"""
        val resp = try {
            net.postJsonRaw(laurl, body, headers, emptyMap(), 20)
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            val json = Json.parseToJsonElement(resp)
            val keys = json.jsonObject["keys"]?.jsonArray ?: return null
            for (k in keys) {
                val kk = (k.jsonObject["k"] as? JsonPrimitive)?.contentOrNull ?: continue
                val key = b64urlDecode(kk)
                if (key.size == 16) return key
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun hexToBytes(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i + 1 < s.length) {
            out[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }

    private fun b64url(b: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun b64urlDecode(s: String): ByteArray {
        val t = s.replace('-', '+').replace('_', '/')
        val padded = t + "=".repeat((4 - t.length % 4) % 4)
        return java.util.Base64.getDecoder().decode(padded)
    }
}

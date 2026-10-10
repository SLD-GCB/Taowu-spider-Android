package com.taowu.backend.crypto

// ================================================================
// cbcs / SAMPLE-AES 容器级解密（ISO/IEC 23001-7 'cbcs' 方案）。
// 盒解析见 Mp4Crypto；本模块只做 cbcs pattern 解密。
// ================================================================
object CbcsDecryptor {

    /** 解密 fMP4 media 片段；init 提供 tenc（可与 media 同一份）。 */
    fun decrypt(media: ByteArray, init: ByteArray?, key: ByteArray): ByteArray {
        if (key.size != 16 && key.size != 32) throw DecryptError("cbcs key 须 16 或 32 字节")
        val info = Mp4Crypto.parse(media, init) ?: return media.copyOf()
        val out = media.copyOf()
        for (s in info.samples) {
            val iv = s.iv ?: info.constantIv ?: continue
            val slice = out.copyOfRange(s.offset, s.offset + s.size)
            val dec = if (s.subsamples != null) {
                StreamDecrypt.decryptCbcsSubsamples(slice, key, iv, s.subsamples, info.crypt, info.skip)
            } else {
                StreamDecrypt.decryptCbcsRange(slice, key, iv, info.crypt, info.skip)
            }
            System.arraycopy(dec, 0, out, s.offset, minOf(dec.size, s.size))
        }
        return out
    }
}

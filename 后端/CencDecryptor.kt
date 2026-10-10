package com.taowu.backend.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// ================================================================
// CENC / ClearKey 容器级解密（ISO/IEC 23001-7 'cenc' 方案 = AES-128-CTR + 子样本）。
// 每个样本用其 IV 派生初始 counter，对"受保护字节"按 16B 块做 CTR 解密；clear 字节跳过、
// counter 只在受保护字节上递进。盒解析见 Mp4Crypto。
// ================================================================
object CencDecryptor {

    /** 解密 fMP4 media 片段；init 提供 tenc（可与 media 同一份）。key 为 16 字节内容密钥。 */
    fun decrypt(media: ByteArray, init: ByteArray?, key: ByteArray): ByteArray {
        if (key.size != 16) throw DecryptError("CENC(cenc) key 须 16 字节")
        val info = Mp4Crypto.parse(media, init) ?: return media.copyOf()
        val out = media.copyOf()
        val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        for (s in info.samples) {
            val iv = s.iv ?: info.constantIv ?: continue
            val counter = ivToCounter(iv)
            val subs = s.subsamples
            if (subs == null) {
                ctrInPlace(out, s.offset, s.size, ecb, counter)
            } else {
                var off = s.offset
                for ((clear, prot) in subs) {
                    off += clear
                    if (prot > 0) ctrInPlace(out, off, prot, ecb, counter)
                    off += prot
                }
            }
        }
        return out
    }

    /** 16B IV → counter 块；8B IV → 左半，右补 8 个 0。 */
    private fun ivToCounter(iv: ByteArray): ByteArray {
        val c = ByteArray(16)
        if (iv.size >= 16) System.arraycopy(iv, 0, c, 0, 16)
        else System.arraycopy(iv, 0, c, 0, iv.size)
        return c
    }

    /** 对 [off, off+len) 做 AES-CTR 解密；counter 就地递进（跨子样本继续）。 */
    private fun ctrInPlace(data: ByteArray, off: Int, len: Int, ecb: Cipher, counter: ByteArray) {
        var pos = off
        val end = off + len
        while (pos < end) {
            val ks = ecb.doFinal(counter)
            val n = minOf(16, end - pos)
            for (i in 0 until n) data[pos + i] = (data[pos + i].toInt() xor ks[i].toInt()).toByte()
            pos += n
            increment(counter)
        }
    }

    private fun increment(counter: ByteArray) {
        for (i in 15 downTo 0) {
            counter[i] = (counter[i] + 1).toByte()
            if (counter[i].toInt() != 0) break
        }
    }
}

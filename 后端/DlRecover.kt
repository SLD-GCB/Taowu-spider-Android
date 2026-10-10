package com.taowu.backend.download

// ================================================================
// 下载层 HTTP 状态归因与恢复提示（移植 video_grab._dl_challenge_table / _classify_dl_403 / _dl_failure_hint）。
// 桌面版还能自动开 Chrome 过 CF/imperva 挑战；安卓无浏览器，自动挑战降级为"提示"。
// ================================================================
object DlRecover {

    private data class Sig(val name: String, val strategy: String, val keywords: List<String>)

    // qm 缺载时的静态回退表（与原项目一致）
    private val TABLE = listOf(
        Sig("cloudflare", "wait_and_refresh",
            listOf("cf-challenge", "just a moment", "cf-browser-verification", "turnstile", "challenge-platform", "checking your browser")),
        Sig("imperva", "wait_and_refresh",
            listOf("incapsula", "imperva", "visid_incap")),
        Sig("generic", "abort",
            listOf("captcha", "验证码", "滑块", "拖动滑块", "g-recaptcha", "h-captcha", "verify you are human")),
    )
    private val AUTO_STRATEGIES = setOf("wait_and_refresh")

    /** 归因：ratelimit / auth / challenge_auto / challenge_manual / hotlink。 */
    fun classify(code: Int, body: ByteArray?): String {
        if (code == 429) return "ratelimit"
        if (code == 401) return "auth"
        if (code != 403 && code != 406 && code != 451) return "hotlink"
        val text = try {
            String(body?.copyOfRange(0, minOf(body.size, 16384)) ?: ByteArray(0), Charsets.UTF_8).lowercase()
        } catch (e: Exception) {
            ""
        }
        var hitAuto = false
        var hitHuman = false
        for (sig in TABLE) {
            if (sig.keywords.any { text.contains(it) }) {
                if (sig.strategy in AUTO_STRATEGIES) hitAuto = true else hitHuman = true
            }
        }
        return when {
            hitAuto -> "challenge_auto"
            hitHuman -> "challenge_manual"
            else -> "hotlink"
        }
    }

    /** 给最终失败的提示文案（人工可读）。 */
    fun hint(code: Int, body: ByteArray?): String = when (classify(code, body)) {
        "challenge_manual" -> "HTTP $code 触发滑块/人工验证码（不自动过），请人工处理后重试"
        "challenge_auto" -> "HTTP $code 人机验证（CF/imperva 类）——安卓无浏览器，无法自动过挑战"
        "ratelimit" -> "限频 $code：请放慢/等待后重试"
        "auth" -> "HTTP $code 疑似需登录/授权，请先登录后再试"
        else -> "HTTP $code 防盗链/签名失效"
    }

    /** 429 退避秒数（0.5~cap 抖动）。 */
    fun backoffSeconds(cap: Double = 30.0): Double {
        val c = if (cap <= 0.0) 0.5 else cap
        return 0.5 + Math.random() * (c - 0.5)
    }
}

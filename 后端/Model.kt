package com.taowu.backend.model

/** 解析路径注记。 */
object Route {
    const val RECIPE = "recipe"
    const val STATIC = "static"
    const val BROWSER = "browser"
    const val BROWSER_API = "browser-api"
    const val NONE = "none"
}

/** DRM 识别（只识别跳过，绝不解密）。 */
object Drm {
    private val KEY_SYSTEMS = mapOf(
        "com.widevine.alpha" to "widevine",
        "com.microsoft.playready" to "playready",
        "com.apple.fps" to "fairplay",
        "org.w3.clearkey" to "clearkey",
    )
    private val COMMERCIAL = setOf("widevine", "playready", "fairplay")

    fun nameFromKeySystem(keySystem: String?): String {
        val ks = (keySystem ?: "").trim().lowercase()
        return KEY_SYSTEMS[ks] ?: ks
    }

    fun isCommercial(scheme: String?): Boolean = (scheme ?: "").trim().lowercase() in COMMERCIAL
}

/** 复合轨子件（DASH 分轨等）。 */
data class Part(
    val mediaClass: String,
    val kind: String,
    val url: String,
    val ext: String,
    val note: String,
)

/**
 * 一条可下载媒体。
 * mediaClass: "video"/"audio"/""（""=纯清单 m3u8/mpd）；kind: "m3u8"/"dash"/"direct"。
 */
data class Track(
    var index: Int,
    val mediaClass: String,
    val kind: String,
    val url: String,
    val ext: String,
    val src: String,
    val pageUrl: String,
    val titleHint: String = "",
    val durationS: Double = 0.0,
    val cookies: Map<String, String> = emptyMap(),
    val drm: List<String> = emptyList(),
    val note: String = "",
    val parts: List<Part> = emptyList(),
    val ephemeral: Boolean = false,
) {
    val isDrmCommercial: Boolean get() = drm.any { Drm.isCommercial(it) }
}

/** 单个 URL 的解析结果。 */
data class PageResolve(
    val url: String,
    val route: String = Route.NONE,
    val tracks: List<Track> = emptyList(),
    val notes: List<String> = emptyList(),
)

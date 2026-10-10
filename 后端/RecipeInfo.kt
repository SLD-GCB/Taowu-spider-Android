package com.taowu.backend.registry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ================================================================
// 配方元信息（供设置页分类展示：视频 / 音频）。
// 分类依据：配方 emit 里出现的 track.media_class；无则按站点族兜底为 video。
// ================================================================

data class RecipeInfo(
    val name: String,
    val desc: String,
    val hosts: List<String>,
    val category: String,   // "video" | "audio"
    val order: Int,
    val source: String = "builtin",   // builtin | user
    val file: String = "",            // user 配方文件名（用于删除）
)

/** 递归收集配方的 media_class 取值。 */
private fun collectMediaClasses(el: JsonElement?, out: MutableSet<String>) {
    when (el) {
        is JsonObject -> {
            for ((k, v) in el) {
                if (k == "media_class") (v as? JsonPrimitive)?.contentOrNull?.let { out.add(it) }
                collectMediaClasses(v, out)
            }
        }
        is JsonArray -> for (v in el) collectMediaClasses(v, out)
        else -> {}
    }
}

/** 配方分类：emit 含 video → 视频；仅含 audio → 音频；都没有 → 视频（如浏览器缝的视频站）。 */
fun classifyRecipeCategory(spec: JsonObject): String {
    val mcs = HashSet<String>()
    collectMediaClasses(spec["emit"], mcs)
    return when {
        mcs.contains("video") -> "video"
        mcs.contains("audio") -> "audio"
        else -> "video"
    }
}

fun recipeInfoOf(
    spec: JsonObject,
    fallbackName: String,
    source: String = "builtin",
    file: String = "",
): RecipeInfo {
    val name = (spec["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: fallbackName
    val desc = (spec["desc"] as? JsonPrimitive)?.contentOrNull ?: ""
    val hosts: List<String> = when (val hs = spec["host_suffix"]) {
        is JsonArray -> hs.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(hs.contentOrNull)
        else -> emptyList()
    }
    val order = (spec["order"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 100
    return RecipeInfo(name, desc, hosts, classifyRecipeCategory(spec), order, source, file)
}

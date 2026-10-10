package com.taowu.backend.json

import kotlinx.serialization.json.*

/** 缺失哨兵：与 null 区分（null 是合法 JSON 值，缺失是"路径没走到"）。 */
object Miss

/** JSON 元素 → 原生值（整数保留为 Long，与 Python json 一致）。 */
fun JsonElement?.toNative(): Any? = when (this) {
    null -> null
    is JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> boolean
        else -> {
            val c = content
            if (c.none { it == '.' || it == 'e' || it == 'E' }) {
                c.toLongOrNull() ?: c.toDoubleOrNull() ?: c
            } else {
                c.toDoubleOrNull() ?: c
            }
        }
    }
    is JsonObject -> LinkedHashMap<String, Any?>().also { m -> for ((k, v) in this) m[k] = v.toNative() }
    is JsonArray -> this.map { it.toNative() }
}

/** 原生值 → JsonElement。 */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Int -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    is Double -> JsonPrimitive(this)
    is Float -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(this.entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is List<*> -> JsonArray(this.map { it.toJsonElement() })
    else -> JsonPrimitive(this.toString())
}

fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
fun JsonElement?.asArr(): JsonArray? = this as? JsonArray
fun JsonElement?.strOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonElement?.primContent(): String? = (this as? JsonPrimitive)?.content

fun JsonObject?.strField(key: String, default: String = ""): String {
    val v = this?.get(key) ?: return default
    val p = v as? JsonPrimitive ?: return default
    return if (p is JsonNull) default else p.content
}

fun JsonObject?.objField(key: String): JsonObject? = this?.get(key).asObj()
fun JsonObject?.arrField(key: String): JsonArray? = this?.get(key).asArr()

/** 该键是否"存在且非空"。 */
fun JsonObject?.truthyField(key: String): Boolean = jsonTruthy(this?.get(key))

/** Python `bool()` 语义。 */
fun jsonTruthy(el: JsonElement?): Boolean = when (el) {
    null -> false
    is JsonNull -> false
    is JsonPrimitive -> when {
        el.isString -> el.content.isNotEmpty()
        el.booleanOrNull != null -> el.boolean
        else -> (el.doubleOrNull ?: 0.0) != 0.0
    }
    is JsonArray -> el.isNotEmpty()
    is JsonObject -> el.isNotEmpty()
}

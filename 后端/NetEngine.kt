package com.taowu.backend.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** GET 结果（含重定向后的最终 URL，供 iframe 深挖记 Referer）。 */
data class TextResponse(val text: String?, val finalUrl: String)

/** 二进制响应（带状态码/Content-Type，供 403 归因与 Range 用）。 */
data class BinResult(val bytes: ByteArray, val status: Int, val contentType: String?)

/** 网络抽象：只暴露"取原文"两个方法；JSON 解析由引擎在上层做。失败返回 null，绝不抛。 */
interface NetEngine {
    fun getRaw(url: String, headers: Map<String, String>, cookies: Map<String, String>, timeoutSec: Int): String?
    fun postJsonRaw(url: String, bodyJson: String?, headers: Map<String, String>, cookies: Map<String, String>, timeoutSec: Int): String?
    /** 取二进制（分片/key 用）。失败返回 null。 */
    fun getBytes(url: String, headers: Map<String, String>, cookies: Map<String, String>, timeoutSec: Int): ByteArray?
    /** GET 文本 + 最终 URL。失败返回 null。 */
    fun getText(url: String, headers: Map<String, String>, cookies: Map<String, String>, timeoutSec: Int): TextResponse?
    /** 带状态码的二进制 GET（可选 Range）。失败返回 null（连接层异常）。 */
    fun getBytesDetailed(
        url: String, headers: Map<String, String>, cookies: Map<String, String>,
        timeoutSec: Int, rangeStart: Long = -1, rangeEnd: Long = -1,
    ): BinResult?
    /** 探测是否支持 Range 及文件总长（GET Range: bytes=0-0 → 206 的 Content-Range）。<0 = 不支持。 */
    fun probeTotalSize(url: String, headers: Map<String, String>, cookies: Map<String, String>, timeoutSec: Int): Long
    /** 只取响应头 Content-Type（HEAD；不支持则退 Range bytes=0-0 只读头）。失败返回 null。 */
    fun headContentType(url: String, headers: Map<String, String>, cookies: Map<String, String>, timeoutSec: Int): String?
}

/** OkHttp 实现。 */
class OkHttpNetEngine(
    private val base: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build(),
) : NetEngine {

    private fun clientFor(timeoutSec: Int): OkHttpClient =
        base.newBuilder().callTimeout(timeoutSec.coerceAtLeast(1).toLong(), TimeUnit.SECONDS).build()

    private fun cookieHeader(cookies: Map<String, String>): String? =
        if (cookies.isEmpty()) null else cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    override fun getRaw(
        url: String,
        headers: Map<String, String>,
        cookies: Map<String, String>,
        timeoutSec: Int,
    ): String? = try {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) b.header(k, v)
        cookieHeader(cookies)?.let { b.header("Cookie", it) }
        clientFor(timeoutSec).newCall(b.build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    override fun postJsonRaw(
        url: String,
        bodyJson: String?,
        headers: Map<String, String>,
        cookies: Map<String, String>,
        timeoutSec: Int,
    ): String? = try {
        val reqBody = (bodyJson ?: "null").toRequestBody("application/json; charset=utf-8".toMediaType())
        val b = Request.Builder().url(url).post(reqBody)
        for ((k, v) in headers) b.header(k, v)
        cookieHeader(cookies)?.let { b.header("Cookie", it) }
        clientFor(timeoutSec).newCall(b.build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    override fun getBytes(
        url: String,
        headers: Map<String, String>,
        cookies: Map<String, String>,
        timeoutSec: Int,
    ): ByteArray? = try {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) b.header(k, v)
        cookieHeader(cookies)?.let { b.header("Cookie", it) }
        clientFor(timeoutSec).newCall(b.build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    } catch (e: Exception) {
        null
    }

    override fun getText(
        url: String,
        headers: Map<String, String>,
        cookies: Map<String, String>,
        timeoutSec: Int,
    ): TextResponse? = try {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) b.header(k, v)
        cookieHeader(cookies)?.let { b.header("Cookie", it) }
        clientFor(timeoutSec).newCall(b.build()).execute().use { resp ->
            if (resp.isSuccessful) TextResponse(resp.body?.string(), resp.request.url.toString()) else null
        }
    } catch (e: Exception) {
        null
    }

    override fun getBytesDetailed(
        url: String,
        headers: Map<String, String>,
        cookies: Map<String, String>,
        timeoutSec: Int,
        rangeStart: Long,
        rangeEnd: Long,
    ): BinResult? = try {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) b.header(k, v)
        cookieHeader(cookies)?.let { b.header("Cookie", it) }
        if (rangeStart >= 0) b.header("Range", "bytes=$rangeStart-${if (rangeEnd >= 0) rangeEnd else ""}")
        clientFor(timeoutSec).newCall(b.build()).execute().use { resp ->
            val body = resp.body?.bytes() ?: ByteArray(0)
            BinResult(body, resp.code, resp.header("Content-Type"))
        }
    } catch (e: Exception) {
        null
    }

    override fun probeTotalSize(
        url: String,
        headers: Map<String, String>,
        cookies: Map<String, String>,
        timeoutSec: Int,
    ): Long = try {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) b.header(k, v)
        cookieHeader(cookies)?.let { b.header("Cookie", it) }
        b.header("Range", "bytes=0-0")
        clientFor(timeoutSec).newCall(b.build()).execute().use { resp ->
            if (resp.code == 206) {
                val cr = resp.header("Content-Range")
                val total = cr?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                if (total > 0) total else -1L
            } else -1L
        }
    } catch (e: Exception) {
        -1L
    }

    override fun headContentType(
        url: String,
        headers: Map<String, String>,
        cookies: Map<String, String>,
        timeoutSec: Int,
    ): String? = try {
        val b = Request.Builder().url(url).head()
        for ((k, v) in headers) b.header(k, v)
        cookieHeader(cookies)?.let { b.header("Cookie", it) }
        clientFor(timeoutSec).newCall(b.build()).execute().use { resp ->
            val ct = resp.header("Content-Type")
            if (!ct.isNullOrEmpty()) ct else null
        }
    } catch (e: Exception) {
        null
    }
}

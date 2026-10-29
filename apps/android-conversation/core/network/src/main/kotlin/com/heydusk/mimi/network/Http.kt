package com.heydusk.mimi.network

import com.heydusk.mimi.domain.json.CanonicalJson
import com.heydusk.mimi.domain.json.JsonValue
import com.heydusk.mimi.domain.json.StrictJson
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlin.coroutines.coroutineContext

/**
 * 云端配置（iOS `NativeAuthConfiguration` + `CatpawBackendOrigin`）。值来自本机 `local.properties` → BuildConfig，
 * 不进 git。三项任一缺失 = 未配置：壳只走本机路径（与 iOS 没有 Local.xcconfig 时一样），不发任何请求。
 */
data class CloudConfig(
    val projectUrl: String,
    val anonKey: String,
    val backendOrigin: String,
    /** 多个工作区时由配置选定；null = 必须恰好一个。 */
    val serverScopeID: java.util.UUID? = null,
) {
    /** Supabase 发行者：`{project}/auth/v1`（与 JWT 的 iss 一致）。 */
    val issuer: String get() = projectUrl.trimEnd('/') + "/auth/v1"

    companion object {
        /** 只接受 https（测试里允许 http://127.0.0.1 / localhost 给 MockWebServer）。 */
        fun from(projectUrl: String?, anonKey: String?, backendOrigin: String?, allowLoopbackHttp: Boolean = false): CloudConfig? {
            val p = projectUrl?.trim().orEmpty()
            val k = anonKey?.trim().orEmpty()
            val b = backendOrigin?.trim().orEmpty()
            if (p.isEmpty() || k.isEmpty() || b.isEmpty()) return null
            if (!acceptable(p, allowLoopbackHttp) || !acceptable(b, allowLoopbackHttp)) return null
            if (k.length > 4096 || k.any { it.code < 0x21 || it.code > 0x7e }) return null
            return CloudConfig(p.trimEnd('/'), k, b.trimEnd('/'))
        }

        private fun acceptable(url: String, loopback: Boolean): Boolean {
            val u = runCatching { URI(url) }.getOrNull() ?: return false
            if (u.userInfo != null || u.host.isNullOrEmpty() || u.query != null || u.fragment != null) return false
            return u.scheme == "https" || (loopback && u.scheme == "http" && (u.host == "127.0.0.1" || u.host == "localhost"))
        }
    }
}

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMillis: Int = 30_000,
    val maxBytes: Int = 65_536,
)

data class HttpResponse(val status: Int, val contentType: String?, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    val mimeType: String? get() = contentType?.substringBefore(';')?.trim()?.lowercase()
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** 网络不可达 / 超时 / 响应过大。调用方映射成各自的「暂时不可用」。 */
class TransportException(message: String, cause: Throwable? = null) : IOException(message, cause)

fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/**
 * 无状态传输：不跟随重定向（重定向一律当失败，令牌不会被带去别的主机）、不缓存、不带 Cookie，响应体有上限。
 * 可取消：协程取消会中断阻塞读取。
 */
class UrlConnectionTransport : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        coroutineContext.ensureActive()
        return runInterruptible(Dispatchers.IO) {
            val connection = (URL(request.url).openConnection() as HttpURLConnection)
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = request.timeoutMillis
                connection.readTimeout = request.timeoutMillis
                connection.requestMethod = request.method
                request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                if (request.body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(request.body.size)
                    connection.outputStream.use { it.write(request.body) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val bytes = stream?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > request.maxBytes) throw TransportException("response too large")
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
                HttpResponse(status, connection.contentType, bytes, headers)
            } catch (e: TransportException) {
                throw e
            } catch (e: IOException) {
                throw TransportException("transport failed", e)
            } finally {
                connection.disconnect()
            }
        }
    }
}

/** 最小 JSON 构造（键按插入顺序；字符串转义与 CanonicalJson 相同）。只用于请求体。 */
object JsonOut {
    fun obj(vararg pairs: Pair<String, Any?>): String = buildString { writeValue(linkedMapOf(*pairs), this) }

    @Suppress("UNCHECKED_CAST")
    private fun writeValue(v: Any?, out: StringBuilder) {
        when (v) {
            null -> out.append("null")
            is String -> CanonicalJson.quote(v, out)
            is Boolean -> out.append(v)
            is Int, is Long -> out.append(v.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, value) in v as Map<String, Any?>) {
                    if (!first) out.append(',')
                    first = false
                    CanonicalJson.quote(k, out); out.append(':'); writeValue(value, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                v.forEachIndexed { i, item -> if (i > 0) out.append(','); writeValue(item, out) }
                out.append(']')
            }
            else -> error("unsupported json value")
        }
    }
}

/** 读取严格 JSON 的小工具。 */
object JsonIn {
    fun parse(bytes: ByteArray, maxBytes: Int = StrictJson.DEFAULT_MAX_BYTES): JsonValue.Obj? =
        runCatching { StrictJson.parseObject(bytes, maxBytes) }.getOrNull()
}

internal fun JsonValue.Obj.str(key: String): String? = (members[key] as? JsonValue.Str)?.value
internal fun JsonValue.Obj.bool(key: String): Boolean? = (members[key] as? JsonValue.Bool)?.value
internal fun JsonValue.Obj.obj(key: String): JsonValue.Obj? = members[key] as? JsonValue.Obj
internal fun JsonValue.Obj.arr(key: String): List<JsonValue>? = (members[key] as? JsonValue.Arr)?.items
internal fun JsonValue.Obj.int(key: String): Long? {
    val n = members[key] as? JsonValue.Num ?: return null
    if (!n.value.isFinite() || n.value != Math.rint(n.value) || kotlin.math.abs(n.value) > 9007199254740991.0) return null
    return n.value.toLong()
}
internal fun JsonValue.Obj.isNull(key: String): Boolean = members[key] == null || members[key] == JsonValue.Null
internal fun String.uuidOrNull(): java.util.UUID? =
    if (Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(this)) runCatching { java.util.UUID.fromString(this) }.getOrNull() else null
internal val SAFE_CODE = Regex("^[a-z0-9_]{1,64}$")

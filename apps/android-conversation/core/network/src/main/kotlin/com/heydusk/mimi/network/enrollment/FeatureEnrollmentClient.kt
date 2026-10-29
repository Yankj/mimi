package com.heydusk.mimi.network.enrollment

import com.heydusk.mimi.network.CloudConfig
import com.heydusk.mimi.network.HttpRequest
import com.heydusk.mimi.network.HttpTransport
import com.heydusk.mimi.network.JsonIn
import com.heydusk.mimi.network.JsonOut
import com.heydusk.mimi.network.TransportException
import com.heydusk.mimi.network.bool
import com.heydusk.mimi.network.obj
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * `catpaw.feature-enrollment.v1`（iOS `FeatureEnrollment`）。Android 只用它开通育儿需要的那一部分（「让小咪整理」）；
 * 邀请码只在输入框与请求体里，不记日志、不落盘。
 */
class FeatureEnrollmentClient(private val config: CloudConfig, private val transport: HttpTransport, private val now: () -> Instant = { Instant.now() }) {
    data class Status(val enrolled: Boolean, val features: Set<String>)

    sealed interface SubmitResult {
        data class Done(val status: Status) : SubmitResult
        data object Invalid : SubmitResult
        data class RateLimited(val until: Instant) : SubmitResult
        data object SignedOut : SubmitResult
        data object Failed : SubmitResult
    }

    suspend fun status(auth: String): Status? {
        val r = runCatching { send("me", "GET", auth, null) }.getOrElse { if (it is CancellationException) throw it; return null } ?: return null
        if (r.first != 200) return null
        return parse(r.second)
    }

    suspend fun submit(auth: String, code: String): SubmitResult {
        if (!validCode(code)) return SubmitResult.Invalid
        val (status, body, retryAfter) = try {
            send("enroll", "POST", auth, JsonOut.obj("invite_code" to code))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return SubmitResult.Failed
        return when (status) {
            200 -> parse(body)?.let { SubmitResult.Done(it) } ?: SubmitResult.Failed
            422 -> SubmitResult.Invalid
            429 -> {
                val seconds = JsonIn.parse(body)?.obj("error")?.members?.get("retry_after_sec")
                    ?.let { (it as? com.heydusk.mimi.domain.json.JsonValue.Num)?.value } ?: retryAfter?.toDoubleOrNull() ?: 900.0
                SubmitResult.RateLimited(now().plusSeconds(seconds.toLong().coerceIn(1, 86_400)))
            }
            401 -> SubmitResult.SignedOut
            else -> SubmitResult.Failed
        }
    }

    private fun parse(body: ByteArray): Status? {
        val o = JsonIn.parse(body) ?: return null
        val enrolled = o.bool("enrolled") ?: return null
        val f = o.obj("features")
        val on = f?.members?.filter { (it.value as? com.heydusk.mimi.domain.json.JsonValue.Bool)?.value == true }?.keys ?: emptySet()
        return Status(enrolled, on)
    }

    private suspend fun send(path: String, method: String, auth: String, body: String?): Triple<Int, ByteArray, String?>? = try {
        val r = transport.send(
            HttpRequest(method, config.backendOrigin + "/catpaw/features/v1/" + path,
                mapOf("Authorization" to auth, "apikey" to config.anonKey, "Content-Type" to "application/json"), body?.toByteArray(), timeoutMillis = 10_000),
        )
        Triple(r.status, r.body, r.header("Retry-After"))
    } catch (e: TransportException) {
        null
    }

    companion object {
        /** iOS 同一规则：去掉分隔符后（可带 CP 前缀）恰好 12 位字母数字。 */
        fun validCode(code: String): Boolean {
            val s = code.uppercase().filter { it.isLetterOrDigit() && it.code < 128 }
            val body = if (s.startsWith("CP")) s.drop(2) else s
            return body.length == 12
        }
    }
}

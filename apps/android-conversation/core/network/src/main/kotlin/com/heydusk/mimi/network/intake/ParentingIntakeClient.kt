package com.heydusk.mimi.network.intake

import com.heydusk.mimi.domain.json.CanonicalJson
import com.heydusk.mimi.domain.json.JsonValue
import com.heydusk.mimi.domain.json.StrictJson
import com.heydusk.mimi.domain.parenting.ParentingIntakeRequest
import com.heydusk.mimi.network.CloudConfig
import com.heydusk.mimi.network.HttpRequest
import com.heydusk.mimi.network.HttpResponse
import com.heydusk.mimi.network.HttpTransport
import com.heydusk.mimi.network.JsonIn
import com.heydusk.mimi.network.JsonOut
import com.heydusk.mimi.network.SAFE_CODE
import com.heydusk.mimi.network.TransportException
import com.heydusk.mimi.network.arr
import com.heydusk.mimi.network.bool
import com.heydusk.mimi.network.int
import com.heydusk.mimi.network.obj
import com.heydusk.mimi.network.str
import com.heydusk.mimi.network.uuidOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** 模型路由的披露（家长点「允许」之前要看到的那段话）。 */
data class IntakeConsentRequest(val title: String, val body: String, val routeHash: String, val expectedRevision: Long)

data class IntakeConversation(val revision: Long, val grantEpoch: Long)

/** 一次整理请求的结果。Unknown = 结果未确认：只能读取原请求，不能再发一次。 */
sealed interface IntakeOutcome {
    data class Completed(val text: String) : IntakeOutcome
    /** [notSent] = 服务端明确没有接收（可以用同一身份重试）；否则是已终止的失败（重试要换新身份）。 */
    data class Failed(val code: String, val notSent: Boolean) : IntakeOutcome
    data object Unknown : IntakeOutcome
}

data class IntakeCancelReceipt(val cancelled: Boolean, val alreadyTerminal: Boolean, val state: String?)

/** 受限命名空间之外的失败（没有权限、未开放、格式不对）。[code] 只给策略用。 */
class IntakeUnavailableException(val status: Int, val code: String?) : Exception("intake unavailable $status")

/**
 * `/catpaw/parenting-intake/v1`（services/edge parenting-intake.mjs）：独立命名空间，服务端冻结为无工具、无记忆、无主动能力，
 * 一个会话只允许一次请求。客户端同样只发这一种请求：不带记忆/审批/UI/设备能力头，context 为空，没有附件。
 * - 正文只经由实时 SSE 送达（服务端存档里是脱敏的哈希），所以完成时校验 `final_text_hash == sha256(拼接文本)`；
 * - 回读（GET events）若只拿到脱敏事件，结果仍是 Unknown：绝不因为「流关了」就当作完成。
 */
class ParentingIntakeClient(private val config: CloudConfig, private val transport: HttpTransport) {
    private fun scopePath(scope: UUID) = PREFIX + "/scopes/" + scope.lower()
    private fun conversationPath(scope: UUID, cid: UUID) = scopePath(scope) + "/conversations/" + cid.lower()
    private fun consentPath(scope: UUID, cid: UUID) = "/catpaw/privacy/v1/scopes/" + scope.lower() + "/conversations/" + cid.lower() + "/model-consent"

    /** 建（或读回）这次整理专用的会话，并核对它属于整理命名空间。 */
    suspend fun openConversation(auth: String, scope: UUID, cid: UUID): IntakeConversation {
        val existing = send(HttpRequest("GET", url(conversationPath(scope, cid)), headers(auth)))
        val response = if (existing.status == 404) {
            send(HttpRequest("POST", url(scopePath(scope) + "/conversations"), headers(auth), JsonOut.obj("conversation_id" to cid.lower()).toByteArray()))
        } else existing
        val o = json(response)
        val c = o.obj("conversation") ?: throw invalid()
        validatePurpose(c)
        if (c.str("id")?.uuidOrNull() != cid || c.str("scope_id")?.uuidOrNull() != scope) throw invalid()
        return IntakeConversation(c.int("revision") ?: throw invalid(), c.int("grant_epoch") ?: throw invalid())
    }

    /** null = 这个会话已允许当前模型路由；否则返回需要家长看过并允许的披露。 */
    suspend fun consent(auth: String, scope: UUID, cid: UUID): IntakeConsentRequest? {
        val route = json(send(HttpRequest("GET", url("/catpaw/privacy/v1/model-route"), headers(auth))))
        val hash = route.str("route_hash")?.takeIf { SHA.matches(it) } ?: throw invalid()
        val ui = route.obj("ui") ?: throw invalid()
        val stored = json(send(HttpRequest("GET", url(consentPath(scope, cid)), headers(auth))))
        val prior = stored.obj("consent")
        if (prior?.str("decision") == "allow" && prior.str("route_hash") == hash) return null
        val title = ui.str("title")?.takeIf { it.toByteArray().size <= 512 } ?: throw invalid()
        val body = ui.str("body")?.takeIf { it.toByteArray().size <= 12_000 } ?: throw invalid()
        val revision = prior?.int("revision") ?: 0
        if (revision < 0) throw invalid()
        return IntakeConsentRequest(title, body, hash, revision)
    }

    suspend fun allowConsent(auth: String, scope: UUID, cid: UUID, consent: IntakeConsentRequest) {
        val body = JsonOut.obj("decision" to "allow", "expected_revision" to consent.expectedRevision, "route_hash" to consent.routeHash)
        val row = json(send(HttpRequest("POST", url(consentPath(scope, cid)), headers(auth), body.toByteArray()))).obj("consent") ?: throw invalid()
        if (row.str("decision") != "allow" || row.str("route_hash") != consent.routeHash || row.int("revision") != consent.expectedRevision + 1) throw invalid()
    }

    /**
     * 发出唯一一次整理请求并读完实时流。传输中断 / 5xx / 回放的 JSON（正文已脱敏）→ Unknown（之后只读）；
     * 404/405（命名空间未开放）→ IntakeUnavailableException；其他 4xx → Failed(notSent = true)。
     */
    suspend fun start(auth: String, scope: UUID, request: ParentingIntakeRequest, conversation: IntakeConversation): IntakeOutcome {
        val messageID = UUID.nameUUIDFromBytes(("mimi.parenting-intake.message:" + request.id).toByteArray())
        val body = JsonOut.obj(
            "turn_id" to request.id.lower(),
            "expected_revision" to conversation.revision,
            "expected_grant_epoch" to conversation.grantEpoch,
            "message" to linkedMapOf("id" to messageID.lower(), "text" to request.prompt),
            "context" to emptyList<Any>(),
        )
        val response = try {
            transport.send(
                HttpRequest(
                    "POST", url(conversationPath(scope, request.conversationID) + "/turns"),
                    headers(auth) + mapOf("Idempotency-Key" to request.id.lower(), "Accept" to "text/event-stream", "X-Catpaw-Use-Memory" to "0"),
                    body.toByteArray(), timeoutMillis = 120_000, maxBytes = 2_000_000,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            return IntakeOutcome.Unknown
        }
        return when {
            response.status == 404 || response.status == 405 -> throw IntakeUnavailableException(response.status, errorCode(response))
            response.status in 400..499 -> IntakeOutcome.Failed(errorCode(response) ?: "parenting_intake_failed", notSent = true)
            response.status !in 200..299 -> IntakeOutcome.Unknown
            response.mimeType == "text/event-stream" -> readStream(response.body, scope, request)
            else -> IntakeOutcome.Unknown // 回放回执：正文在服务端已脱敏
        }
    }

    /** 只读回读：GET …/events。绝不触发新的模型请求。 */
    suspend fun observe(auth: String, scope: UUID, request: ParentingIntakeRequest): IntakeOutcome {
        val response = try {
            transport.send(
                HttpRequest(
                    "GET", url(conversationPath(scope, request.conversationID) + "/turns/" + request.id.lower() + "/events"),
                    headers(auth) + mapOf("Accept" to "text/event-stream"), timeoutMillis = 60_000, maxBytes = 2_000_000,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            return IntakeOutcome.Unknown
        }
        if (response.status == 404) return IntakeOutcome.Failed("parenting_intake_not_found", notSent = false)
        if (response.status !in 200..299) return IntakeOutcome.Unknown
        return if (response.mimeType == "text/event-stream") readStream(response.body, scope, request) else IntakeOutcome.Unknown
    }

    suspend fun cancel(auth: String, scope: UUID, request: ParentingIntakeRequest): IntakeCancelReceipt {
        val o = json(send(HttpRequest("POST", url(conversationPath(scope, request.conversationID) + "/turns/" + request.id.lower() + "/cancel"), headers(auth), "{}".toByteArray())))
        val c = o.obj("cancel") ?: throw invalid()
        if (c.str("intake_contract") != ParentingIntakeRequest.CONTRACT) throw invalid()
        return IntakeCancelReceipt(c.bool("cancelled") == true, c.bool("already_terminal") == true, c.str("state"))
    }

    /** 清理云端临时整理数据（DELETE 会话）。200 + JSON 才算确认。 */
    suspend fun delete(auth: String, scope: UUID, cid: UUID) {
        val response = send(HttpRequest("DELETE", url(conversationPath(scope, cid)), headers(auth), timeoutMillis = 20_000))
        if (response.status == 404) return // 已经不在了
        if (response.status != 200 || response.mimeType != "application/json") throw IntakeUnavailableException(response.status, errorCode(response))
    }

    internal fun readStream(body: ByteArray, scope: UUID, request: ParentingIntakeRequest): IntakeOutcome {
        val text = StringBuilder()
        var redacted = false
        var lastSequence = 0L
        val frames = String(body, Charsets.UTF_8).split("\n\n")
        for (frame in frames) {
            val data = frame.lineSequence().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").removePrefix(" ") }
            if (data.isBlank()) continue
            val e = runCatching { StrictJson.parseObject(data.toByteArray(Charsets.UTF_8), 2_000_000) }.getOrNull() ?: return IntakeOutcome.Unknown
            val type = e.str("type") ?: return IntakeOutcome.Unknown
            if (type !in ALLOWED_EVENTS) return IntakeOutcome.Failed("parenting_intake_output_invalid", notSent = false)
            if (e.str("intake_contract") != ParentingIntakeRequest.CONTRACT || e.str("turn_id")?.uuidOrNull() != request.id ||
                e.str("conversation_id")?.uuidOrNull() != request.conversationID || e.str("scope_id")?.uuidOrNull() != scope
            ) return IntakeOutcome.Failed("parenting_intake_output_invalid", notSent = false)
            val sequence = e.int("sequence") ?: return IntakeOutcome.Unknown
            if (sequence <= lastSequence) continue // 重放的事件
            lastSequence = sequence
            val d = e.obj("data") ?: JsonValue.Obj(linkedMapOf())
            when (type) {
                "text_delta" -> {
                    if (d.bool("intake_redacted") == true) redacted = true
                    else text.append(d.str("text") ?: return IntakeOutcome.Unknown)
                }
                "turn_completed" -> {
                    val hash = d.str("final_text_hash") ?: return IntakeOutcome.Unknown
                    if (redacted) return IntakeOutcome.Unknown
                    if (CanonicalJson.sha256Hex(text.toString().toByteArray(Charsets.UTF_8)) != hash) return IntakeOutcome.Failed("parenting_intake_hash_mismatch", notSent = false)
                    for (key in listOf("task_bindings", "continuations", "revision_work_receipts")) {
                        val list = d.arr(key)
                        if (list != null && list.isNotEmpty()) return IntakeOutcome.Failed("parenting_intake_output_invalid", notSent = false)
                    }
                    return IntakeOutcome.Completed(text.toString())
                }
                "turn_failed" -> {
                    val raw = d.str("code")
                    return IntakeOutcome.Failed(raw?.takeIf { SAFE_CODE.matches(it) } ?: "parenting_intake_failed", notSent = false)
                }
            }
        }
        return IntakeOutcome.Unknown // 流结束但没有终态：结果未确认
    }

    private fun validatePurpose(c: JsonValue.Obj) {
        if (c.str("intake_contract") != ParentingIntakeRequest.CONTRACT) throw invalid()
        for (key in listOf("task_bindings", "pending_approvals", "continuations", "revision_work_receipts")) {
            val raw = c.members[key] ?: continue
            if (raw == JsonValue.Null) continue
            if ((raw as? JsonValue.Arr)?.items?.isEmpty() != true) throw invalid()
        }
    }

    private suspend fun send(request: HttpRequest): HttpResponse = try {
        transport.send(request.copy(maxBytes = 2_000_000))
    } catch (e: CancellationException) {
        throw e
    } catch (e: TransportException) {
        throw IntakeUnavailableException(0, null)
    }

    private fun json(response: HttpResponse): JsonValue.Obj {
        if (response.status !in 200..299) throw IntakeUnavailableException(response.status, errorCode(response))
        if (response.mimeType != "application/json") throw invalid()
        return JsonIn.parse(response.body, 2_000_000) ?: throw invalid()
    }

    private fun errorCode(response: HttpResponse): String? {
        val o = JsonIn.parse(response.body, 65_536) ?: return null
        return (o.obj("error")?.str("code") ?: o.str("code"))?.takeIf { SAFE_CODE.matches(it) }
    }

    private fun headers(auth: String) = mapOf("Authorization" to auth, "apikey" to config.anonKey, "Content-Type" to "application/json")
    private fun url(path: String) = config.backendOrigin + path
    private fun invalid() = IntakeUnavailableException(200, "parenting_intake_invalid_response")
    private fun UUID.lower() = toString().lowercase(Locale.ROOT)

    companion object {
        const val PREFIX = "/catpaw/parenting-intake/v1"
        private val SHA = Regex("^[0-9a-f]{64}$")
        private val ALLOWED_EVENTS = setOf("turn_started", "text_delta", "turn_completed", "turn_failed", "model_route", "reasoning_summary")
    }
}

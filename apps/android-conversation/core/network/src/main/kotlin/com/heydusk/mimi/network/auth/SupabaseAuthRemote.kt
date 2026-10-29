package com.heydusk.mimi.network.auth

import com.heydusk.mimi.domain.json.JsonValue
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
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** 应用会话（catpaw/account/v1 下的接口）失败：状态码/代码只给策略与诊断，不变成用户文案。 */
class ApplicationSessionException(val httpStatus: Int, val code: String?) : Exception("application session $httpStatus") {
    val isRetryable: Boolean get() = httpStatus == 408 || httpStatus == 429 || httpStatus in 500..599
}

/**
 * Supabase Auth 的官方 HTTP 契约（iOS `NativeAuthHTTPRemote` 逐条移植），没有 SDK、没有隐式会话。
 * 响应 ≤ 64 KB；重定向当失败；业务错误码按允许清单映射。
 */
class SupabaseAuthRemote(
    private val config: CloudConfig,
    private val transport: HttpTransport,
    private val now: () -> Instant = { Instant.now() },
) : AuthRemote {

    override suspend fun signInAnonymously(): AuthCredential {
        val dto = token(call("signup", body = "{}")) ?: throw invalid()
        if (!dto.user.isAnonymous) throw invalid()
        return credential(dto)
    }

    override suspend fun signUp(email: String, password: String): AuthRegistration {
        val data = call("signup", body = JsonOut.obj("email" to email, "password" to password))
        token(data)?.let { dto ->
            if (dto.user.isAnonymous) throw invalid()
            return AuthRegistration.Authenticated(credential(dto))
        }
        // 需要邮箱确认：返回的是用户对象（或 {user:…}），绝不能带令牌。
        val o = JsonIn.parse(data, 65_536) ?: throw invalid()
        if (o.members.containsKey("access_token") || o.members.containsKey("refresh_token")) throw invalid()
        val raw = o.obj("user") ?: o
        val user = user(raw) ?: throw invalid()
        if (user.isAnonymous) throw invalid()
        return AuthRegistration.EmailConfirmationRequired
    }

    override suspend fun signIn(email: String, password: String): AuthCredential {
        val dto = token(call("token", grant = "password", body = JsonOut.obj("email" to email, "password" to password))) ?: throw invalid()
        if (dto.user.isAnonymous) throw invalid()
        return credential(dto)
    }

    override suspend fun refresh(refreshToken: String): AuthCredential =
        credential(token(call("token", grant = "refresh_token", body = JsonOut.obj("refresh_token" to refreshToken))) ?: throw invalid())

    override suspend fun exchangeOAuthCode(code: String, verifier: String): AuthCredential {
        OAuthPkce.of(verifier)
        val c = credential(token(call("token", grant = "pkce", body = JsonOut.obj("auth_code" to code, "code_verifier" to verifier))) ?: throw invalid())
        if (c.user.isAnonymous) throw invalid()
        return c
    }

    override suspend fun user(accessToken: String): AuthUser {
        val o = JsonIn.parse(call("user", bearer = accessToken), 65_536) ?: throw invalid()
        return user(o) ?: throw invalid()
    }

    override suspend fun signOut(accessToken: String) {
        call("logout", query = "scope=local", bearer = accessToken, body = "{}")
    }

    override suspend fun revokeApplicationSession(accessToken: String) {
        val o = JsonIn.parse(applicationCall("/catpaw/account/v1/logout", accessToken, post = true)) ?: throw invalid()
        if (o.int("schema_version") != 1L || o.bool("revoked") != true) throw invalid()
    }

    /**
     * `GET /catpaw/account/v1/session`：服务端主体必须就是当前登录的主体；只认有 `local_output` 权限、带 profile/workspace 的作用域。
     * 配置了 serverScopeID 就必须在其中；否则必须恰好一个。
     */
    suspend fun applicationScope(accessToken: String, subject: UUID): UUID {
        val o = JsonIn.parse(applicationCall("/catpaw/account/v1/session", accessToken)) ?: throw invalid()
        if (o.int("schema_version") != 1L || o.str("subject_id")?.uuidOrNull() != subject) throw invalid()
        val scopes = o.arr("scopes") ?: throw invalid()
        val valid = scopes.mapNotNull { row ->
            val r = row as? JsonValue.Obj ?: return@mapNotNull null
            if (r.obj("permissions")?.bool("local_output") != true) return@mapNotNull null
            if (r.str("profile_id")?.uuidOrNull() == null || r.str("workspace_id")?.uuidOrNull() == null) return@mapNotNull null
            r.str("scope_id")?.uuidOrNull()
        }
        config.serverScopeID?.let { selected -> if (selected in valid) return selected else throw invalid() }
        return valid.singleOrNull() ?: throw invalid()
    }

    private suspend fun applicationCall(route: String, accessToken: String, post: Boolean = false): ByteArray {
        val request = HttpRequest(
            method = if (post) "POST" else "GET",
            url = config.backendOrigin + route,
            headers = mapOf("Authorization" to "Bearer $accessToken", "apikey" to config.anonKey, "Content-Type" to "application/json"),
            body = if (post) "{}".toByteArray() else null,
            timeoutMillis = 10_000,
        )
        val response = send(request)
        if (response.status !in 200..299) {
            val o = JsonIn.parse(response.body, 65_536)
            val raw = o?.str("code") ?: o?.obj("error")?.str("code")
            throw ApplicationSessionException(response.status, raw?.takeIf { SAFE_CODE.matches(it) })
        }
        return response.body
    }

    private suspend fun send(request: HttpRequest): HttpResponse = try {
        transport.send(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: TransportException) {
        throw AuthException(AuthException.Kind.Unavailable)
    }

    private suspend fun call(route: String, grant: String? = null, query: String? = null, bearer: String? = null, body: String? = null): ByteArray {
        val q = listOfNotNull(grant?.let { "grant_type=" + URLEncoder.encode(it, Charsets.UTF_8) }, query).joinToString("&")
        val headers = buildMap {
            put("apikey", config.anonKey)
            put("Accept", "application/json")
            bearer?.let { put("Authorization", "Bearer $it") }
            if (body != null) put("Content-Type", "application/json")
        }
        val url = config.projectUrl + "/auth/v1/" + route + (if (q.isEmpty()) "" else "?$q")
        val response = send(HttpRequest(if (body != null) "POST" else "GET", url, headers, body?.toByteArray()))
        if (response.status !in 200..299) {
            val o = JsonIn.parse(response.body, 65_536)
            when (o?.str("code") ?: o?.str("error_code")) {
                "email_not_confirmed" -> throw AuthException(AuthException.Kind.EmailNotConfirmed)
                "signup_disabled" -> throw AuthException(AuthException.Kind.SignupUnavailable)
                "email_address_not_authorized", "email_not_authorized" -> throw AuthException(AuthException.Kind.EmailNotAuthorized)
                "weak_password" -> throw AuthException(AuthException.Kind.WeakPassword)
                "anonymous_provider_disabled", "anonymous_sign_ins_disabled" -> throw AuthException(AuthException.Kind.AnonymousUnavailable)
            }
        }
        return when {
            response.status == 200 && route != "logout" -> response.body
            response.status == 204 && route == "logout" -> response.body
            response.status == 400 || response.status == 422 ->
                throw AuthException(if (route == "token" && grant == "password") AuthException.Kind.InvalidCredentials else AuthException.Kind.LoginRequired)
            response.status == 401 || response.status == 403 -> throw AuthException(AuthException.Kind.LoginRequired)
            response.status == 429 -> throw AuthException(AuthException.Kind.RateLimited)
            else -> throw AuthException(AuthException.Kind.Unavailable)
        }
    }

    private class TokenDto(val access: String, val refresh: String, val type: String, val expiresIn: Long, val user: AuthUser)

    private fun token(bytes: ByteArray): TokenDto? {
        val o = JsonIn.parse(bytes, 65_536) ?: return null
        val access = o.str("access_token") ?: return null
        val refresh = o.str("refresh_token") ?: return null
        val type = o.str("token_type") ?: return null
        val expires = o.int("expires_in") ?: return null
        val user = o.obj("user")?.let(::user) ?: return null
        return TokenDto(access, refresh, type, expires, user)
    }

    private fun user(o: JsonValue.Obj): AuthUser? {
        val id = o.str("id")?.uuidOrNull() ?: return null
        val anonymous = o.bool("is_anonymous") ?: return null
        val email = o.members["email"].let { if (it is JsonValue.Str) it.value else null }
        return AuthUser(id, email, anonymous)
    }

    private fun credential(dto: TokenDto): AuthCredential {
        if (!dto.type.equals("bearer", ignoreCase = true) || dto.expiresIn !in 1..86_400) throw invalid()
        return AuthCredential(config.issuer, dto.user, dto.access, dto.refresh, now().plusSeconds(dto.expiresIn))
    }

    private fun invalid() = AuthException(AuthException.Kind.InvalidResponse)
}

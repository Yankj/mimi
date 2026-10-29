package com.heydusk.mimi.network.auth

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Supabase OAuth（PKCE）—— GitHub / Google。客户端密钥只在 Supabase；应用只持有一次性的 verifier（只在内存）。 */
enum class OAuthProvider(val raw: String, val displayName: String) { GitHub("github", "GitHub"), Google("google", "Google") }

object OAuthRedirect {
    const val SCHEME = "catpawm"
    const val HOST = "auth-callback"
    const val URL = "catpawm://auth-callback"
}

class OAuthPkce private constructor(val verifier: String, val challenge: String) {
    companion object {
        const val METHOD = "s256"
        private val ALLOWED = Regex("^[A-Za-z0-9._~-]+$")

        fun of(verifier: String): OAuthPkce {
            if (verifier.length !in 43..128 || !ALLOWED.matches(verifier)) throw AuthException(AuthException.Kind.InvalidConfiguration)
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return OAuthPkce(verifier, base64Url(digest))
        }

        fun generate(random: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } }): OAuthPkce {
            val bytes = random(48)
            if (bytes.size != 48) throw AuthException(AuthException.Kind.Storage)
            return of(base64Url(bytes)) // 64 个字符
        }

        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

sealed interface OAuthCallback {
    data class Code(val code: String) : OAuthCallback
    data object Cancelled : OAuthCallback
    data class Failure(val code: String?) : OAuthCallback

    companion object {
        private val CODE = Regex("^[A-Za-z0-9._-]{8,512}$")
        private val SAFE = Regex("^[a-z0-9_]{1,64}$")

        /** 只接受 `catpawm://auth-callback?code=…`（PKCE）或错误回跳；片段里带令牌的隐式流一律拒绝。重复参数有歧义，拒绝。 */
        fun parse(url: String): OAuthCallback {
            val u = runCatching { URI(url) }.getOrNull() ?: throw AuthException(AuthException.Kind.CallbackUnsupported)
            if (u.scheme?.lowercase() != OAuthRedirect.SCHEME || u.host?.lowercase() != OAuthRedirect.HOST || u.userInfo != null || u.port != -1 ||
                !(u.rawPath.isNullOrEmpty() || u.rawPath == "/")
            ) throw AuthException(AuthException.Kind.CallbackUnsupported)
            val items = HashMap<String, String>()
            for (part in listOfNotNull(u.rawQuery, u.rawFragment)) {
                for (pair in part.split('&')) {
                    if (pair.isEmpty()) continue
                    val name = decode(pair.substringBefore('='))
                    val value = if ('=' in pair) decode(pair.substringAfter('=')) else ""
                    if (items.put(name, value) != null) throw AuthException(AuthException.Kind.InvalidResponse)
                }
            }
            if ("access_token" in items || "refresh_token" in items) throw AuthException(AuthException.Kind.CallbackUnsupported)
            items["error"]?.let { error ->
                val raw = items["error_code"] ?: error
                val code = raw.takeIf { SAFE.matches(it) }
                return if (code == "access_denied") Cancelled else Failure(code)
            }
            val code = items["code"]?.takeIf { CODE.matches(it) } ?: throw AuthException(AuthException.Kind.InvalidResponse)
            return Code(code)
        }

        private fun decode(s: String) = URLDecoder.decode(s, Charsets.UTF_8)
    }
}

object OAuthRequest {
    /** GET {project}/auth/v1/authorize —— 用 Custom Tabs 打开。 */
    fun authorizeUrl(projectUrl: String, provider: OAuthProvider, pkce: OAuthPkce, redirect: String = OAuthRedirect.URL): String {
        fun q(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
        return projectUrl.trimEnd('/') + "/auth/v1/authorize?provider=" + q(provider.raw) + "&redirect_to=" + q(redirect) +
            "&code_challenge=" + q(pkce.challenge) + "&code_challenge_method=" + OAuthPkce.METHOD
    }
}

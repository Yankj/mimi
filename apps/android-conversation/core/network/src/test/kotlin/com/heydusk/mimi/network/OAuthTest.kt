package com.heydusk.mimi.network

import com.heydusk.mimi.network.auth.AuthException
import com.heydusk.mimi.network.auth.OAuthCallback
import com.heydusk.mimi.network.auth.OAuthPkce
import com.heydusk.mimi.network.auth.OAuthProvider
import com.heydusk.mimi.network.auth.OAuthRequest
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OAuthTest {
    @Test fun pkceMatchesRfc7636Vector() {
        val p = OAuthPkce.of("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", p.challenge)
    }

    @Test fun generatedVerifierIsLongAndUrlSafe() {
        val a = OAuthPkce.generate(); val b = OAuthPkce.generate()
        assertTrue(a.verifier.length in 43..128 && a.verifier.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertTrue(a.verifier != b.verifier)
    }

    @Test fun authorizeUrlCarriesProviderRedirectAndChallenge() {
        val p = OAuthPkce.of("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        val url = URI(OAuthRequest.authorizeUrl("https://p.example", OAuthProvider.GitHub, p))
        assertEquals("/auth/v1/authorize", url.path)
        val q = url.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        assertEquals("github", q["provider"])
        assertEquals("catpawm://auth-callback", q["redirect_to"])
        assertEquals(p.challenge, q["code_challenge"])
        assertEquals("s256", q["code_challenge_method"])
    }

    @Test fun callbackAcceptsOnlyPkceCode() {
        assertEquals(OAuthCallback.Code("abcdefgh1234"), OAuthCallback.parse("catpawm://auth-callback?code=abcdefgh1234"))
        assertIs<OAuthCallback.Failure>(OAuthCallback.parse("catpawm://auth-callback?error=server_error&error_code=bad_oauth_state"))
        assertFailsWith<AuthException> { OAuthCallback.parse("catpawm://auth-callback#access_token=x&refresh_token=y") }
        assertFailsWith<AuthException> { OAuthCallback.parse("https://evil.example/auth-callback?code=abcdefgh1234") }
        assertFailsWith<AuthException> { OAuthCallback.parse("catpawm://other?code=abcdefgh1234") }
        assertFailsWith<AuthException> { OAuthCallback.parse("catpawm://auth-callback?code=abcdefgh1234&code=zzzzzzzz9999") }
        assertFailsWith<AuthException> { OAuthCallback.parse("catpawm://auth-callback?code=abcdefgh1234&access_token=t") }
    }
}

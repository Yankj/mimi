package com.heydusk.mimi.network

import com.heydusk.mimi.network.auth.AuthException
import com.heydusk.mimi.network.auth.AuthRegistration
import com.heydusk.mimi.network.auth.SupabaseAuthRemote
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class SupabaseAuthRemoteTest {
    private val server = MockWebServer()
    private val user = UUID.randomUUID()
    private lateinit var remote: SupabaseAuthRemote

    @BeforeTest fun up() { server.start(); remote = SupabaseAuthRemote(server.config(), UrlConnectionTransport()) }
    @AfterTest fun down() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun passwordSignInPostsGrantAndApiKey() = runTest {
        server.enqueue(json(tokenJson(user)))
        val c = remote.signIn("a@example.com", "secret-pass")
        assertEquals(user, c.user.id)
        val r = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=password", r.path)
        assertEquals("test-anon-key", r.getHeader("apikey"))
        assertTrue(r.body.readUtf8().contains("\"email\":\"a@example.com\""))
        assertTrue(!c.toString().contains("access-1"), "toString must not leak tokens")
    }

    @Test fun badPasswordMapsToInvalidCredentials() = runTest {
        server.enqueue(json("""{"code":"invalid_credentials"}""", 400))
        assertEquals(AuthException.Kind.InvalidCredentials, assertFailsWith<AuthException> { remote.signIn("a@example.com", "x") }.kind)
    }

    @Test fun signUpWithoutSessionNeedsConfirmationAndRejectsTokens() = runTest {
        server.enqueue(json(userJson(user)))
        assertEquals(AuthRegistration.EmailConfirmationRequired, remote.signUp("a@example.com", "longpassword"))
        server.enqueue(json("""{"id":"$user","is_anonymous":false,"access_token":"x"}"""))
        assertFailsWith<AuthException> { remote.signUp("a@example.com", "longpassword") }
    }

    @Test fun pkceExchangeSendsVerifierAndRejectsAnonymous() = runTest {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        server.enqueue(json(tokenJson(user)))
        remote.exchangeOAuthCode("code-123456", verifier)
        val r = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=pkce", r.path)
        assertTrue(r.body.readUtf8().contains(verifier))
        server.enqueue(json(tokenJson(user, anonymous = true)))
        assertFailsWith<AuthException> { remote.exchangeOAuthCode("code-123456", verifier) }
    }

    @Test fun redirectsAndServerErrorsAreFailures() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/"))
        assertEquals(AuthException.Kind.Unavailable, assertFailsWith<AuthException> { remote.refresh("r") }.kind)
        server.enqueue(json("{}", 429))
        assertEquals(AuthException.Kind.RateLimited, assertFailsWith<AuthException> { remote.refresh("r") }.kind)
        server.enqueue(json("""{"code":"refresh_token_not_found"}""", 400))
        assertEquals(AuthException.Kind.LoginRequired, assertFailsWith<AuthException> { remote.refresh("r") }.kind)
    }

    @Test fun applicationScopeRequiresMatchingSubjectAndSingleUsableScope() = runTest {
        val scope = UUID.randomUUID()
        fun row(id: UUID, ok: Boolean) = """{"scope_id":"$id","profile_id":"${UUID.randomUUID()}","workspace_id":"${UUID.randomUUID()}","permissions":{"local_output":$ok}}"""
        server.enqueue(json("""{"schema_version":1,"subject_id":"$user","scopes":[${row(scope, true)},${row(UUID.randomUUID(), false)}]}"""))
        assertEquals(scope, remote.applicationScope("tok", user))
        assertEquals("/catpaw/account/v1/session", server.takeRequest().path)
        server.enqueue(json("""{"schema_version":1,"subject_id":"${UUID.randomUUID()}","scopes":[${row(scope, true)}]}"""))
        assertFailsWith<AuthException> { remote.applicationScope("tok", user) }
        server.enqueue(json("""{"schema_version":1,"subject_id":"$user","scopes":[${row(scope, true)},${row(UUID.randomUUID(), true)}]}"""))
        assertFailsWith<AuthException> { remote.applicationScope("tok", user) }
    }
}

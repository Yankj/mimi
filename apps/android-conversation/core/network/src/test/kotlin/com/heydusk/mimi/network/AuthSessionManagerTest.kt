package com.heydusk.mimi.network

import com.heydusk.mimi.network.auth.AuthCredential
import com.heydusk.mimi.network.auth.AuthException
import com.heydusk.mimi.network.auth.AuthRegistration
import com.heydusk.mimi.network.auth.AuthRemote
import com.heydusk.mimi.network.auth.AuthSessionManager
import com.heydusk.mimi.network.auth.AuthSnapshot
import com.heydusk.mimi.network.auth.AuthUser
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

private const val ISSUER = "https://p.example/auth/v1"

private class FakeRemote(val user: UUID) : AuthRemote {
    val refreshes = AtomicInteger()
    var refreshGate: CompletableDeferred<Unit>? = null
    var refreshError: AuthException? = null
    var revokeFails = false
    val calls = mutableListOf<String>()
    var nextAccess = 1
    fun cred(anon: Boolean = false, exp: Long = 3600) = AuthCredential(ISSUER, AuthUser(user, if (anon) null else "a@example.com", anon), "h.acc${nextAccess++}.s", "ref-$nextAccess", Instant.now().plusSeconds(exp))
    override suspend fun signInAnonymously() = cred(anon = true).also { calls += "anon" }
    override suspend fun signUp(email: String, password: String): AuthRegistration = AuthRegistration.EmailConfirmationRequired
    override suspend fun signIn(email: String, password: String) = cred().also { calls += "signIn" }
    override suspend fun refresh(refreshToken: String): AuthCredential {
        refreshes.incrementAndGet(); calls += "refresh"
        refreshGate?.await()
        refreshError?.let { throw it }
        return cred()
    }
    override suspend fun user(accessToken: String) = AuthUser(user, "a@example.com", calls.lastOrNull() == "anon")
    override suspend fun signOut(accessToken: String) { calls += "logout" }
    override suspend fun revokeApplicationSession(accessToken: String) { calls += "revoke"; if (revokeFails) throw AuthException(AuthException.Kind.Unavailable) }
    override suspend fun exchangeOAuthCode(code: String, verifier: String) = cred().also { calls += "pkce" }
}

class AuthSessionManagerTest {
    private val user = UUID.randomUUID()

    @Test fun signInPersistsAndClearsSignedOutFence() = runTest {
        val vault = MemoryVault(); val remote = FakeRemote(user)
        val m = AuthSessionManager(ISSUER, remote, vault, vault)
        m.signIn("a@example.com", "pw123456")
        assertEquals(AuthSnapshot.Phase.Authenticated, m.snapshot().phase)
        assertEquals(user, vault.stored?.user?.id)
        assertTrue(!vault.fence)
        assertTrue(m.authorization(user).startsWith("Bearer h.acc"))
    }

    @Test fun concurrentAuthorizationsShareOneRefresh() = runTest {
        val vault = MemoryVault(); val remote = FakeRemote(user)
        vault.stored = credential(user, Instant.now().plusSeconds(3600), ISSUER)
        val m = AuthSessionManager(ISSUER, remote, vault, vault, flightScope = backgroundScope)
        m.restore()
        // 让会话即将过期，三个并发请求只能触发一次刷新。
        vault.stored = null
        val near = credential(user, Instant.now().plusSeconds(5), ISSUER)
        val field = AuthSessionManager::class.java.getDeclaredField("credential").apply { isAccessible = true }
        field.set(m, near)
        remote.refreshGate = CompletableDeferred()
        val waiters = (1..3).map { async { m.authorization(user) } }
        testScheduler.advanceUntilIdle()
        remote.refreshGate!!.complete(Unit)
        val tokens = waiters.awaitAll()
        assertEquals(1, remote.refreshes.get())
        assertEquals(1, tokens.toSet().size)
        assertEquals(tokens.first().removePrefix("Bearer "), vault.stored?.accessToken)
    }

    @Test fun uncertainRefreshDoesNotSilentlyMintNewGuest() = runTest {
        val vault = MemoryVault(); val remote = FakeRemote(user)
        // 上次进程在刷新途中被杀：栅栏已落下、轮换日志没写成。
        vault.stored = credential(user, Instant.now().minusSeconds(10), ISSUER)
        vault.fence = true
        val m = AuthSessionManager(ISSUER, remote, vault, vault)
        val e = assertFailsWith<AuthException> { m.startExperience() }
        assertEquals(AuthException.Kind.SessionRecoveryRequired, e.kind)
        assertTrue("anon" !in remote.calls, "must not create a new anonymous identity")
        assertEquals(0, remote.refreshes.get(), "must not replay an old refresh token")
    }

    @Test fun journaledRotationIsRecoveredOnRestore() = runTest {
        val vault = MemoryVault(); val remote = FakeRemote(user)
        vault.stored = credential(user, Instant.now().minusSeconds(10), ISSUER)
        vault.pending = credential(user, Instant.now().plusSeconds(3600), ISSUER, access = "h.rotated.s")
        vault.fence = true
        val m = AuthSessionManager(ISSUER, remote, vault, vault)
        m.restore()
        assertEquals(AuthSnapshot.Phase.Authenticated, m.snapshot().phase)
        assertEquals("h.rotated.s", vault.stored?.accessToken)
        assertNull(vault.pending)
        assertEquals(0, remote.refreshes.get())
    }

    @Test fun refreshNetworkFailureAfterJournalKeepsRecoveryInsteadOfExpiring() = runTest {
        val vault = MemoryVault(); val remote = FakeRemote(user)
        vault.stored = credential(user, Instant.now().minusSeconds(10), ISSUER)
        remote.refreshError = AuthException(AuthException.Kind.Unavailable)
        val m = AuthSessionManager(ISSUER, remote, vault, vault)
        assertFailsWith<AuthException> { m.restore() }
        assertTrue(vault.fence, "fence stays down: the old refresh token's fate is unknown")
    }

    @Test fun signOutClearsLocallyEvenWhenServerRevokeFails() = runTest {
        val vault = MemoryVault(); val remote = FakeRemote(user)
        val m = AuthSessionManager(ISSUER, remote, vault, vault)
        m.signIn("a@example.com", "pw123456")
        remote.revokeFails = true
        m.signOut()
        assertNull(vault.stored); assertNull(vault.pending); assertTrue(vault.fence)
        assertEquals(AuthSnapshot.Phase.SignedOut, m.snapshot().phase)
        assertEquals(AuthSessionManager.REMOTE_SIGN_OUT_UNCONFIRMED, m.snapshot().message)
        assertTrue(remote.calls.containsAll(listOf("revoke", "logout")))
        assertFailsWith<AuthException> { m.authorization(user) }
    }

    @Test fun wrongIssuerCredentialIsDiscarded() = runTest {
        val vault = MemoryVault(); val remote = FakeRemote(user)
        vault.stored = credential(user, Instant.now().plusSeconds(3600), issuer = "https://other.example/auth/v1")
        val m = AuthSessionManager(ISSUER, remote, vault, vault)
        runCatching { m.restore() }
        assertTrue(m.snapshot().phase != AuthSnapshot.Phase.Authenticated)
    }
}

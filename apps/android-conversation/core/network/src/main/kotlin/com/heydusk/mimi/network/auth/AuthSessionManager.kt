package com.heydusk.mimi.network.auth

import com.heydusk.mimi.domain.account.AccountEpoch
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers

/**
 * iOS `NativeAuthSessionManager` 的移植。状态只在 [lock] 里短暂修改；每次挂起回来都用 [generation] 核对
 * （与 actor 的可重入语义一样：中途退出/切换会让旧流程作废）。
 *
 * 安全围栏（与 iOS 同一套）：
 * - 刷新前先同步把「已退出」屏障置为 true：刷新途中崩溃，冷启动不会把可能已被服务端吊销的旧刷新令牌再交一次；
 * - 刷新拿到新令牌后、/user 验证前先写「轮换日志」（pending）；冷启动只从日志里的那一份继续，且只做只读验证；
 * - 屏障为 true 又没有同一账号的日志 = 「不确定的刷新」：startExperience 拒绝悄悄造一个新的体验账号（sessionRecoveryRequired）；
 * - 同一时刻只有一个刷新在飞，所有等待者共享它；取消的等待者只是离开，不取消别人的刷新；
 * - 发布任何「没有可用会话」的状态时，同步让 [accountBarrier] 退出，早于观察者收到快照。
 */
class AuthSessionManager(
    private val issuer: String,
    private val remote: AuthRemote,
    private val vault: AuthVault,
    private val barrier: AuthLogoutBarrier,
    private val now: () -> Instant = { Instant.now() },
    private val accountBarrier: AccountEpoch? = null,
    private val flightScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val lock = Any()
    private var logoutInProgress = false
    private var generation = 0
    private var credential: AuthCredential? = null
    private var refreshFlight: Flight? = null
    private var pendingPersistence: AuthCredential? = null
    private var recoveryCandidate: AuthCredential? = null
    private val _state = MutableStateFlow(AuthSnapshot(AuthSnapshot.Phase.SignedOut, null, 0, null))
    val state: StateFlow<AuthSnapshot> = _state.asStateFlow()

    private class Flight(val id: UUID, val epoch: Int, val task: Deferred<AuthCredential>)

    fun snapshot(): AuthSnapshot = _state.value
    val currentUser: AuthUser? get() = synchronized(lock) { credential?.user }

    fun rotationInFlight(): Boolean = synchronized(lock) {
        _state.value.phase in setOf(AuthSnapshot.Phase.Restoring, AuthSnapshot.Phase.Refreshing, AuthSnapshot.Phase.SigningIn) || refreshFlight != null
    }

    private fun journaledRotation(): AuthCredential? {
        val pending = runCatching { vault.loadPending() }.getOrNull() ?: return null
        val stored = runCatching { vault.load() }.getOrNull()
        if (pending.issuer != issuer || stored == null || stored.issuer != pending.issuer || stored.user.id != pending.user.id) {
            runCatching { vault.clearPending() }
            return null
        }
        return pending
    }

    suspend fun restore() {
        currentCoroutineContext().ensureActive()
        val epoch: Int
        synchronized(lock) {
            if (logoutInProgress || _state.value.phase == AuthSnapshot.Phase.Restoring || _state.value.phase == AuthSnapshot.Phase.SigningIn) throw err(AuthException.Kind.Busy)
            if (pendingPersistence != null) throw err(AuthException.Kind.Storage)
        }
        credential?.let { authorization(it.user.id); return }
        synchronized(lock) { generation += 1; epoch = generation; publish(AuthSnapshot.Phase.Restoring) }
        try {
            val saved: AuthCredential
            val candidateFromRecovery = recoveryCandidate
            if (candidateFromRecovery != null) saved = candidateFromRecovery
            else if (storage { barrier.isSignedOut() }) {
                val journaled = journaledRotation() ?: run { publish(AuthSnapshot.Phase.SignedOut); return }
                recoveryCandidate = journaled; saved = journaled
            } else {
                saved = storage { vault.load() } ?: run { publish(AuthSnapshot.Phase.SignedOut); return }
            }
            if (saved.issuer != issuer) { expireLocally(); return }
            val candidate: AuthCredential
            if (recoveryCandidate != null) {
                if (saved.expiresAt > now().plusSeconds(30)) candidate = saved
                else {
                    candidate = remote.refresh(saved.refreshToken)
                    checkActive(epoch)
                    if (candidate.user.id != saved.user.id) throw err(AuthException.Kind.InvalidResponse)
                    validate(candidate, candidate.user)
                    runCatching { vault.savePending(candidate) }
                    recoveryCandidate = candidate
                }
            } else if (saved.expiresAt <= now().plusSeconds(30)) {
                storage { barrier.setSignedOut(true) }
                candidate = remote.refresh(saved.refreshToken)
                checkActive(epoch)
                if (candidate.user.id != saved.user.id) throw err(AuthException.Kind.InvalidResponse)
                validate(candidate, candidate.user)
                runCatching { vault.savePending(candidate) } // /user 之前写日志：旧刷新令牌在服务端已被吊销
                recoveryCandidate = candidate
            } else candidate = saved
            val user = remote.user(candidate.accessToken)
            checkActive(epoch)
            validate(candidate, user)
            if (recoveryCandidate != null) persistVerified(candidate, epoch)
            else synchronized(lock) { checkActive(epoch); credential = candidate; publish(AuthSnapshot.Phase.Authenticated) }
        } catch (e: Throwable) {
            if (epoch != generation) throw err(AuthException.Kind.Cancelled)
            credential = null
            if (e is CancellationException) { closeLocally(); throw e }
            val safe = (e as? AuthException)?.kind ?: AuthException.Kind.Storage
            if (safe == AuthException.Kind.LoginRequired || safe == AuthException.Kind.InvalidResponse) expireLocally()
            else publish(if (safe == AuthException.Kind.Storage) AuthSnapshot.Phase.StorageBlocked else AuthSnapshot.Phase.SignedOut, safe.message)
            throw err(safe)
        }
    }

    /** 体验账号：启动预热与显式入口共用。不确定的刷新绝不悄悄换一个新身份。 */
    suspend fun startExperience() {
        synchronized(lock) {
            if (logoutInProgress || _state.value.phase == AuthSnapshot.Phase.Restoring || _state.value.phase == AuthSnapshot.Phase.SigningIn) throw err(AuthException.Kind.Busy)
            if (pendingPersistence != null || _state.value.phase == AuthSnapshot.Phase.StorageBlocked) throw err(AuthException.Kind.Storage)
        }
        currentCoroutineContext().ensureActive()
        if (recoveryCandidate != null) { restore(); return }
        val saved = try { vault.load() } catch (e: Exception) { throw err(AuthException.Kind.Storage) }
        if (credential != null || saved != null) {
            if (credential == null && storage { barrier.isSignedOut() } && journaledRotation() == null) throw err(AuthException.Kind.SessionRecoveryRequired)
            restore()
            if (_state.value.phase != AuthSnapshot.Phase.Authenticated) throw err(AuthException.Kind.LoginRequired)
            return
        }
        restore()
        val epoch = beginSignIn { credential == null && pendingPersistence == null && _state.value.phase == AuthSnapshot.Phase.SignedOut }
        signInFlow(epoch) {
            val candidate = remote.signInAnonymously(); checkActive(epoch)
            if (!candidate.user.isAnonymous) throw err(AuthException.Kind.InvalidResponse)
            validate(candidate, candidate.user)
            recoveryCandidate = candidate
            val user = remote.user(candidate.accessToken); checkActive(epoch)
            validate(candidate, user); persistVerified(candidate, epoch)
        }
    }

    suspend fun signIn(email: String, password: String) {
        if (email.isEmpty() || email.toByteArray().size > 254 || '@' !in email || password.isEmpty() || password.toByteArray().size > 4096) throw err(AuthException.Kind.InvalidCredentials)
        val epoch = beginSignIn { credential == null }
        signInFlow(epoch) {
            val candidate = remote.signIn(email, password); checkActive(epoch)
            val user = remote.user(candidate.accessToken); checkActive(epoch)
            validate(candidate, user); persistVerified(candidate, epoch)
        }
    }

    /** true = 需要去邮箱确认（没有会话）。 */
    suspend fun signUp(email: String, password: String): Boolean {
        if (email.isEmpty() || email.toByteArray().size > 254 || '@' !in email || password.toByteArray().size < 8 || password.toByteArray().size > 4096) throw err(AuthException.Kind.InvalidCredentials)
        val epoch = beginSignIn { credential == null }
        var confirm = false
        signInFlow(epoch) {
            when (val result = remote.signUp(email, password)) {
                AuthRegistration.EmailConfirmationRequired -> {
                    checkActive(epoch)
                    storage { vault.clear() }; credential = null; pendingPersistence = null; publish(AuthSnapshot.Phase.SignedOut); confirm = true
                }
                is AuthRegistration.Authenticated -> {
                    checkActive(epoch)
                    val c = result.credential
                    val user = remote.user(c.accessToken); checkActive(epoch); validate(c, user); persistVerified(c, epoch)
                }
            }
        }
        return confirm
    }

    suspend fun signInWithOAuth(code: String, verifier: String) {
        val epoch = beginSignIn { credential == null }
        signInFlow(epoch) {
            val candidate = remote.exchangeOAuthCode(code, verifier); checkActive(epoch)
            if (candidate.user.isAnonymous) throw err(AuthException.Kind.InvalidResponse)
            validate(candidate, candidate.user)
            recoveryCandidate = candidate
            val user = remote.user(candidate.accessToken); checkActive(epoch)
            validate(candidate, user); persistVerified(candidate, epoch)
        }
    }

    private fun beginSignIn(precondition: () -> Boolean): Int = synchronized(lock) {
        val p = _state.value.phase
        if (logoutInProgress || p == AuthSnapshot.Phase.SigningIn || p == AuthSnapshot.Phase.Restoring || !precondition()) throw err(AuthException.Kind.Busy)
        try { barrier.setSignedOut(true) } catch (e: Exception) {
            publish(AuthSnapshot.Phase.StorageBlocked, AuthException.Kind.Storage.message); throw err(AuthException.Kind.Storage)
        }
        generation += 1
        publish(AuthSnapshot.Phase.SigningIn)
        generation
    }

    private suspend fun signInFlow(epoch: Int, body: suspend () -> Unit) {
        try {
            body()
        } catch (e: Throwable) {
            if (epoch != generation) throw err(AuthException.Kind.Cancelled)
            credential = null
            if (e is CancellationException) { closeLocally(); throw e }
            val safe = (e as? AuthException)?.kind ?: AuthException.Kind.Unavailable
            publish(if (safe == AuthException.Kind.Storage) AuthSnapshot.Phase.StorageBlocked else AuthSnapshot.Phase.SignedOut, safe.message)
            throw err(safe)
        }
    }

    suspend fun signOut() {
        val token: String?
        val epoch: Int
        var storageFailure = false
        var remoteFailure = false
        synchronized(lock) {
            if (logoutInProgress) throw err(AuthException.Kind.Busy)
            logoutInProgress = true
            token = credential?.accessToken ?: pendingPersistence?.accessToken
            generation += 1; epoch = generation
            refreshFlight?.task?.cancel(); refreshFlight = null; credential = null; pendingPersistence = null; recoveryCandidate = null
            try { barrier.setSignedOut(true) } catch (e: Exception) { storageFailure = true }
            try { vault.clearPending() } catch (e: Exception) { storageFailure = true } // 任何挂起之前：轮换日志不能比退出活得久
            publish(if (storageFailure) AuthSnapshot.Phase.StorageBlocked else AuthSnapshot.Phase.SignedOut)
        }
        try {
            if (token != null) {
                try { remote.revokeApplicationSession(token) } catch (e: CancellationException) { remoteFailure = true } catch (e: Exception) { remoteFailure = true }
                try { remote.signOut(token) } catch (e: CancellationException) { remoteFailure = true } catch (e: Exception) { remoteFailure = true }
            }
            // 网络失败/取消都不能跳过最后的本机凭证删除。
            try { vault.clear() } catch (e: Exception) { storageFailure = true }
            if (epoch != generation) throw err(AuthException.Kind.Cancelled)
            if (storageFailure) { publish(AuthSnapshot.Phase.StorageBlocked, AuthException.Kind.Storage.message); throw err(AuthException.Kind.Storage) }
            publish(AuthSnapshot.Phase.SignedOut, if (remoteFailure) REMOTE_SIGN_OUT_UNCONFIRMED else null)
        } finally {
            synchronized(lock) { logoutInProgress = false }
        }
    }

    fun cancelLogin() = closeLocally()

    /** 服务端 401：只让同一主体失效。 */
    fun rejectAuthorization(subject: UUID) {
        if (credential?.user?.id == subject) expireLocally()
    }

    /** 已登录主体的 `Bearer …`；快过期时共享一次刷新。 */
    suspend fun authorization(subject: UUID): String {
        currentCoroutineContext().ensureActive()
        val epoch: Int
        val current: AuthCredential
        synchronized(lock) {
            val c = credential
            if (_state.value.phase !in AUTHORIZED || c == null || c.user.id != subject) throw err(AuthException.Kind.LoginRequired)
            epoch = generation; current = c
        }
        val usable = if (current.expiresAt > now().plusSeconds(30)) current else refreshCurrent()
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (generation != epoch || _state.value.phase !in AUTHORIZED || credential?.user?.id != subject) throw err(AuthException.Kind.LoginRequired)
        }
        return "Bearer " + usable.accessToken
    }

    private suspend fun refreshCurrent(): AuthCredential {
        val original = credential ?: throw err(AuthException.Kind.LoginRequired)
        val flight: Flight = synchronized(lock) {
            refreshFlight ?: run {
                // 刷新途中崩溃不能在恢复时重交一个结果未知的旧刷新令牌。
                try { barrier.setSignedOut(true) } catch (e: Exception) {
                    credential = null; publish(AuthSnapshot.Phase.StorageBlocked, AuthException.Kind.Storage.message); throw err(AuthException.Kind.Storage)
                }
                val epoch = generation
                val task = flightScope.async(start = CoroutineStart.LAZY) {
                    val next = remote.refresh(original.refreshToken)
                    checkActive(epoch)
                    if (next.user.id != original.user.id) throw err(AuthException.Kind.InvalidResponse)
                    validate(next, next.user)
                    runCatching { vault.savePending(next) } // /user 之前写日志（见 restore）
                    synchronized(lock) { recoveryCandidate = next }
                    val user = remote.user(next.accessToken)
                    checkActive(epoch)
                    if (user.id != original.user.id || next.user.isAnonymous != user.isAnonymous) throw err(AuthException.Kind.InvalidResponse)
                    next
                }
                Flight(UUID.randomUUID(), epoch, task).also { refreshFlight = it; publish(AuthSnapshot.Phase.Refreshing); task.start() }
            }
        }
        try {
            val next = flight.task.await()
            checkActive(flight.epoch)
            if (refreshFlight?.id == flight.id) {
                validate(next, next.user)
                persistVerified(next, flight.epoch)
                synchronized(lock) { if (refreshFlight?.id == flight.id) refreshFlight = null }
            }
            if (pendingPersistence != null && _state.value.phase == AuthSnapshot.Phase.StorageBlocked) throw err(AuthException.Kind.Storage)
            val current = credential
            if (current == null || current.user.id != original.user.id) throw err(AuthException.Kind.LoginRequired)
            return current
        } catch (e: Throwable) {
            if (generation != flight.epoch) throw err(AuthException.Kind.Cancelled)
            // 取消的等待者只是离开；共享的刷新仍属于其他等待者。
            if (e is CancellationException && !flight.task.isCancelled) throw e
            synchronized(lock) { if (refreshFlight?.id == flight.id) refreshFlight = null }
            val kind = (e as? AuthException)?.kind
            if (kind == AuthException.Kind.Storage) { credential = null; publish(AuthSnapshot.Phase.StorageBlocked, kind.message); throw err(kind) }
            if ((kind == AuthException.Kind.Unavailable || kind == AuthException.Kind.RateLimited) && recoveryCandidate != null) {
                credential = null; publish(AuthSnapshot.Phase.SignedOut, kind.message); throw err(kind)
            }
            expireLocally()
            throw err(AuthException.Kind.LoginRequired)
        }
    }

    fun retryPersistence() {
        synchronized(lock) {
            val pending = pendingPersistence
            if (pending == null || _state.value.phase != AuthSnapshot.Phase.StorageBlocked) throw err(AuthException.Kind.LoginRequired)
            validate(pending, pending.user)
            try { vault.save(pending); barrier.setSignedOut(false) } catch (e: Exception) {
                publish(AuthSnapshot.Phase.StorageBlocked, AuthException.Kind.Storage.message); throw err(AuthException.Kind.Storage)
            }
            runCatching { vault.clearPending() }
            pendingPersistence = null; recoveryCandidate = null; refreshFlight = null; credential = pending; publish(AuthSnapshot.Phase.Authenticated)
        }
    }

    private fun checkActive(epoch: Int) {
        if (epoch != generation) throw err(AuthException.Kind.Cancelled)
    }

    private fun persistVerified(candidate: AuthCredential, epoch: Int) = synchronized(lock) {
        checkActive(epoch)
        pendingPersistence = candidate
        try {
            vault.save(candidate)
            barrier.setSignedOut(false)
        } catch (e: Exception) {
            throw err(AuthException.Kind.Storage)
        }
        runCatching { vault.clearPending() }
        pendingPersistence = null; recoveryCandidate = null; credential = candidate
        publish(AuthSnapshot.Phase.Authenticated)
    }

    private fun validate(c: AuthCredential, user: AuthUser) {
        if (c.issuer != issuer || c.user.id != user.id || c.user.isAnonymous != user.isAnonymous ||
            c.accessToken.length >= 8192 || !JWT.matches(c.accessToken) ||
            c.refreshToken.isEmpty() || c.refreshToken.length >= 8192 || c.expiresAt <= now()
        ) throw err(AuthException.Kind.InvalidResponse)
    }

    private fun closeLocally() = synchronized(lock) {
        generation += 1; refreshFlight?.task?.cancel(); refreshFlight = null; credential = null; pendingPersistence = null; recoveryCandidate = null
        try { barrier.setSignedOut(true); vault.clearPending(); vault.clear() } catch (e: Exception) {
            publish(AuthSnapshot.Phase.StorageBlocked, AuthException.Kind.Storage.message); throw err(AuthException.Kind.Storage)
        }
        publish(AuthSnapshot.Phase.SignedOut)
    }

    private fun expireLocally() = synchronized(lock) {
        generation += 1; refreshFlight?.task?.cancel(); refreshFlight = null; credential = null; pendingPersistence = null; recoveryCandidate = null
        try { barrier.setSignedOut(true); vault.clearPending(); vault.clear() } catch (e: Exception) {
            publish(AuthSnapshot.Phase.StorageBlocked, AuthException.Kind.Storage.message); throw err(AuthException.Kind.Storage)
        }
        publish(AuthSnapshot.Phase.LoginRequired, AuthException.Kind.LoginRequired.message)
    }

    private fun publish(phase: AuthSnapshot.Phase, message: String? = null) = synchronized(lock) {
        val value = AuthSnapshot(phase, credential?.user, generation, message)
        val account = accountBarrier
        if (account != null && account.currentAccount != null &&
            (phase !in AUTHORIZED_OR_RESTORING || value.user == null || !value.user.id.toString().equals(account.currentAccount, ignoreCase = true))
        ) account.signOut()
        _state.value = value
    }

    private inline fun <T> storage(block: () -> T): T = try { block() } catch (e: AuthException) { throw e } catch (e: Exception) { throw err(AuthException.Kind.Storage) }

    private fun err(kind: AuthException.Kind) = AuthException(kind)

    companion object {
        const val REMOTE_SIGN_OUT_UNCONFIRMED = "已在此设备退出；服务端会话撤销未确认。"
        private val JWT = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
        private val AUTHORIZED = setOf(AuthSnapshot.Phase.Authenticated, AuthSnapshot.Phase.Refreshing)
        private val AUTHORIZED_OR_RESTORING = setOf(AuthSnapshot.Phase.Authenticated, AuthSnapshot.Phase.Refreshing, AuthSnapshot.Phase.Restoring)
    }
}

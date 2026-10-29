package com.heydusk.mimi.feature.parenting.cloud

import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.feature.parenting.intake.CloudIntakeService
import com.heydusk.mimi.feature.parenting.intake.InlineIntakeModel
import com.heydusk.mimi.feature.parenting.model.ParentingSession
import com.heydusk.mimi.network.CloudConfig
import com.heydusk.mimi.network.auth.AuthException
import com.heydusk.mimi.network.auth.AuthSessionManager
import com.heydusk.mimi.network.auth.AuthSnapshot
import com.heydusk.mimi.network.auth.OAuthCallback
import com.heydusk.mimi.network.auth.OAuthPkce
import com.heydusk.mimi.network.auth.OAuthProvider
import com.heydusk.mimi.network.auth.OAuthRequest
import com.heydusk.mimi.network.auth.SupabaseAuthRemote
import com.heydusk.mimi.network.enrollment.FeatureEnrollmentClient
import com.heydusk.mimi.network.intake.IntakeJournal
import com.heydusk.mimi.network.intake.ParentingIntakeClient
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 设置页与表单读的云端账号状态。 */
data class CloudUi(
    /** false = 没有配置云端（local.properties 里没有地址/密钥）：只走本机路径，不显示登录与「让小咪整理」。 */
    val configured: Boolean = false,
    val phase: AuthSnapshot.Phase = AuthSnapshot.Phase.SignedOut,
    val email: String? = null,
    val anonymous: Boolean = false,
    val busy: Boolean = false,
    /** 文案目录的键。 */
    val message: String? = null,
    val messageIsError: Boolean = false,
    val enrolled: Boolean? = null,
    val enrollment: EnrollmentPhase = EnrollmentPhase.Idle,
    val serverScopeID: UUID? = null,
    /** 这次登录的账号（换账号时界面据此重建整理模型）。 */
    val subjectID: UUID? = null,
) {
    val signedIn: Boolean get() = phase == AuthSnapshot.Phase.Authenticated || phase == AuthSnapshot.Phase.Refreshing
    val intakeReady: Boolean get() = configured && signedIn && serverScopeID != null
}

sealed interface EnrollmentPhase {
    data object Idle : EnrollmentPhase
    data object Submitting : EnrollmentPhase
    data object Done : EnrollmentPhase
    data object Invalid : EnrollmentPhase
    data class RateLimited(val until: Instant) : EnrollmentPhase
    data object SignedOut : EnrollmentPhase
    data object Failed : EnrollmentPhase
}

/**
 * 云端账号的编排：Supabase 会话（[AuthSessionManager]）↔ 本机账本主体（[ParentingSession]）↔ 服务端作用域 / 功能准入。
 * - 登录成功 → 账本切到这个账号的 id（仅本机体验数据会带进第一次登录的账号；换到另一个云端账号与退出一样清除）；
 * - 退出 → 先撤销服务端会话并清本机令牌，再按 M2 的方式清账本并发一个新的本机体验主体；
 * - OAuth：PKCE verifier 只在内存，回调只接受 `catpawm://auth-callback?code=…`。
 * 未配置时所有方法都是空操作。
 */
class CloudAccount(
    private val config: CloudConfig?,
    private val auth: AuthSessionManager?,
    private val remote: SupabaseAuthRemote?,
    private val session: ParentingSession,
    private val enrollmentClient: FeatureEnrollmentClient?,
    private val intakeClient: ParentingIntakeClient?,
    private val epoch: AccountEpoch,
    private val journalFor: (UUID) -> IntakeJournal,
    private val scope: CoroutineScope,
    private val now: () -> Instant = { Instant.now() },
) {
    private val _state = MutableStateFlow(CloudUi(configured = config != null && auth != null))
    val state: StateFlow<CloudUi> = _state.asStateFlow()
    private val mutex = Mutex()
    @Volatile private var pkce: Pair<OAuthProvider, OAuthPkce>? = null

    fun start() {
        val a = auth ?: return
        scope.launch {
            a.state.collect { snap ->
                _state.update { it.copy(phase = snap.phase, email = snap.user?.email, anonymous = snap.user?.isAnonymous == true, subjectID = snap.user?.id) }
            }
        }
        scope.launch {
            run(silent = true) { a.restore() }
        }
    }

    fun startExperience() = launchAuth { auth!!.startExperience() }
    fun signIn(email: String, password: String) = launchAuth { auth!!.signIn(email.trim(), password) }
    fun signUp(email: String, password: String) = launchAuth {
        val confirm = auth!!.signUp(email.trim(), password)
        if (confirm) _state.update { it.copy(message = CONFIRM_EMAIL, messageIsError = false) }
    }

    /** 返回要用 Custom Tabs 打开的授权页；未配置返回 null。 */
    fun beginOAuth(provider: OAuthProvider): String? {
        val c = config ?: return null
        val p = OAuthPkce.generate()
        pkce = provider to p
        return OAuthRequest.authorizeUrl(c.projectUrl, provider, p)
    }

    /** 处理 `catpawm://auth-callback`。没有待完成的 OAuth 时忽略。 */
    fun handleCallback(url: String) {
        val pending = pkce ?: return
        pkce = null
        val callback = try { OAuthCallback.parse(url) } catch (e: AuthException) {
            _state.update { it.copy(message = e.kind.message, messageIsError = true) }; return
        }
        when (callback) {
            is OAuthCallback.Code -> launchAuth {
                // 已登录（例如体验账号）时，先退出再用新账号登录：与 iOS「切换账号」一致，不做身份绑定。
                if (auth!!.currentUser != null) { auth.signOut(); session.signOutAndStartFresh() }
                auth.signInWithOAuth(callback.code, pending.second.verifier)
            }
            OAuthCallback.Cancelled -> _state.update { it.copy(message = null) }
            is OAuthCallback.Failure -> _state.update { it.copy(message = LOGIN_INCOMPLETE, messageIsError = true) }
        }
    }

    fun signOut() {
        val a = auth ?: return
        scope.launch {
            mutex.withLock {
                _state.update { it.copy(busy = true) }
                val remoteNote = try { a.signOut(); null } catch (e: CancellationException) { throw e } catch (e: AuthException) { e.kind.message }
                session.signOutAndStartFresh()
                _state.update { it.copy(busy = false, serverScopeID = null, enrolled = null, enrollment = EnrollmentPhase.Idle, message = remoteNote ?: a.snapshot().message, messageIsError = remoteNote != null) }
            }
        }
    }

    fun reportBrowserMissing() { pkce = null; _state.update { it.copy(message = NO_BROWSER, messageIsError = true) } }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun submitInvite(code: String) {
        val client = enrollmentClient ?: return
        val current = _state.value.enrollment
        if (current is EnrollmentPhase.RateLimited && current.until > now()) return
        _state.update { it.copy(enrollment = EnrollmentPhase.Submitting) }
        scope.launch {
            val bearer = bearer() ?: run { _state.update { it.copy(enrollment = EnrollmentPhase.SignedOut) }; return@launch }
            val phase = when (val r = client.submit(bearer, code)) {
                is FeatureEnrollmentClient.SubmitResult.Done -> { _state.update { it.copy(enrolled = r.status.enrolled) }; EnrollmentPhase.Done }
                FeatureEnrollmentClient.SubmitResult.Invalid -> EnrollmentPhase.Invalid
                is FeatureEnrollmentClient.SubmitResult.RateLimited -> EnrollmentPhase.RateLimited(r.until)
                FeatureEnrollmentClient.SubmitResult.SignedOut -> EnrollmentPhase.SignedOut
                FeatureEnrollmentClient.SubmitResult.Failed -> EnrollmentPhase.Failed
            }
            _state.update { it.copy(enrollment = phase) }
        }
    }

    fun resetEnrollmentPhase() = _state.update { if (it.enrollment is EnrollmentPhase.RateLimited) it else it.copy(enrollment = EnrollmentPhase.Idle) }

    /** 当前账号的整理模型；没登录或未配置时返回一个不可用的模型（按钮不显示）。 */
    fun intakeModel(modelScope: CoroutineScope = scope): InlineIntakeModel {
        val s = _state.value
        val subject = s.subjectID
        val serverScope = s.serverScopeID
        val client = intakeClient
        if (!s.intakeReady || subject == null || serverScope == null || client == null) {
            return InlineIntakeModel(null, null, null, epoch, modelScope)
        }
        val service = CloudIntakeService(client, serverScope) {
            auth!!.authorization(subject)
        }
        return InlineIntakeModel(subject, service, journalFor(subject), epoch, modelScope, admission = { _state.value.intakeReady && _state.value.subjectID == subject })
    }

    private suspend fun bearer(): String? {
        val a = auth ?: return null
        val user = a.currentUser ?: return null
        return try { a.authorization(user.id) } catch (e: AuthException) { null }
    }

    private fun launchAuth(block: suspend () -> Unit) {
        if (auth == null) return
        scope.launch { run(silent = false, block) }
    }

    private suspend fun run(silent: Boolean, block: suspend () -> Unit) = mutex.withLock {
        _state.update { it.copy(busy = true, message = if (silent) it.message else null) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            if (!silent || e.kind != AuthException.Kind.Busy) _state.update { it.copy(message = e.kind.message, messageIsError = true) }
        }
        afterAuth()
        _state.update { it.copy(busy = false) }
    }

    /** 登录后：账本切到账号主体，读服务端作用域与功能准入。失败不影响本机使用。 */
    private suspend fun afterAuth() {
        val a = auth ?: return
        val user = a.currentUser ?: return
        if (a.snapshot().phase != AuthSnapshot.Phase.Authenticated) return
        session.bindCloudSubject(user.id)
        val bearer = bearer() ?: return
        val scopeID = try {
            remote?.applicationScope(bearer.removePrefix("Bearer "), user.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val status = enrollmentClient?.status(bearer)
        _state.update { it.copy(serverScopeID = scopeID, enrolled = status?.enrolled) }
    }

    companion object {
        const val CONFIRM_EMAIL = "请先在邮箱里确认注册，再登录。"
        const val NO_BROWSER = "没有可用的浏览器，无法完成登录。"
        const val LOGIN_INCOMPLETE = "登录未完成，请稍后重试。"
    }
}

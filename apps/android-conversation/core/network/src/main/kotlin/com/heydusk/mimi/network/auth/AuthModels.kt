package com.heydusk.mimi.network.auth

import java.time.Instant
import java.util.UUID

data class AuthUser(val id: UUID, val email: String?, val isAnonymous: Boolean)

/** 令牌只活在内存与 Keystore 包裹的保管箱里；toString 不打印令牌。 */
data class AuthCredential(
    val issuer: String,
    val user: AuthUser,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "AuthCredential(issuer=$issuer, user=${user.id}, anonymous=${user.isAnonymous}, expiresAt=$expiresAt)"
}

sealed interface AuthRegistration {
    data class Authenticated(val credential: AuthCredential) : AuthRegistration
    data object EmailConfirmationRequired : AuthRegistration
}

/**
 * 与 iOS `NativeAuthError` 同一组；[message] 是界面文案目录的键（中文原句，界面层通过 `ui()` 显示）。
 * 状态码与服务端原始错误不会变成用户文案。
 */
class AuthException(val kind: Kind) : Exception(kind.name) {
    enum class Kind(val message: String) {
        NotConfigured("还没有配置云端服务，当前只在本机使用。"),
        InvalidConfiguration("登录配置无效。"),
        InvalidCredentials("邮箱或密码不正确。"),
        EmailNotConfirmed("请先在邮箱里确认注册，再登录。"),
        SignupUnavailable("暂时无法注册新账号。"),
        EmailNotAuthorized("这个邮箱暂时不能注册。"),
        WeakPassword("密码太简单，请换一个更长的密码。"),
        AnonymousUnavailable("体验账号暂未开放，可以用邮箱登录。"),
        LoginRequired("登录已过期，请重新登录。"),
        RateLimited("尝试太频繁，请稍后再试。"),
        Unavailable("暂时连不上服务，请稍后重试。"),
        InvalidResponse("服务返回的内容未通过校验，已停止登录。"),
        Storage("登录信息无法安全保存在这台手机上。"),
        Busy("正在处理上一次登录，请稍候。"),
        Cancelled("已取消。"),
        CallbackUnsupported("这个登录回调无法识别。"),
        SessionRecoveryRequired("上一次登录续期结果未确认，请重新登录，不会自动换成新的体验账号。"),
    }
}

data class AuthSnapshot(val phase: Phase, val user: AuthUser?, val generation: Int, val message: String?) {
    enum class Phase { SignedOut, Restoring, SigningIn, Authenticated, Refreshing, LoginRequired, StorageBlocked }
}

/** 保管箱：已验证的凭证 + 「轮换日志」（刷新已拿到新令牌、但还没完成 /user 验证时的那一份）。 */
interface AuthVault {
    fun load(): AuthCredential?
    fun save(credential: AuthCredential)
    fun clear()
    fun loadPending(): AuthCredential?
    fun savePending(credential: AuthCredential)
    fun clearPending()
}

/** 「已退出」屏障（iOS `NativeAuthLogoutBarrier`）：刷新进行中或崩溃后为 true，冷启动不得把不确定的旧令牌当有效。 */
interface AuthLogoutBarrier {
    fun isSignedOut(): Boolean
    fun setSignedOut(value: Boolean)
}

interface AuthRemote {
    suspend fun signInAnonymously(): AuthCredential
    suspend fun signUp(email: String, password: String): AuthRegistration
    suspend fun signIn(email: String, password: String): AuthCredential
    suspend fun refresh(refreshToken: String): AuthCredential
    suspend fun user(accessToken: String): AuthUser
    suspend fun signOut(accessToken: String)
    suspend fun revokeApplicationSession(accessToken: String)
    suspend fun exchangeOAuthCode(code: String, verifier: String): AuthCredential
}

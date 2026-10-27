package com.heydusk.mimi.domain.account

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** "哪个账号、第几次登录"的凭证，必须在任何异步操作之前捕获。 */
data class AccountToken(val account: String, val epoch: Long)

/**
 * 账号代际屏障（iOS `AccountEpoch`）。
 *
 * 规则：在任何 await（网络、权限弹窗、查询）之前捕获 token；在任何持久化或系统写入（账本/偏好/文件/通知/上传）之前，
 * 立刻调用 `commit(token) { write }`。只有同一账号仍在同一代际登录时 write 才会执行。
 * - `signOut()` / 换账号的 `activate` 同步地让代际 +1，所以更早捕获的 token 在退出开始的瞬间就失效，早于任何异步清理；
 * - `commit` 在写入期间持有锁，并发的 bump 要等它结束：写入要么完全在屏障之前，要么永远不发生。
 *   写入要短而同步，里面不要挂起；目标位置（账本、密钥）应由 token 推导，而不是"当前"；
 * - 线程安全（可重入锁，所以 `capture`/`isValid` 可以在 `commit` 里调用）。
 */
class AccountEpoch(account: String? = null) {
    private val lock = ReentrantLock()
    private var accountId: String? = account
    private var counter: Long = 0

    /** 当前代际号（单调）。 */
    val epoch: Long get() = lock.withLock { counter }

    /** 已登录的账号 id；未登录为 null。 */
    val currentAccount: String? get() = lock.withLock { accountId }

    /** 已登录账号的 token；未登录为 null（调用方不得开始与账号绑定的工作）。 */
    fun capture(): AccountToken? = lock.withLock { accountId?.let { AccountToken(it, counter) } }

    /** 登录/恢复/切换账号。同一账号 → 什么都不做（token 保持有效）；其他情况代际 +1。 */
    fun activate(account: String?) = lock.withLock {
        if (account == accountId) return@withLock
        accountId = account
        counter += 1
    }

    /** 退出登录时第一个同步调用，早于任何挂起点。 */
    fun signOut() = lock.withLock { accountId = null; counter += 1 }

    /** 不改账号，让所有未完成的 token 失效（例如会话重启、清除数据）。 */
    fun bump() = lock.withLock { counter += 1 }

    fun isValid(token: AccountToken?): Boolean {
        if (token == null) return false
        return lock.withLock { accountId == token.account && counter == token.epoch }
    }

    /** 只有 `token` 仍有效时才执行 `write`，并与退出/切换互斥。被拒绝时返回 null。 */
    fun <T> commit(token: AccountToken?, write: () -> T): T? = lock.withLock {
        if (!isValid(token)) null else write()
    }
}

/**
 * 与账号绑定的子系统的清理挂点。顺序契约：调用方先同步地让 `AccountEpoch` +1（退出/被动失效/切换），
 * 再 await `notify`，它按键的顺序执行所有已注册的异步清理。清理只能拆除 `previous` 的状态，不得重新授予任何东西。
 * （iOS 的 `AccountParkedItems` 登记表带界面决策，留到 M5 与设置/退出登录一起做。）
 */
class AccountChangeObservers {
    fun interface Cleanup {
        suspend fun run(previous: String?, current: String?)
    }

    private val lock = Any()
    private val observers = sortedMapOf<String, Cleanup>()

    fun register(key: String, cleanup: Cleanup) = synchronized(lock) { observers[key] = cleanup; Unit }
    fun unregister(key: String) = synchronized(lock) { observers.remove(key); Unit }
    val registeredKeys: List<String> get() = synchronized(lock) { observers.keys.toList() }

    /** 只能在同步的代际 +1 之后调用。 */
    suspend fun notify(previous: String?, current: String?) {
        val snapshot = synchronized(lock) { observers.entries.map { it.key to it.value } }
        for ((_, cleanup) in snapshot) cleanup.run(previous, current)
    }
}

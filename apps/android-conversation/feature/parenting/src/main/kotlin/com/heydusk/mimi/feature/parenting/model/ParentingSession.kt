package com.heydusk.mimi.feature.parenting.model

import com.heydusk.mimi.data.account.LocalIdentityStore
import com.heydusk.mimi.data.account.ParentingScopes
import com.heydusk.mimi.data.ledger.LedgerBlobStore
import com.heydusk.mimi.data.ledger.LedgerCipher
import com.heydusk.mimi.data.ledger.LedgerKeyProvider
import com.heydusk.mimi.data.ledger.LedgerScope
import com.heydusk.mimi.data.ledger.ParentingWorkspaceStore
import com.heydusk.mimi.domain.account.AccountChangeObservers
import com.heydusk.mimi.domain.account.AccountEpoch
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 本机账号会话：把 [LocalIdentityStore] / [AccountEpoch] / 加密账本串起来。
 * - 启动：ensureSubject → epoch.activate → 打开该主体的账本并 load
 * - 退出：epoch.signOut（同步屏障）→ 删密文与密钥 → 清 subject → 通知观察者 → 再发一个新主体（仍是体验账号）
 * - 切换：与退出相同，只是新主体立刻激活（M5 才会有真正的多账号选择）
 *
 * 没有 Supabase；这是 iOS「体验账号」路径的 Android 对等物。
 */
class ParentingSession(
    private val identity: LocalIdentityStore,
    private val epoch: AccountEpoch,
    private val blobs: LedgerBlobStore,
    private val cipher: LedgerCipher,
    private val keys: LedgerKeyProvider,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = { Instant.now() },
    private val observers: AccountChangeObservers = AccountChangeObservers(),
    private val ledgerScope: LedgerScope = ParentingScopes.USER,
    /** 为某个账号装配本机提醒；null = 这个壳不发提醒（测试、预览）。 */
    private val reminders: ReminderFactory? = null,
) {
    /** 给一个账号造提醒装配。`isCurrent` 是该账号的代际屏障：账号一换，调度器就不再写。 */
    fun interface ReminderFactory {
        fun create(subjectID: UUID, isCurrent: () -> Boolean): ReminderWiring
    }

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    sealed interface Phase {
        data object Loading : Phase
        data class Ready(
            val subjectID: UUID,
            val model: ParentingWorkspaceModel,
            val onboardingDone: Boolean,
            val isAnonymous: Boolean = true,
        ) : Phase
        data class Failed(val message: String) : Phase
    }

    private val mutex = Mutex()
    private val _phase = MutableStateFlow<Phase>(Phase.Loading)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    fun start() {
        started.set(true)
        scope.launch { bootstrap() }
    }

    /** 幂等：界面和「开机后重排提醒」的接收器谁先到谁启动，只会启动一次。 */
    fun startIfNeeded() {
        if (started.compareAndSet(false, true)) scope.launch { bootstrap() }
    }

    /** 等到账本已就绪（或失败/超时返回 null）。接收器在没有界面的进程里用它。 */
    suspend fun awaitReady(timeoutMillis: Long): Phase.Ready? {
        startIfNeeded()
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
            phase.first { it is Phase.Ready || it is Phase.Failed } as? Phase.Ready
        }
    }

    private fun newModel(store: ParentingWorkspaceStore, subject: UUID): ParentingWorkspaceModel {
        val wiring = reminders?.create(subject) { epoch.currentAccount == subject.toString() && store.isCurrentAccount() }
        wiring?.onActivate?.invoke()
        return ParentingWorkspaceModel(store, clock, wiring)
    }

    suspend fun bootstrap(): Unit = mutex.withLock {
        _phase.value = Phase.Loading
        try {
            val subject = identity.ensureSubject()
            val previous = epoch.currentAccount
            epoch.activate(subject.toString())
            if (previous != null && previous != subject.toString()) {
                observers.notify(previous, subject.toString())
            }
            val store = ParentingWorkspaceStore(blobs, ledgerScope, subject, epoch, cipher, clock)
            val model = newModel(store, subject)
            model.load()
            val onboarding = identity.snapshot.first().onboardingDone
            _phase.value = Phase.Ready(subject, model, onboarding)
        } catch (e: Exception) {
            _phase.value = Phase.Failed(e.message ?: e.toString())
        }
    }

    suspend fun completeOnboarding() {
        identity.setOnboardingDone(true)
        val current = _phase.value
        if (current is Phase.Ready) _phase.value = current.copy(onboardingDone = true)
    }

    /**
     * 退出当前体验账号：同步抬升代际 → 清掉该主体的密文与密钥 → 清 identity → 再开一个新主体。
     * 旧账本因密钥已删且 subject 已换，永远读不到。
     */
    suspend fun signOutAndStartFresh(): Unit = mutex.withLock {
        val leaving = when (val p = _phase.value) {
            is Phase.Ready -> p.subjectID
            else -> identity.snapshot.first().subjectID
        }
        val previous = epoch.currentAccount
        val wiring = (_phase.value as? Phase.Ready)?.model?.reminders
        epoch.signOut() // 1. 同步屏障：任何在途写入立刻被拒
        // 提醒属于这个账号：队列、已送达的通知、设置与「已响过」记录一并清掉，之后迟到的闹钟也会被丢弃。
        runCatching { wiring?.onSignOut?.invoke() }
        if (leaving != null) wipeSubject(leaving)
        identity.clearSubject()
        observers.notify(previous, null)
        // 立刻发新体验账号，保持「打开就能用」
        val subject = identity.ensureSubject()
        epoch.activate(subject.toString())
        val store = ParentingWorkspaceStore(blobs, ledgerScope, subject, epoch, cipher, clock)
        val model = newModel(store, subject)
        model.load()
        _phase.value = Phase.Ready(subject, model, onboardingDone = true)
    }

    /** 清除当前主体的账本数据但保持登录（设置 › 清除全部数据）。 */
    suspend fun clearCurrentLedger(): Unit = mutex.withLock {
        val ready = _phase.value as? Phase.Ready ?: return
        epoch.bump() // 让在途 token 失效
        runCatching { ready.model.reminders?.scheduler?.clear() } // 账本清空了：已排的提醒一并取消（设置保留）
        wipeSubject(ready.subjectID)
        epoch.activate(ready.subjectID.toString()) // 同一账号、新代际
        val store = ParentingWorkspaceStore(blobs, ledgerScope, ready.subjectID, epoch, cipher, clock)
        val model = newModel(store, ready.subjectID)
        model.load()
        _phase.value = ready.copy(model = model)
    }

    private fun wipeSubject(subject: UUID) {
        runCatching { blobs.delete(ParentingWorkspaceStore.preferenceKey(ledgerScope, subject)) }
        runCatching { keys.deleteKey(subject) }
    }
}

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
) {
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
        scope.launch { bootstrap() }
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
            val model = ParentingWorkspaceModel(store, clock)
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
        epoch.signOut() // 1. 同步屏障：任何在途写入立刻被拒
        if (leaving != null) wipeSubject(leaving)
        identity.clearSubject()
        observers.notify(previous, null)
        // 立刻发新体验账号，保持「打开就能用」
        val subject = identity.ensureSubject()
        epoch.activate(subject.toString())
        val store = ParentingWorkspaceStore(blobs, ledgerScope, subject, epoch, cipher, clock)
        val model = ParentingWorkspaceModel(store, clock)
        model.load()
        _phase.value = Phase.Ready(subject, model, onboardingDone = true)
    }

    /** 清除当前主体的账本数据但保持登录（设置 › 清除全部数据）。 */
    suspend fun clearCurrentLedger(): Unit = mutex.withLock {
        val ready = _phase.value as? Phase.Ready ?: return
        epoch.bump() // 让在途 token 失效
        wipeSubject(ready.subjectID)
        epoch.activate(ready.subjectID.toString()) // 同一账号、新代际
        val store = ParentingWorkspaceStore(blobs, ledgerScope, ready.subjectID, epoch, cipher, clock)
        val model = ParentingWorkspaceModel(store, clock)
        model.load()
        _phase.value = ready.copy(model = model)
    }

    private fun wipeSubject(subject: UUID) {
        runCatching { blobs.delete(ParentingWorkspaceStore.preferenceKey(ledgerScope, subject)) }
        runCatching { keys.deleteKey(subject) }
    }
}

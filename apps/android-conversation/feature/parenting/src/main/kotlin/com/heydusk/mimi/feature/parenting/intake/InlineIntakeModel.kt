package com.heydusk.mimi.feature.parenting.intake

import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.parenting.ParentingIntakeDraft
import com.heydusk.mimi.domain.parenting.ParentingIntakeException
import com.heydusk.mimi.domain.parenting.ParentingIntakePhase
import com.heydusk.mimi.domain.parenting.ParentingIntakeRequest
import com.heydusk.mimi.network.intake.IntakeCleanupReference
import com.heydusk.mimi.network.intake.IntakeConsentRequest
import com.heydusk.mimi.network.intake.IntakeConversation
import com.heydusk.mimi.network.intake.IntakeJournal
import com.heydusk.mimi.network.intake.IntakeOutcome
import com.heydusk.mimi.network.intake.IntakeRecoveryReference
import com.heydusk.mimi.network.intake.IntakeUnavailableException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 原位「让小咪整理」（iOS `ParentingInlineIntakeModel`）。一次显式请求、一个专用云端会话、一份本机加密恢复记录；
 * 从不改动育儿账本——结果只是候选，家长点「采用整理结果」后才会填进表单，仍要再点保存。
 *
 * 恢复规则（与 iOS 相同）：
 * - notSent（服务端明确没接收）→ 可以用同一身份重试；
 * - terminal（服务端已终止失败）→ 「重新发起整理」用新身份，旧会话进清理队列；
 * - readOnly（结果未确认）→ 只能「检查本次结果」（GET events），绝不再发一次模型请求；
 * - 账号代际一变，在途结果全部作废（不会填回别的账号）。
 */
class InlineIntakeModel(
    private val subjectID: UUID?,
    private val service: IntakeService?,
    private val journal: IntakeJournal?,
    private val epoch: AccountEpoch,
    private val scope: CoroutineScope,
    private val admission: () -> Boolean = { true },
    private val clock: () -> Instant = { Instant.now() },
    private val makeID: () -> UUID = { UUID.randomUUID() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    data class State(
        val phase: ParentingIntakePhase = ParentingIntakePhase.Idle,
        val busy: Boolean = false,
        val candidate: ParentingIntakeDraft? = null,
        val error: String? = null,
        val consent: IntakeConsentRequest? = null,
        val recoveryAvailable: Boolean = false,
        val recoveredSource: IntakeRecoveryReference? = null,
        val deletionPending: Boolean = false,
        val deletionError: String? = null,
        val canRetry: Boolean = false,
        val canReconcile: Boolean = false,
    )

    private enum class Recovery { NotSent, ReadOnly, Terminal }

    private class Work(
        val generation: Int,
        val request: ParentingIntakeRequest,
        val reference: IntakeRecoveryReference,
        var conversation: IntakeConversation? = null,
        var recovery: Recovery = Recovery.NotSent,
        var sent: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val available: Boolean get() = service != null && subjectID != null && journal != null

    private var generation = 0
    private var work: Work? = null
    private var job: Job? = null
    private var lastInput: Triple<String, UUID, UUID?>? = null

    init {
        refreshStored()
    }

    private fun refreshStored() {
        val j = journal ?: return
        val recovery = runCatching { j.readRecovery() }.getOrNull()?.let { ref ->
            if (ref.subjectID != subjectID || ref.startedAt.plusSeconds(IntakeRecoveryReference.MAX_AGE_SECONDS) < clock()) {
                // 过期：只清理，不恢复。
                enqueueCleanup(IntakeCleanupReference(ref.serverScopeID, ref.conversationID))
                runCatching { j.deleteRecovery() }
                null
            } else ref
        }
        val pending = runCatching { j.readCleanup() }.getOrDefault(emptyList()).isNotEmpty()
        _state.update { it.copy(recoveryAvailable = recovery != null, deletionPending = pending) }
        recompute()
    }

    private fun recompute() {
        val w = work
        _state.update { s ->
            s.copy(
                canRetry = !s.busy && !s.deletionPending && s.phase == ParentingIntakePhase.Failed && admission() && service != null &&
                    tokenMatches() && (if (w != null) w.generation == generation && w.recovery != Recovery.ReadOnly else lastInput != null),
                canReconcile = !s.busy && s.phase == ParentingIntakePhase.Unknown && w?.generation == generation && w.recovery == Recovery.ReadOnly,
            )
        }
    }

    private fun tokenMatches(): Boolean {
        val token = epoch.capture() ?: return false
        return subjectID != null && token.account.equals(subjectID.toString(), ignoreCase = true)
    }

    private fun set(transform: (State) -> State) { _state.update(transform); recompute() }

    /** 家长点「让小咪整理」。 */
    fun request(sourceText: String, childID: UUID, goalID: UUID?) {
        val s = _state.value
        if (s.busy || s.phase == ParentingIntakePhase.AwaitingConsent || s.phase == ParentingIntakePhase.Unknown) return
        if (s.deletionPending) { set { it.copy(deletionError = CLEANUP_MESSAGE) }; return }
        stop()
        lastInput = Triple(sourceText, childID, goalID)
        // 新的显式请求会替换尚未取回的冷恢复记录。
        journal?.let { j -> runCatching { j.readRecovery() }.getOrNull()?.let { prev ->
            enqueueCleanup(IntakeCleanupReference(prev.serverScopeID, prev.conversationID)); runCatching { j.deleteRecovery() }
        } }
        val svc = service
        val token = epoch.capture()
        if (subjectID == null || svc == null || journal == null || token == null || !token.account.equals(subjectID.toString(), ignoreCase = true) || !admission()) {
            set { it.copy(phase = ParentingIntakePhase.Failed, error = UNAVAILABLE, recoveryAvailable = false) }
            return
        }
        val requestID = makeID()
        val conversationID = makeID()
        val reference = IntakeRecoveryReference(requestID, subjectID, svc.serverScopeID, conversationID, sourceText, childID, goalID, clock(), zone().id)
        val request = try {
            ParentingIntakeRequest(requestID, subjectID, token, conversationID, sourceText, childID, goalID, reference.startedAt, ZoneId.of(reference.timeZoneID))
        } catch (e: ParentingIntakeException) {
            set { it.copy(phase = ParentingIntakePhase.Failed, error = e.kind.message) }
            return
        }
        val w = Work(generation, request, reference)
        work = w
        set { it.copy(phase = ParentingIntakePhase.Preparing, busy = true, error = null, candidate = null, consent = null, recoveryAvailable = false) }
        launch(w) { prepare(w) }
    }

    private suspend fun prepare(w: Work) {
        drainCleanup()
        check(w)
        if (_state.value.deletionPending) { fail(w, CLEANUP_MESSAGE, Recovery.NotSent); return }
        val svc = service ?: return
        try {
            val conversation = svc.open(w.request.conversationID)
            check(w)
            w.conversation = conversation
            val consent = svc.consent(w.request.conversationID)
            check(w)
            if (consent != null) {
                set { it.copy(phase = ParentingIntakePhase.AwaitingConsent, busy = false, consent = consent) }
                return
            }
            send(w)
        } catch (e: IntakeUnavailableException) {
            if (isCurrent(w)) fail(w, if (e.status == 404 || e.status == 405) UNAVAILABLE else NOT_SENT, Recovery.NotSent)
        }
    }

    /** 家长在发送许可里点「允许」。 */
    fun allowConsent() {
        val w = work ?: return
        val consent = _state.value.consent ?: return
        if (!isCurrent(w) || _state.value.busy) return
        set { it.copy(busy = true, consent = null, phase = ParentingIntakePhase.Preparing) }
        launch(w) {
            try {
                service!!.allow(w.request.conversationID, consent)
                check(w)
                send(w)
            } catch (e: IntakeUnavailableException) {
                if (isCurrent(w)) fail(w, NOT_SENT, Recovery.NotSent)
            }
        }
    }

    /** 「暂不允许」：不发送，清掉专用会话。 */
    fun declineConsent() {
        val w = work ?: return
        if (_state.value.phase != ParentingIntakePhase.AwaitingConsent) return
        generation += 1
        work = null
        enqueueCleanup(IntakeCleanupReference(w.reference.serverScopeID, w.reference.conversationID))
        set { it.copy(phase = ParentingIntakePhase.Cancelled, consent = null, busy = false) }
        scope.launch { drainCleanup() }
    }

    private suspend fun send(w: Work) {
        val svc = service ?: return
        val conversation = w.conversation ?: return
        // 发出之前先落恢复记录：进程中途被杀，冷启动只能读回，不会重发。
        try {
            journal?.saveRecovery(w.reference)
        } catch (e: Exception) {
            fail(w, STORAGE, Recovery.NotSent); return
        }
        check(w)
        set { it.copy(phase = ParentingIntakePhase.Sending, busy = true) }
        w.sent = true
        val outcome = try {
            svc.start(w.request, conversation)
        } catch (e: IntakeUnavailableException) {
            IntakeOutcome.Failed("parenting_intake_unavailable", notSent = e.status == 404 || e.status == 405)
        }
        project(w, outcome)
    }

    private fun project(w: Work, outcome: IntakeOutcome) {
        if (!isCurrent(w)) return
        when (outcome) {
            is IntakeOutcome.Completed -> {
                val draft = try {
                    w.request.parse(outcome.text, epoch.capture())
                } catch (e: ParentingIntakeException) {
                    if (e.kind == ParentingIntakeException.Kind.ChangedAccount) { invalidate(); return }
                    fail(w, INVALID, Recovery.Terminal); return
                }
                runCatching { journal?.deleteRecovery() }
                w.recovery = Recovery.Terminal
                set { it.copy(phase = ParentingIntakePhase.Ready, busy = false, candidate = draft, error = null, recoveryAvailable = false) }
            }
            is IntakeOutcome.Failed -> {
                if (outcome.notSent) {
                    runCatching { journal?.deleteRecovery() }
                    fail(w, if (outcome.code == "parenting_intake_unavailable") UNAVAILABLE else NOT_SENT, Recovery.NotSent)
                } else {
                    runCatching { journal?.deleteRecovery() }
                    enqueueCleanup(IntakeCleanupReference(w.reference.serverScopeID, w.reference.conversationID))
                    fail(w, if (outcome.code == "cancelled") FAILED else failureMessage(outcome.code), Recovery.Terminal)
                    scope.launch { drainCleanup() }
                }
            }
            IntakeOutcome.Unknown -> {
                w.recovery = Recovery.ReadOnly
                set { it.copy(phase = ParentingIntakePhase.Unknown, busy = false, error = UNKNOWN) }
            }
        }
    }

    private fun fail(w: Work, message: String, recovery: Recovery) {
        if (!isCurrent(w)) return
        w.recovery = recovery
        set { it.copy(phase = ParentingIntakePhase.Failed, busy = false, error = message) }
    }

    /** 「重试本次整理」/「重新发起整理」。 */
    fun retry() {
        val s = _state.value
        if (s.busy) return
        val w = work
        if (w == null) {
            val input = lastInput ?: return
            if (s.canRetry) { set { it.copy(phase = ParentingIntakePhase.Idle) }; request(input.first, input.second, input.third) }
            return
        }
        if (!isCurrent(w) || s.phase != ParentingIntakePhase.Failed) return
        when (w.recovery) {
            Recovery.ReadOnly -> reconcile()
            Recovery.Terminal -> { set { it.copy(phase = ParentingIntakePhase.Idle) }; request(w.reference.sourceText, w.reference.childID, w.reference.goalID) }
            Recovery.NotSent -> {
                set { it.copy(phase = ParentingIntakePhase.Preparing, busy = true, error = null) }
                launch(w) { if (w.conversation == null) prepare(w) else send(w) }
            }
        }
    }

    /** 只读：GET 原请求的事件。结果仍未确认就保持 Unknown，永远不会变成一次新的发送。 */
    fun reconcile() {
        val w = work ?: return
        if (_state.value.busy || !isCurrent(w) || w.recovery != Recovery.ReadOnly) return
        set { it.copy(busy = true, error = null) }
        launch(w) {
            val outcome = try { service!!.observe(w.request) } catch (e: IntakeUnavailableException) { IntakeOutcome.Unknown }
            check(w)
            if (outcome is IntakeOutcome.Failed && outcome.code == "parenting_intake_not_found") {
                set { it.copy(phase = ParentingIntakePhase.Unknown, busy = false, error = UNKNOWN) }
            } else project(w, outcome)
        }
    }

    /** 冷恢复：读本机加密记录并只读回读原请求；不会发送模型请求。原话交还给表单。 */
    fun recover() {
        if (work != null) { if (_state.value.canReconcile) reconcile(); return }
        val j = journal ?: return
        val token = epoch.capture() ?: return
        val subject = subjectID
        if (_state.value.busy || subject == null || service == null || !tokenMatches()) return
        val ref = runCatching { j.readRecovery() }.getOrNull()?.takeIf { it.subjectID == subjectID } ?: run {
            set { it.copy(recoveryAvailable = false, error = STORAGE, phase = ParentingIntakePhase.Failed) }; return
        }
        val request = runCatching {
            ParentingIntakeRequest(ref.requestID, subject, token, ref.conversationID, ref.sourceText, ref.childID, ref.goalID, ref.startedAt, ZoneId.of(ref.timeZoneID))
        }.getOrNull() ?: run { set { it.copy(recoveryAvailable = false, error = STORAGE, phase = ParentingIntakePhase.Failed) }; return }
        val w = Work(generation, request, ref, recovery = Recovery.ReadOnly, sent = true)
        work = w
        lastInput = Triple(ref.sourceText, ref.childID, ref.goalID)
        set { it.copy(phase = ParentingIntakePhase.Unknown, recoveredSource = ref, recoveryAvailable = false) }
        reconcile()
    }

    /** 「停止等待本次整理」：停止本页等待；已发出的请求尽力取消，并把临时会话放进清理队列。 */
    fun cancel() {
        val w = work ?: run { set { it.copy(phase = ParentingIntakePhase.Cancelled, busy = false) }; return }
        job?.cancel()
        generation += 1
        work = null
        runCatching { journal?.deleteRecovery() }
        enqueueCleanup(IntakeCleanupReference(w.reference.serverScopeID, w.reference.conversationID))
        set { it.copy(phase = ParentingIntakePhase.Cancelled, busy = false, consent = null, candidate = null, error = null, recoveryAvailable = false) }
        val svc = service
        scope.launch {
            withContext(NonCancellable) {
                if (w.sent && svc != null) runCatching { svc.cancel(w.request) }
                drainCleanup()
            }
        }
    }

    /** 家长采用了候选：候选交给表单，临时记录进入清理。 */
    fun consumeCandidate(): ParentingIntakeDraft? {
        val candidate = _state.value.candidate ?: return null
        if (!tokenMatches()) { invalidate(); return null }
        work?.let { enqueueCleanup(IntakeCleanupReference(it.reference.serverScopeID, it.reference.conversationID)) }
        work = null
        generation += 1
        set { it.copy(phase = ParentingIntakePhase.Idle, candidate = null, error = null) }
        scope.launch { drainCleanup() }
        return candidate
    }

    /** 「删除本次整理记录」：本机恢复记录与云端临时会话一起清理。 */
    fun deleteRecord() {
        val ref = runCatching { journal?.readRecovery() }.getOrNull()
        val w = work
        job?.cancel(); generation += 1; work = null
        ref?.let { enqueueCleanup(IntakeCleanupReference(it.serverScopeID, it.conversationID)) }
        w?.let { enqueueCleanup(IntakeCleanupReference(it.reference.serverScopeID, it.reference.conversationID)) }
        runCatching { journal?.deleteRecovery() }
        set { State(deletionPending = it.deletionPending) }
        scope.launch { drainCleanup() }
    }

    fun retryDeletion() {
        if (_state.value.busy) return
        scope.launch { drainCleanup() }
    }

    /** 表单原话被改、账号变化、页面离开：作废在途结果。 */
    fun invalidate() {
        val w = work
        job?.cancel(); generation += 1; work = null
        if (w != null) {
            if (w.recovery != Recovery.ReadOnly) runCatching { journal?.deleteRecovery() }
            if (w.sent || w.conversation != null) enqueueCleanup(IntakeCleanupReference(w.reference.serverScopeID, w.reference.conversationID))
        }
        set { it.copy(phase = ParentingIntakePhase.Idle, busy = false, candidate = null, consent = null, error = null) }
        refreshStored()
        if (_state.value.deletionPending) scope.launch { drainCleanup() }
    }

    private fun enqueueCleanup(item: IntakeCleanupReference) {
        val j = journal ?: return
        val existing = runCatching { j.readCleanup() }.getOrDefault(emptyList())
        if (item in existing) return
        runCatching { j.saveCleanup(existing + item) }
        _state.update { it.copy(deletionPending = true) }
    }

    private suspend fun drainCleanup() {
        val j = journal ?: return
        val svc = service ?: return
        val items = runCatching { j.readCleanup() }.getOrDefault(emptyList())
        if (items.isEmpty()) { set { it.copy(deletionPending = false, deletionError = null) }; return }
        val remaining = mutableListOf<IntakeCleanupReference>()
        for (item in items) {
            try {
                svc.delete(item.serverScopeID, item.conversationID)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                remaining += item
            }
        }
        runCatching { j.saveCleanup(remaining) }
        set { it.copy(deletionPending = remaining.isNotEmpty(), deletionError = if (remaining.isEmpty()) null else CLEANUP_FAILED) }
    }

    private fun stop() {
        job?.cancel(); job = null
        generation += 1
        work = null
    }

    private fun isCurrent(w: Work) = w.generation == generation && work === w && tokenMatches()

    private fun check(w: Work) {
        if (!isCurrent(w)) throw CancellationException("stale intake")
    }

    private fun launch(w: Work, block: suspend () -> Unit) {
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                if (w.generation == generation && work === w && !tokenMatches()) invalidateForAccount()
                throw e
            } catch (e: Exception) {
                if (!isCurrent(w)) return@launch
                if (w.sent) {
                    w.recovery = Recovery.ReadOnly
                    set { it.copy(phase = ParentingIntakePhase.Unknown, busy = false, error = UNKNOWN) }
                } else fail(w, NOT_SENT, Recovery.NotSent)
            }
        }
    }

    private fun invalidateForAccount() {
        work = null
        set { it.copy(phase = ParentingIntakePhase.Failed, busy = false, candidate = null, consent = null, error = CHANGED) }
    }

    companion object {
        // iOS ParentingInlineIntakeError 的原句。
        const val UNAVAILABLE = "AI 整理暂时不可用。原话仍保留，可以继续手动填写。"
        const val CHANGED = "账号、输入或页面状态已变化，本次结果不会填回。原话仍保留。"
        const val NOT_SENT = "本次整理尚未发送。原话已保留，可以重试原请求。"
        const val UNKNOWN = "本次整理结果尚未确认。请读取原请求状态，不要重复发送。"
        const val FAILED = "本次整理未能完成。原话仍保留，可以手动填写或重新发起整理。"
        const val STORAGE = "整理记录无法安全保存，已停止请求。原文件与原话会保留。"
        const val INVALID = "整理回复未通过格式或来源校验。原话仍保留，可以继续手动填写。"
        const val CLEANUP_MESSAGE = "请先重试清理，再发起新的整理。表单原话和已保存的安排不受影响。"
        const val CLEANUP_FAILED = "云端临时整理数据尚未确认清理，可以稍后重试清理。"
        const val DAILY_LIMIT = "今天的 AI 整理次数已用完。原话仍保留，可以手动填写。"

        fun failureMessage(code: String): String = when (code) {
            "model_daily_limit", "quota_exhausted" -> DAILY_LIMIT
            "parenting_intake_output_invalid", "parenting_intake_hash_mismatch" -> INVALID
            else -> FAILED
        }
    }
}

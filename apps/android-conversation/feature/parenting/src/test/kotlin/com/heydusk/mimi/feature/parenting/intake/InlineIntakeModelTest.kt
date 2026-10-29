package com.heydusk.mimi.feature.parenting.intake

import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.parenting.ParentingIntakePhase
import com.heydusk.mimi.domain.parenting.ParentingIntakeRequest
import com.heydusk.mimi.network.intake.IntakeCancelReceipt
import com.heydusk.mimi.network.intake.IntakeConsentRequest
import com.heydusk.mimi.network.intake.IntakeConversation
import com.heydusk.mimi.network.intake.IntakeOutcome
import com.heydusk.mimi.network.intake.IntakeUnavailableException
import com.heydusk.mimi.network.intake.MemoryIntakeJournal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

internal class FakeIntake(override val serverScopeID: UUID = UUID.randomUUID()) : IntakeService {
    var consent: IntakeConsentRequest? = null
    var outcome: (ParentingIntakeRequest) -> IntakeOutcome = { IntakeOutcome.Unknown }
    var observed: (ParentingIntakeRequest) -> IntakeOutcome = { IntakeOutcome.Unknown }
    var deleteFails = false
    val starts = mutableListOf<ParentingIntakeRequest>()
    val observes = mutableListOf<UUID>()
    val deletes = mutableListOf<UUID>()
    val cancels = mutableListOf<UUID>()
    var allowed = 0
    override suspend fun open(conversationID: UUID) = IntakeConversation(1, 1)
    override suspend fun consent(conversationID: UUID) = consent
    override suspend fun allow(conversationID: UUID, consent: IntakeConsentRequest) { allowed++; this.consent = null }
    override suspend fun start(request: ParentingIntakeRequest, conversation: IntakeConversation): IntakeOutcome { starts += request; return outcome(request) }
    override suspend fun observe(request: ParentingIntakeRequest): IntakeOutcome { observes += request.id; return observed(request) }
    override suspend fun cancel(request: ParentingIntakeRequest): IntakeCancelReceipt { cancels += request.id; return IntakeCancelReceipt(true, false, "cancelled") }
    override suspend fun delete(serverScopeID: UUID, conversationID: UUID) { if (deleteFails) throw IntakeUnavailableException(503, null); deletes += conversationID }
}

internal fun reply(r: ParentingIntakeRequest, title: String = "带孩子去游泳", due: String? = "2026-10-30T07:00:00Z", evidence: String? = "明天下午三点") =
    """{"clarification":null,"contract":"mimi.parenting-intake.v1","due_at":${due?.let { "\"$it\"" } ?: "null"},"request_id":"${r.id.toString().lowercase()}","schema_version":1,"time_evidence":${evidence?.let { "\"$it\"" } ?: "null"},"title":"$title"}"""

@OptIn(ExperimentalCoroutinesApi::class)
class InlineIntakeModelTest {
    private val subject = UUID.randomUUID()
    private val child = UUID.randomUUID()
    private val epoch = AccountEpoch(subject.toString())
    private val journal = MemoryIntakeJournal()
    private val service = FakeIntake()
    private val source = "明天下午三点带孩子去游泳"

    private fun TestScope.model() = InlineIntakeModel(
        subject, service, journal, epoch, TestScope(UnconfinedTestDispatcher(testScheduler)),
        clock = { Instant.parse("2026-10-29T02:00:00Z") }, zone = { ZoneId.of("Asia/Shanghai") },
    )

    @Test fun completedReplyBecomesCandidateOnlyUntilParentAdopts() = runTest {
        service.outcome = { IntakeOutcome.Completed(reply(it)) }
        val m = model()
        m.request(source, child, null)
        val s = m.state.value
        assertEquals(ParentingIntakePhase.Ready, s.phase)
        assertEquals("带孩子去游泳", s.candidate?.title)
        assertEquals(child, s.candidate?.childID)
        assertNull(journal.recovery, "terminal result drops the recovery record")
        val adopted = m.consumeCandidate()
        assertNotNull(adopted)
        assertEquals(ParentingIntakePhase.Idle, m.state.value.phase)
        assertEquals(listOf(service.starts.single().conversationID), service.deletes, "temporary conversation cleaned after adopt")
    }

    @Test fun consentIsAskedBeforeAnythingIsSentAndDeclineSendsNothing() = runTest {
        service.consent = IntakeConsentRequest("发送许可", "说明", "a".repeat(64), 0)
        val m = model()
        m.request(source, child, null)
        assertEquals(ParentingIntakePhase.AwaitingConsent, m.state.value.phase)
        assertTrue(service.starts.isEmpty())
        m.declineConsent()
        assertEquals(ParentingIntakePhase.Cancelled, m.state.value.phase)
        assertTrue(service.starts.isEmpty())
        assertEquals(1, service.deletes.size)
    }

    @Test fun allowConsentThenSends() = runTest {
        service.consent = IntakeConsentRequest("发送许可", "说明", "a".repeat(64), 0)
        service.outcome = { IntakeOutcome.Completed(reply(it)) }
        val m = model()
        m.request(source, child, null)
        m.allowConsent()
        assertEquals(1, service.allowed)
        assertEquals(ParentingIntakePhase.Ready, m.state.value.phase)
    }

    @Test fun unknownOutcomeOnlyReconcilesReadOnlyAndNeverResends() = runTest {
        val m = model()
        m.request(source, child, null)
        assertEquals(ParentingIntakePhase.Unknown, m.state.value.phase)
        assertNotNull(journal.recovery, "unknown keeps the recovery record")
        m.request(source, child, null) // 未确认时不能再发
        m.retry()
        assertEquals(1, service.starts.size)
        service.observed = { IntakeOutcome.Completed(reply(it)) }
        m.reconcile()
        assertEquals(1, service.starts.size)
        assertEquals(1, service.observes.size)
        assertEquals(ParentingIntakePhase.Ready, m.state.value.phase)
    }

    @Test fun notSentFailureRetriesSameRequest() = runTest {
        var first = true
        service.outcome = { if (first) { first = false; IntakeOutcome.Failed("conversation_busy", notSent = true) } else IntakeOutcome.Completed(reply(it)) }
        val m = model()
        m.request(source, child, null)
        assertEquals(ParentingIntakePhase.Failed, m.state.value.phase)
        assertEquals(InlineIntakeModel.NOT_SENT, m.state.value.error)
        m.retry()
        assertEquals(ParentingIntakePhase.Ready, m.state.value.phase)
        assertEquals(1, service.starts.map { it.id }.toSet().size, "retry reuses the same turn id (idempotent)")
    }

    @Test fun dailyLimitIsTerminalWithIosSentence() = runTest {
        service.outcome = { IntakeOutcome.Failed("model_daily_limit", notSent = false) }
        val m = model()
        m.request(source, child, null)
        assertEquals(InlineIntakeModel.DAILY_LIMIT, m.state.value.error)
    }

    @Test fun cancelStopsWaitingAndAsksServerToCancel() = runTest {
        val m = model()
        m.request(source, child, null) // Unknown → still has work
        m.cancel()
        assertEquals(ParentingIntakePhase.Cancelled, m.state.value.phase)
        assertEquals(1, service.cancels.size)
        assertNull(journal.recovery)
    }

    @Test fun coldRecoveryIsReadOnly() = runTest {
        val first = model()
        first.request(source, child, null) // Unknown, recovery saved
        val reborn = model()
        assertTrue(reborn.state.value.recoveryAvailable)
        service.observed = { IntakeOutcome.Completed(reply(it)) }
        reborn.recover()
        assertEquals(1, service.starts.size, "recovery never sends again")
        assertEquals(ParentingIntakePhase.Ready, reborn.state.value.phase)
    }

    @Test fun cleanupFailureBlocksNewRequestUntilRetried() = runTest {
        service.outcome = { IntakeOutcome.Completed(reply(it)) }
        service.deleteFails = true
        val m = model()
        m.request(source, child, null)
        m.consumeCandidate()
        assertTrue(m.state.value.deletionPending)
        m.request(source, child, null)
        assertEquals(InlineIntakeModel.CLEANUP_MESSAGE, m.state.value.deletionError)
        assertEquals(1, service.starts.size)
        service.deleteFails = false
        m.retryDeletion()
        assertFalse(m.state.value.deletionPending)
    }

    @Test fun accountChangeDropsResult() = runTest {
        service.outcome = { epoch.activate(UUID.randomUUID().toString()); IntakeOutcome.Completed(reply(it)) }
        val m = model()
        m.request(source, child, null)
        assertNull(m.state.value.candidate)
    }

    @Test fun unavailableWithoutService() = runTest {
        val m = InlineIntakeModel(null, null, null, epoch, this)
        assertFalse(m.available)
        m.request(source, child, null)
        assertEquals(InlineIntakeModel.UNAVAILABLE, m.state.value.error)
    }
}

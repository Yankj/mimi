package com.heydusk.mimi.network

import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.json.CanonicalJson
import com.heydusk.mimi.domain.parenting.ParentingIntakeRequest
import com.heydusk.mimi.network.auth.AuthUser
import com.heydusk.mimi.network.auth.SealedAuthVault
import com.heydusk.mimi.network.enrollment.FeatureEnrollmentClient
import com.heydusk.mimi.network.intake.IntakeCleanupReference
import com.heydusk.mimi.network.intake.IntakeOutcome
import com.heydusk.mimi.network.intake.IntakeRecoveryReference
import com.heydusk.mimi.network.intake.IntakeUnavailableException
import com.heydusk.mimi.network.intake.ParentingIntakeClient
import com.heydusk.mimi.network.intake.SealedIntakeJournal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class IntakeAndEnrollmentTest {
    private val server = MockWebServer()
    private val subject = UUID.randomUUID()
    private val scope = UUID.randomUUID()
    private lateinit var client: ParentingIntakeClient
    private lateinit var request: ParentingIntakeRequest

    @BeforeTest fun up() {
        server.start()
        client = ParentingIntakeClient(server.config(), UrlConnectionTransport())
        val token = AccountEpoch(subject.toString()).capture()!!
        request = ParentingIntakeRequest(UUID.randomUUID(), subject, token, UUID.randomUUID(), "明天下午三点带孩子去游泳", UUID.randomUUID(), null, Instant.parse("2026-10-29T02:00:00Z"), ZoneId.of("Asia/Shanghai"))
    }
    @AfterTest fun down() = server.shutdown()

    private fun event(seq: Int, type: String, data: String) =
        """data: {"type":"$type","sequence":$seq,"intake_contract":"${ParentingIntakeRequest.CONTRACT}","turn_id":"${request.id}","conversation_id":"${request.conversationID}","scope_id":"$scope","data":$data}""" + "\n\n"

    private fun stream(text: String, hashOf: String = text, redacted: Boolean = false): ByteArray {
        val hash = CanonicalJson.sha256Hex(hashOf.toByteArray())
        val delta = if (redacted) """{"intake_redacted":true}""" else """{"text":${com.heydusk.mimi.network.JsonOut.obj("t" to text).removePrefix("{\"t\":").removeSuffix("}")}}"""
        return (event(1, "turn_started", "{}") + event(2, "text_delta", delta) + event(3, "turn_completed", """{"final_text_hash":"$hash"}""")).toByteArray()
    }

    @Test fun liveStreamWithMatchingHashCompletes() {
        val out = client.readStream(stream("""{"title":"游泳"}"""), scope, request)
        assertEquals(IntakeOutcome.Completed("""{"title":"游泳"}"""), out)
    }

    @Test fun redactedReplayIsUnknownNotCompleted() {
        assertEquals(IntakeOutcome.Unknown, client.readStream(stream("x", redacted = true), scope, request))
    }

    @Test fun hashMismatchFails() {
        assertIs<IntakeOutcome.Failed>(client.readStream(stream("a", hashOf = "b"), scope, request))
    }

    @Test fun streamWithoutTerminalIsUnknown() {
        assertEquals(IntakeOutcome.Unknown, client.readStream(event(1, "turn_started", "{}").toByteArray(), scope, request))
    }

    @Test fun eventForAnotherTurnIsRejected() {
        val other = event(1, "turn_started", "{}").replace(request.id.toString(), UUID.randomUUID().toString())
        assertIs<IntakeOutcome.Failed>(client.readStream(other.toByteArray(), scope, request))
    }

    @Test fun startSendsRestrictedHeadersAndMapsStatuses() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(String(stream("ok"))))
        val conv = com.heydusk.mimi.network.intake.IntakeConversation(3, 1)
        assertEquals(IntakeOutcome.Completed("ok"), client.start("Bearer t", scope, request, conv))
        val r = server.takeRequest()
        assertEquals("0", r.getHeader("X-Catpaw-Use-Memory"))
        assertEquals(request.id.toString(), r.getHeader("Idempotency-Key"))
        assertTrue(r.path!!.startsWith("/catpaw/parenting-intake/v1/scopes/$scope/conversations/${request.conversationID}/turns"))
        val body = r.body.readUtf8()
        assertTrue(body.contains("\"context\":[]") && body.contains("\"expected_revision\":3") && body.contains(request.id.toString()))

        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        assertFailsWith<IntakeUnavailableException> { client.start("Bearer t", scope, request, conv) }
        server.enqueue(MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json").setBody("""{"error":{"code":"conversation_busy"}}"""))
        assertEquals(IntakeOutcome.Failed("conversation_busy", notSent = true), client.start("Bearer t", scope, request, conv))
        server.enqueue(MockResponse().setResponseCode(502))
        assertEquals(IntakeOutcome.Unknown, client.start("Bearer t", scope, request, conv))
        // 回放的 JSON 回执（正文已脱敏）不是完成。
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{}"))
        assertEquals(IntakeOutcome.Unknown, client.start("Bearer t", scope, request, conv))
    }

    @Test fun observeIsReadOnlyGet() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(String(stream("x", redacted = true))))
        assertEquals(IntakeOutcome.Unknown, client.observe("Bearer t", scope, request))
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertTrue(r.path!!.endsWith("/turns/${request.id}/events"))
    }

    @Test fun enrollmentMapsInvalidRateLimitAndSignedOut() = runTest {
        val now = Instant.parse("2026-10-29T00:00:00Z")
        val e = FeatureEnrollmentClient(server.config(), UrlConnectionTransport()) { now }
        assertEquals(FeatureEnrollmentClient.SubmitResult.Invalid, e.submit("Bearer t", "short"))
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"enrolled":true,"features":{"parenting_intake":true}}"""))
        assertEquals(FeatureEnrollmentClient.SubmitResult.Done(FeatureEnrollmentClient.Status(true, setOf("parenting_intake"))), e.submit("Bearer t", "CP-ABCD-EFGH-JKLM"))
        assertTrue(server.takeRequest().body.readUtf8().contains("CP-ABCD-EFGH-JKLM"))
        server.enqueue(MockResponse().setResponseCode(422).setBody("{}"))
        assertEquals(FeatureEnrollmentClient.SubmitResult.Invalid, e.submit("Bearer t", "ABCDEFGHJKLM"))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"retry_after_sec":120}}"""))
        assertEquals(FeatureEnrollmentClient.SubmitResult.RateLimited(now.plusSeconds(120)), e.submit("Bearer t", "ABCDEFGHJKLM"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertEquals(FeatureEnrollmentClient.SubmitResult.SignedOut, e.submit("Bearer t", "ABCDEFGHJKLM"))
    }

    @Test fun sealedVaultRoundTripsWithoutPlaintextTokens() {
        val blobs = MemoryBlobs()
        val vault = SealedAuthVault(blobs, XorCipher())
        val c = credential(subject, Instant.parse("2026-10-29T03:00:00Z"), access = "secret-access-token", refresh = "secret-refresh")
        vault.save(c); vault.savePending(c.copy(accessToken = "next"))
        assertEquals(c, vault.load()); assertEquals("next", vault.loadPending()?.accessToken)
        blobs.keys().forEach { k -> assertFalse(String(blobs.raw(k)!!).contains("secret-access-token")) }
        vault.setSignedOut(true); assertTrue(vault.isSignedOut()); vault.setSignedOut(false); assertFalse(vault.isSignedOut())
        vault.clear(); vault.clearPending(); assertNull(vault.load()); assertNull(vault.loadPending())
        assertEquals(AuthUser(subject, "a@example.com", false), c.user)
    }

    @Test fun intakeJournalIsPerSubject() {
        val blobs = MemoryBlobs()
        val j = SealedIntakeJournal(blobs, XorCipher(), subject)
        val ref = IntakeRecoveryReference(UUID.randomUUID(), subject, scope, UUID.randomUUID(), "原话", UUID.randomUUID(), null, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), "Asia/Shanghai")
        j.saveRecovery(ref)
        assertEquals(ref, j.readRecovery())
        assertNull(SealedIntakeJournal(blobs, XorCipher(), UUID.randomUUID()).readRecovery())
        j.saveCleanup(listOf(IntakeCleanupReference(scope, ref.conversationID)))
        assertEquals(1, j.readCleanup().size)
        // 另一个主体的记录（被篡改的明文 subject）读出来也会被丢弃。
        j.saveRecovery(ref.copy(subjectID = UUID.randomUUID()))
        assertNull(j.readRecovery())
    }
}

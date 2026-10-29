package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.json.CanonicalJson
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** iOS ParentingWorkspaceTests / ParentingRescheduleIntakeTests 的整理请求与解码夹具。 */
class ParentingIntakeTest {
    private fun stableID(text: String): UUID {
        val h = CanonicalJson.sha256Hex(text.toByteArray())
        return UUID.fromString(h.substring(0, 8) + "-" + h.substring(8, 12) + "-4" + h.substring(13, 16) + "-8" + h.substring(17, 20) + "-" + h.substring(20, 32))
    }
    private val subject = stableID("reschedule-intake-subject")
    private val child = stableID("reschedule-intake-child")
    private val goal = stableID("reschedule-intake-goal")
    private val requestID = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val conversationID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val token = AccountEpoch(subject.toString().uppercase()).capture()!!

    private fun request(text: String, zone: String = "GMT", now: String = "2026-10-23T12:00:00Z", target: ParentingArrangementEditTarget? = null) =
        ParentingIntakeRequest(requestID, subject, token, conversationID, text, child, goal, Instant.parse(now), ZoneId.of(zone), target)

    private fun body(r: ParentingIntakeRequest, due: String?, evidence: String?, title: String = "合成绘本时间", clarification: String? = "请核对时间"): String {
        fun q(s: String?) = s?.let { "\"$it\"" } ?: "null"
        return """{"clarification":${q(clarification)},"contract":"mimi.parenting-intake.v1","due_at":${q(due)},"request_id":"${r.id.toString().lowercase()}","schema_version":1,"time_evidence":${q(evidence)},"title":"$title"}"""
    }

    @Test fun pinnedM1PromptMatchesIosHash() {
        // iOS testNilContextRetainsPinnedM1PromptAndLegacyRecoveryDecodes：同一输入的 SHA-256。
        val r = request("明天15:00合成绘本时间")
        assertEquals("db7bcc3f008f3145514f443717ef25a43e9112c7293d32c449a113d69f88ec12", CanonicalJson.sha256Hex(r.prompt.toByteArray()))
        assertTrue(r.prompt.contains(requestID.toString()))
    }

    @Test fun completedReplyKeepsSelectedIdentityAndUnknownDate() {
        val r = request("合成原话：找一天读绘本")
        val d = r.parse(body(r, null, null), token)
        assertEquals(child, d.childID); assertEquals(goal, d.goalID); assertNull(d.dueAt); assertEquals("请核对时间", d.clarification)
        assertEquals(r.sourceText, d.sourceText)
    }

    @Test fun explicitDateWithLiteralEvidence() {
        val r = request("明天15:00合成绘本时间")
        val d = r.parse(body(r, "2026-10-24T15:00:00Z", "明天15:00", clarification = null), token)
        assertEquals(Instant.parse("2026-10-24T15:00:00Z"), d.dueAt)
    }

    @Test fun nonceExtraIdentityDuplicateKeysAndMalformedJsonRejected() {
        val r = request("明天15:00合成绘本时间")
        val valid = body(r, null, null)
        val cases = listOf(
            valid.replace(r.id.toString().lowercase(), stableID("parenting-intake-wrong-nonce").toString()),
            valid.replace("{", "{\"child_id\":\"${stableID("x")}\","),
            valid.replace("\"schema_version\":1", "\"schema_version\":true"),
            """{"schema_version":1,"contract":"mimi.parenting-intake.v1","request_id":"${r.id.toString().lowercase()}","title":"合成甲","title":"合成乙","due_at":null,"time_evidence":null,"clarification":null}""",
            "not JSON",
            "```json\n$valid\n```",
        )
        for (c in cases) {
            val e = assertFailsWith<ParentingIntakeException>(c) { r.parse(c, token) }
            assertEquals(ParentingIntakeException.Kind.InvalidReply, e.kind, c)
        }
    }

    @Test fun datesWithoutLiteralEvidenceRejected() {
        val r = request("明天15:00合成绘本时间")
        for (evidence in listOf(null, "后天15:00", "明天")) {
            val e = assertFailsWith<ParentingIntakeException> { r.parse(body(r, "2026-10-24T15:00:00Z", evidence), token) }
            assertEquals(ParentingIntakeException.Kind.MissingTimeEvidence, e.kind)
        }
        val e = assertFailsWith<ParentingIntakeException> { r.parse(body(r, null, "明天15:00"), token) }
        assertEquals(ParentingIntakeException.Kind.InvalidReply, e.kind)
    }

    @Test fun changedAccountRejected() {
        val r = request("明天15:00合成绘本时间")
        val e = assertFailsWith<ParentingIntakeException> { r.parse(body(r, null, null), null) }
        assertEquals(ParentingIntakeException.Kind.ChangedAccount, e.kind)
        assertFailsWith<ParentingIntakeException> {
            ParentingIntakeRequest(requestID, subject, AccountEpoch(UUID.randomUUID().toString()).capture()!!, conversationID, "x", child, goal, Instant.now(), ZoneId.of("GMT"))
        }
    }

    @Test fun rescheduleKeepsOriginalTitleAndNeverInheritsTime() {
        val created = Instant.parse("2026-10-22T12:00:00Z")
        val target = ParentingArrangementEditTarget(
            ParentingArrangement(stableID("reschedule-intake-arrangement"), child, "Original selected arrangement", Instant.parse("2026-10-23T15:00:00Z"), goal,
                ParentingArrangementState.Planned, "Original source must remain local", created, created), 73,
        )
        val r = request("Change to 2027-02-18T17:00:00Z.", zone = "Asia/Shanghai", now = "2026-10-23T16:30:00Z", target = target)
        assertTrue(r.prompt.contains("本次解释时区：Asia/Shanghai"))
        for (privateValue in listOf(target.arrangement.id.toString(), child.toString(), goal.toString(), target.arrangement.sourceText)) assertTrue(privateValue !in r.prompt)
        val d = r.parse(body(r, "2027-02-18T17:00:00Z", "2027-02-18T17:00:00Z", title = "Untrusted replacement title", clarification = null), token)
        assertEquals("Original selected arrangement", d.title); assertEquals(Instant.parse("2027-02-18T17:00:00Z"), d.dueAt)
        val none = request("改到明天", zone = "Asia/Shanghai", now = "2026-10-23T16:30:00Z", target = target).let { it.parse(body(it, null, null, clarification = null), token) }
        assertNull(none.dueAt); assertTrue(none.clarification != null)
    }

    @Test fun swiftStringEscaping() {
        assertEquals("\"a\\/b\\\"c\\n\"", ParentingIntakeRequest.swiftJSONString("a/b\"c\n"))
    }
}

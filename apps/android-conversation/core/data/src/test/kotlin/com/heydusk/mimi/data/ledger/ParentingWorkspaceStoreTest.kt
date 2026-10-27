package com.heydusk.mimi.data.ledger

import com.heydusk.mimi.data.ledger.LedgerFixtures.childID
import com.heydusk.mimi.data.ledger.LedgerFixtures.goalID
import com.heydusk.mimi.data.ledger.LedgerFixtures.itemID
import com.heydusk.mimi.data.ledger.LedgerFixtures.now
import com.heydusk.mimi.data.ledger.LedgerFixtures.recordID
import com.heydusk.mimi.data.ledger.LedgerFixtures.stable
import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.common.DomainError
import com.heydusk.mimi.domain.parenting.ParentingArrangementState
import com.heydusk.mimi.domain.parenting.ParentingChild
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.domain.parenting.ParentingGoal
import com.heydusk.mimi.domain.parenting.ParentingRecordKind
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * 对应 iOS `ParentingWorkspaceTests` 里走加密仓库边界的用例（账本、回执、屏障、损坏）。
 * 密码学用真实的 AES-GCM + [KeystoreLedgerKeyProvider]（密钥库换成 JCA 内存 PKCS12）；磁盘是真实文件。
 */
class ParentingWorkspaceStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun harness(subject: UUID = LedgerFixtures.subject, dir: File = tmp.newFolder()) = LedgerHarness(dir, subject)
    private fun test(body: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { body() }

    @Test fun emptyLoadHasNoSeededChildOrImplicitWriteOrKey() = test {
        val h = harness()
        assertEquals(ParentingWorkspace.EMPTY, h.store.load())
        assertTrue(h.ledgerFiles().isEmpty(), "reading must not write")
        assertNull(h.keys.existingKey(h.subject), "reading must not mint a key")
    }

    @Test fun draftConfirmEditCompleteCreatesIndependentRecordAndColdReopens() = test {
        val h = harness()
        val draft = h.seedDraft(linkedGoal = true)
        assertEquals(ParentingArrangementState.Draft, draft.arrangements.first().state)
        assertTrue(draft.records.isEmpty())
        assertEquals(draft, h.reopen().load())

        assertEquals(ParentingArrangementState.Planned, h.apply(ParentingCommand.ConfirmArrangement(itemID)).arrangements.first().state)
        val revisedDue = now.plusSeconds(7200)
        val edited = h.apply(ParentingCommand.EditArrangement(itemID, "合成绘本改到下午", revisedDue, goalID))
        assertEquals(itemID, edited.arrangements.first().id); assertEquals(revisedDue, edited.arrangements.first().dueAt)
        val completed = h.apply(ParentingCommand.CompleteArrangement(itemID, recordID, "合成观察：主动选了故事"))
        assertEquals(ParentingArrangementState.Completed, completed.arrangements.first().state)
        val record = assertNotNull(completed.records.singleOrNull())
        assertEquals(recordID, record.id); assertNotEquals(itemID, record.id)
        assertEquals(itemID, record.arrangementID); assertEquals(childID, record.childID); assertEquals(goalID, record.goalID)
        assertEquals(ParentingRecordKind.Completion, record.kind); assertEquals("合成观察：主动选了故事", record.text)
        assertEquals(completed, h.reopen().load())
        val onDisk = h.diskBytes().toString(Charsets.ISO_8859_1)
        assertFalse("合成小星" in onDisk || "childID" in onDisk, "ciphertext must not contain plaintext fields")
        assertEquals(1, h.ledgerFiles().size)
    }

    @Test fun operationRetryAfterLaterEditReturnsLatestWorkspaceWithoutDuplicate() = test {
        val h = harness()
        val op = stable("parenting-test-retry")
        val command = ParentingCommand.AddChild(childID, "合成小星")
        val first = h.store.apply(command, 0, op)
        val edited = h.apply(ParentingCommand.RenameChild(childID, "合成新昵称"))
        val bytes = h.diskBytes()
        assertEquals(edited, h.store.apply(command, first.revision, op))
        assertEquals(edited, h.reopen().apply(command, 0, op), "a cold store reads the receipt from disk")
        assertEquals(1, edited.children.size)
        assertContentEquals(bytes, h.diskBytes(), "a retry writes nothing")
        assertEquals(edited, h.reopen().load())
    }

    @Test fun sameOperationWithDifferentCommandIsRejectedWithoutMutation() = test {
        val h = harness()
        val op = stable("parenting-test-mismatch")
        val first = h.store.apply(ParentingCommand.AddChild(childID, "合成小星"), 0, op)
        val bytes = h.diskBytes()
        val error = rejects { h.store.apply(ParentingCommand.RenameChild(childID, "合成误重试"), first.revision, op) }
        assertEquals(DomainError.Invalid("重复操作的内容不一致。"), error)
        assertContentEquals(bytes, h.diskBytes()); assertEquals(first, h.reopen().load())
    }

    @Test fun concurrentSameOperationWithDistinctSealedBytesHasOneDurableResult() = test {
        repeat(10) { round ->
            val h = harness()
            val barrier = CountDownLatch(2) // 两个 store 都读完（都看到"不存在"）之后才允许写：必然撞上比较并替换
            h.blobs.readBarrier = barrier
            val second = h.store()
            val command = ParentingCommand.AddChild(childID, "合成并发重试")
            val op = stable("parenting-test-concurrent-retry-$round")
            val results = listOf(async { h.store.apply(command, 0, op) }, async { second.apply(command, 0, op) }).awaitAll()
            assertEquals(results[0], results[1])
            assertEquals(1, results[0].revision); assertEquals(1, results[0].children.size)
            h.blobs.readBarrier = null
            assertEquals(results[0], h.reopen().load())
            assertEquals(1, h.blobs.writes.get(), "exactly one durable write won; the loser reconciled by receipt")
        }
    }

    @Test fun concurrentDifferentOperationsOnTwoStoresNeverLoseAnUpdate() = test {
        val h = harness()
        val a = h.store; val b = h.store()
        h.apply(ParentingCommand.AddChild(childID, "合成小星"))
        val rev = a.load().revision
        h.blobs.readBarrier = CountDownLatch(2)
        val outcomes = listOf(
            async { runCatching { a.apply(ParentingCommand.RenameChild(childID, "甲"), rev) } },
            async { runCatching { b.apply(ParentingCommand.RenameChild(childID, "乙"), rev) } },
        ).awaitAll()
        h.blobs.readBarrier = null
        assertEquals(1, outcomes.count { it.isSuccess }, "same revision: exactly one wins")
        assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is DomainError.Unavailable || outcomes.single { it.isFailure }.exceptionOrNull() is DomainError.Conflict)
        val final = h.reopen().load()
        assertEquals(rev + 1, final.revision)
    }

    @Test fun saveFailureDoesNotChangeMemoryOrDiskAndSameOperationCanRetry() = test {
        val h = harness()
        val original = h.seedDraft()
        val bytes = h.diskBytes()
        val op = stable("parenting-test-disk-failure")
        h.blobs.failNextWrite = true
        rejects { h.store.apply(ParentingCommand.ConfirmArrangement(itemID), original.revision, op) }
        assertContentEquals(bytes, h.diskBytes()); assertEquals(original, h.store.load()); assertEquals(original, h.reopen().load())
        assertTrue(h.directory.listFiles()!!.none { ".tmp-" in it.name }, "no half-written temp files")
        val retry = h.store.apply(ParentingCommand.ConfirmArrangement(itemID), original.revision, op)
        assertEquals(ParentingArrangementState.Planned, retry.arrangements.first().state)
        assertEquals(original.revision + 1, retry.revision)
    }

    @Test fun unconfirmedAndCancelledDraftCannotBecomeCompletedOrRevive() = test {
        val h = harness()
        val draft = h.seedDraft()
        rejects { h.store.apply(ParentingCommand.CompleteArrangement(itemID, recordID, "合成未获确认"), draft.revision) }
        val cancelled = h.apply(ParentingCommand.CancelArrangement(itemID))
        val bytes = h.diskBytes()
        rejects { h.store.apply(ParentingCommand.CompleteArrangement(itemID, recordID, "合成迟到结果"), cancelled.revision) }
        rejects { h.store.apply(ParentingCommand.ConfirmArrangement(itemID), cancelled.revision) }
        assertEquals(ParentingArrangementState.Cancelled, cancelled.arrangements.first().state); assertTrue(cancelled.records.isEmpty())
        assertContentEquals(bytes, h.diskBytes()); assertEquals(cancelled, h.reopen().load())
    }

    @Test fun repeatedCompletionCreatesOnlyOneRecordAndBlankNoteUsesTitle() = test {
        val h = harness()
        h.seedDraft(); h.apply(ParentingCommand.ConfirmArrangement(itemID))
        val completed = h.apply(ParentingCommand.CompleteArrangement(itemID, recordID, "  "))
        val again = h.apply(ParentingCommand.CompleteArrangement(itemID, stable("parenting-test-second-completion"), "合成重复点击"))
        assertEquals(completed.arrangements.first().title, completed.records.first().text)
        assertEquals("  ", completed.records.first().sourceText)
        assertEquals(completed.records, again.records); assertEquals(1, again.records.size)
    }

    @Test fun deletingGoalClearsReferencesAndKeepsArrangementAndObservation() = test {
        val h = harness()
        h.seedDraft(linkedGoal = true)
        h.apply(ParentingCommand.AddObservation(recordID, childID, goalID, "合成成长观察"))
        val removed = h.apply(ParentingCommand.DeleteGoal(goalID))
        assertTrue(removed.goals.isEmpty()); assertEquals(1, removed.arrangements.size); assertNull(removed.arrangements.first().goalID)
        assertEquals(1, removed.records.size); assertNull(removed.records.first().goalID)
        removed.validate()
        assertEquals(removed, h.reopen().load())
    }

    @Test fun deletingChildRemovesOnlyThatChildsReferencesAndSurvivesReopen() = test {
        val h = harness()
        h.seedDraft(linkedGoal = true)
        h.apply(ParentingCommand.AddObservation(recordID, childID, goalID, "合成将删除观察"))
        val other = stable("parenting-test-other-child"); val otherRecord = stable("parenting-test-other-observation")
        h.apply(ParentingCommand.AddChild(other, "合成小月"))
        h.apply(ParentingCommand.AddObservation(otherRecord, other, null, "合成保留观察"))
        val removed = h.apply(ParentingCommand.DeleteChild(childID))
        assertEquals(listOf(other), removed.children.map { it.id })
        assertTrue(removed.goals.isEmpty() && removed.arrangements.isEmpty())
        assertEquals(listOf(otherRecord), removed.records.map { it.id })
        removed.validate()
        assertEquals(removed, h.reopen().load())
    }

    @Test fun deletingArrangementPreservesIndependentEditableCompletionRecord() = test {
        val h = harness()
        h.seedDraft(); h.apply(ParentingCommand.ConfirmArrangement(itemID))
        h.apply(ParentingCommand.CompleteArrangement(itemID, recordID, "合成完成观察"))
        val removed = h.apply(ParentingCommand.DeleteArrangement(itemID))
        assertTrue(removed.arrangements.isEmpty()); assertEquals(recordID, removed.records.single().id); assertNull(removed.records.single().arrangementID)
        val edited = h.apply(ParentingCommand.EditRecord(recordID, "合成更正后的观察"))
        assertEquals("合成更正后的观察", edited.records.first().text); assertEquals("合成完成观察", edited.records.first().sourceText)
        assertEquals(edited, h.reopen().load())
        assertTrue(h.apply(ParentingCommand.DeleteRecord(recordID)).records.isEmpty())
    }

    @Test fun staleRevisionAndWrongChildGoalRejectWithoutChangingSavedDraft() = test {
        val h = harness()
        val draft = h.seedDraft(linkedGoal = true)
        val bytes = h.diskBytes()
        assertEquals(DomainError.Conflict, rejects { h.store.apply(ParentingCommand.ConfirmArrangement(itemID), draft.revision - 1) })
        rejects { h.store.apply(ParentingCommand.CreateDraft(stable("invalid-ref"), stable("missing-child"), "合成无效关联", now, goalID, ""), draft.revision) }
        assertContentEquals(bytes, h.diskBytes()); assertEquals(draft, h.reopen().load())
    }

    @Test fun suggestionsChoicePersistsWithoutChangingExistingArrangement() = test {
        val h = harness()
        val draft = h.seedDraft()
        val enabled = h.apply(ParentingCommand.SetSuggestionsEnabled(true))
        val paused = h.apply(ParentingCommand.SetSuggestionsEnabled(false))
        assertTrue(enabled.suggestionsEnabled); assertFalse(paused.suggestionsEnabled)
        assertEquals(draft.arrangements, paused.arrangements); assertEquals(paused, h.reopen().load())
    }

    @Test fun completeThenReopenPersistsAcrossColdRelaunchAndRetriesAreIdempotent() = test {
        val h = harness()
        h.seedDraft(linkedGoal = true); h.apply(ParentingCommand.ConfirmArrangement(itemID))
        val completed = h.apply(ParentingCommand.CompleteArrangement(itemID, recordID, ""))
        assertEquals(1, completed.records.size); assertEquals(completed, h.reopen().load())
        val op = UUID.randomUUID()
        val reopened = h.apply(ParentingCommand.ReopenArrangement(itemID), op)
        assertEquals(ParentingArrangementState.Planned, reopened.arrangements.first().state); assertTrue(reopened.records.isEmpty())
        assertEquals(reopened, h.reopen().load())
        assertEquals(reopened, h.store.apply(ParentingCommand.ReopenArrangement(itemID), completed.revision, op), "lost-ack retry returns the saved result")
        val again = h.apply(ParentingCommand.ReopenArrangement(itemID))
        assertEquals(reopened.arrangements, again.arrangements); assertEquals(reopened.records, again.records)
        val second = h.apply(ParentingCommand.CompleteArrangement(itemID, stable("parenting-test-recomplete"), ""))
        assertEquals(ParentingArrangementState.Completed, second.arrangements.first().state); assertEquals(1, second.records.size)
    }

    @Test fun reopenSaveFailureLeavesCompletedLedgerUntouchedOnDiskAndMemory() = test {
        val h = harness()
        h.seedDraft(); h.apply(ParentingCommand.ConfirmArrangement(itemID))
        val completed = h.apply(ParentingCommand.CompleteArrangement(itemID, recordID, ""))
        val before = h.diskBytes()
        h.blobs.failNextWrite = true
        rejects { h.apply(ParentingCommand.ReopenArrangement(itemID)) }
        assertContentEquals(before, h.diskBytes()); assertEquals(completed, h.store.load()); assertEquals(completed, h.reopen().load())
    }

    // ---- 账号屏障 ----
    @Test fun oldEpochCannotReadOrWriteAfterSameSubjectSignsInAgain() = test {
        val h = harness()
        val draft = h.seedDraft()
        val bytes = h.diskBytes()
        h.epoch.signOut(); h.epoch.activate(h.subject.toString())
        assertIs<DomainError.Unavailable>(rejects { h.store.load() })
        assertIs<DomainError.Unavailable>(rejects { h.store.apply(ParentingCommand.ConfirmArrangement(itemID), draft.revision) })
        assertContentEquals(bytes, h.diskBytes())
        assertEquals(draft, h.store().load(), "a store created after the new sign-in reads the ledger")
    }

    @Test fun epochChangeDuringSealPreventsDurableWrite() = test {
        val h = harness()
        val store = h.store(cipher = EpochChangingCipher(h.cipher, h.epoch))
        val error = rejects { store.apply(ParentingCommand.AddChild(childID, "合成过期操作"), 0) }
        assertEquals(DomainError.Unavailable("账号已变更，育儿账本未写入。"), error)
        assertTrue(h.ledgerFiles().isEmpty()); assertEquals(0, h.blobs.writes.get())
    }

    @Test fun twoSubjectsInTheSameDirectoryNeverReadEachOthersLedger() = test {
        val dir = tmp.newFolder()
        val h = LedgerHarness(dir)
        val original = h.seedDraft()
        val other = stable("parenting-test-other-subject")
        val otherEpoch = AccountEpoch(other.toString())
        val otherStore = h.store(subject = other, epoch = otherEpoch)
        assertEquals(ParentingWorkspace.EMPTY, otherStore.load())
        val otherSaved = otherStore.apply(ParentingCommand.AddChild(childID, "合成独立家庭"), 0)
        assertEquals("合成独立家庭", otherSaved.children.first().name)
        assertEquals(original, h.store.load())
        assertEquals(otherSaved, h.store(subject = other, epoch = otherEpoch, blobs = FileLedgerBlobStore(dir)).load())
        assertEquals(2, h.ledgerFiles().size)
        assertNotEquals(ParentingWorkspaceStore.preferenceKey(h.scope, h.subject), ParentingWorkspaceStore.preferenceKey(h.scope, other))
    }

    @Test fun subjectAndEpochMismatchCannotCreateLedger() = test {
        val h = harness()
        val wrongSubject = h.store(subject = stable("parenting-test-wrong-subject"))
        val missingSubject = h.store(subject = null)
        val signedOut = h.store(epoch = AccountEpoch())
        for (store in listOf(wrongSubject, missingSubject, signedOut)) {
            rejects { store.load() }
            rejects { store.apply(ParentingCommand.AddChild(childID, "合成未授权输入"), 0) }
        }
        assertTrue(h.ledgerFiles().isEmpty())
        assertNull(h.keys.existingKey(stable("parenting-test-wrong-subject")), "no key minted for an unauthorised subject")
    }

    // ---- 损坏：保留原数据，停止写入 ----
    private suspend fun assertCorruptBlocksOverwrite(h: LedgerHarness) {
        val bytes = h.diskBytes()
        val cold = h.reopen()
        assertIs<DomainError.Corrupt>(rejects { cold.load() })
        assertIs<DomainError.Corrupt>(rejects { cold.apply(ParentingCommand.AddChild(stable("parenting-test-must-not-overwrite"), "合成覆盖尝试"), 0) })
        assertContentEquals(bytes, h.diskBytes(), "Corrupt data must remain available for recovery")
    }

    @Test fun corruptCiphertextIsNotTreatedAsEmptyAndCannotBeOverwritten() = test {
        val h = harness(); h.seedDraft()
        val file = h.onlyLedgerFile()
        val bytes = file.readBytes(); bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x55).toByte(); file.writeBytes(bytes)
        assertCorruptBlocksOverwrite(h)
    }

    @Test fun truncatedEmptyAndGarbageFilesAreCorruptNotEmpty() = test {
        for (mutate in listOf<(ByteArray) -> ByteArray>({ it.copyOf(it.size - 5) }, { ByteArray(0) }, { "synthetic-damaged-envelope".toByteArray() }, { it.copyOf(10) })) {
            val h = harness(); h.seedDraft()
            h.onlyLedgerFile().writeBytes(mutate(h.diskBytes()))
            assertCorruptBlocksOverwrite(h)
        }
    }

    @Test fun missingKeyMakesTheLedgerUnreadableButKeepsTheFile() = test {
        val h = harness(); h.seedDraft()
        h.keys.delete(h.subject)
        assertCorruptBlocksOverwrite(h)
    }

    private fun rewriteEnvelope(h: LedgerHarness, edit: (String) -> String) {
        val plain = h.cipher.open(h.diskBytes(), h.subject).toString(Charsets.UTF_8)
        val changed = edit(plain)
        assertNotEquals(plain, changed, "the edit must change the envelope")
        h.onlyLedgerFile().writeBytes(h.cipher.seal(changed.toByteArray(Charsets.UTF_8), h.subject))
    }

    @Test fun unsupportedSavedEnvelopeSchemaIsNotTreatedAsEmpty() = test {
        val h = harness(); h.seedDraft()
        rewriteEnvelope(h) { it.replace("\"schemaVersion\":1", "\"schemaVersion\":999") }
        assertCorruptBlocksOverwrite(h)
    }

    @Test fun envelopeForAnotherScopeOrReceiptJunkIsRejected() = test {
        val h = harness(); h.seedDraft()
        rewriteEnvelope(h) { it.replace(h.scope.workspaceID.toString().uppercase(), UUID.randomUUID().toString().uppercase()) }
        assertCorruptBlocksOverwrite(h)
        val g = harness(); g.seedDraft()
        rewriteEnvelope(g) { Regex("\"operations\":\\{\"([^\"]+)\":\"[0-9a-f]{64}\"").replaceFirst(it, "\"operations\":{\"$1\":\"not-a-hash\"") }
        assertCorruptBlocksOverwrite(g)
    }

    @Test fun invalidDecodedWorkspaceReferencesAreRejectedBeforeMutation() = test {
        val h = harness(); h.seedDraft(linkedGoal = true)
        // 把目标的 childID 改成不存在的孩子：解码后 validate() 必须拦住。
        rewriteEnvelope(h) { it.replace("\"childID\":\"${childID.toString().uppercase()}\",\"title\":\"合成阅读目标\"", "\"childID\":\"${stable("missing-owner").toString().uppercase()}\",\"title\":\"合成阅读目标\"") }
        assertCorruptBlocksOverwrite(h)
        val corrupt = ParentingWorkspace(revision = 1, children = listOf(ParentingChild(childID, "合成小星", now)), goals = listOf(ParentingGoal(goalID, stable("missing-owner"), "合成孤立目标", now)))
        assertIs<DomainError>(runCatching { corrupt.validate() }.exceptionOrNull())
        assertIs<DomainError>(runCatching { corrupt.applying(ParentingCommand.RenameChild(childID, "合成不能覆盖"), now) }.exceptionOrNull())
    }

    @Test fun anotherSubjectsCiphertextCannotBeOpenedEvenWithTheSameKey() = test {
        val keys = TestKeys.provider()
        val cipher = AesGcmLedgerCipher(keys)
        val a = stable("a"); val b = stable("b")
        val sealed = cipher.seal("hello".toByteArray(), a)
        assertEquals("hello", cipher.open(sealed, a).toString(Charsets.UTF_8))
        assertFailsLedger { cipher.open(sealed, b) } // b 没有密钥
        // 同一把密钥、不同主体：AAD 不符
        val shared = object : LedgerKeyProvider {
            val key = keys.loadOrCreateKey(a)
            override fun loadOrCreateKey(subjectID: UUID) = key
            override fun existingKey(subjectID: UUID) = key
        }
        val sharedCipher = AesGcmLedgerCipher(shared)
        assertFailsLedger { sharedCipher.open(sealed, b) }
        assertNotEquals(sealed.toList(), cipher.seal("hello".toByteArray(), a).toList(), "fresh IV every time")
        assertEquals(1.toByte(), sealed[0])
    }

    private fun assertFailsLedger(block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull()
        assertTrue(e is LedgerCryptoException, "expected LedgerCryptoException, got $e")
    }

    @Test fun fingerprintsAreStableCanonicalJsonHashes() {
        val expectedJson = "{\"id\":\"C2000000-0000-4000-8000-000000000001\",\"name\":\"合成小星\",\"type\":\"addChild\"}"
        assertEquals(sha256Hex(expectedJson.toByteArray()), ParentingWorkspaceStore.fingerprint(ParentingCommand.AddChild(childID, "合成小星")))
        assertEquals(sha256Hex("{\"type\":\"deleteAllRecords\"}".toByteArray()), ParentingWorkspaceStore.fingerprint(ParentingCommand.DeleteAllRecords))
        assertNotEquals(ParentingWorkspaceStore.fingerprint(ParentingCommand.AddChild(childID, "a")), ParentingWorkspaceStore.fingerprint(ParentingCommand.AddChild(childID, "b")))
        val key = ParentingWorkspaceStore.preferenceKey(LedgerFixtures.scope, LedgerFixtures.subject)
        assertTrue(Regex("parenting_workspace:v1:[0-9a-f]{64}").matches(key))
    }
}

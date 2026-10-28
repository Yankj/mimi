package com.heydusk.mimi.feature.parenting.model

import com.heydusk.mimi.data.ledger.AesGcmLedgerCipher
import com.heydusk.mimi.data.ledger.FileLedgerBlobStore
import com.heydusk.mimi.data.ledger.KeystoreLedgerKeyProvider
import com.heydusk.mimi.data.ledger.LedgerScope
import com.heydusk.mimi.data.ledger.ParentingWorkspaceStore
import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.parenting.ParentingArrangementState
import com.heydusk.mimi.domain.parenting.ParentingCommand
import java.nio.file.Files
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParentingWorkspaceModelTest {
    private val subject = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val scope = LedgerScope(
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        UUID.fromString("11111111-2222-4222-8222-111111111111"),
    )
    private var now = Instant.parse("2026-10-28T08:00:00Z")

    private fun model(): ParentingWorkspaceModel {
        val epoch = AccountEpoch(subject.toString())
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val pw = "t".toCharArray()
        val keys = KeystoreLedgerKeyProvider(
            ks,
            generate = { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() },
            store = { a, k -> ks.setEntry(a, KeyStore.SecretKeyEntry(k), KeyStore.PasswordProtection(pw)) },
            keyPassword = pw,
        )
        val store = ParentingWorkspaceStore(
            FileLedgerBlobStore(Files.createTempDirectory("mimi-model").toFile()),
            scope, subject, epoch, AesGcmLedgerCipher(keys), clock = { now },
        )
        return ParentingWorkspaceModel(store) { now }
    }

    @Test fun loadEmptyThenAddChildPersists() = runBlocking {
        val m = model()
        m.load()
        assertTrue(m.loaded)
        assertTrue(m.workspace.children.isEmpty())
        assertTrue(m.apply(ParentingCommand.AddChild(UUID.randomUUID(), "小明")))
        assertEquals(1, m.workspace.children.size)
        assertEquals("小明", m.workspace.children.single().name)
    }

    @Test fun draftIsNotPlannedUntilConfirm() = runBlocking {
        val m = model()
        m.load()
        val child = UUID.randomUUID()
        val draft = UUID.randomUUID()
        assertTrue(m.apply(ParentingCommand.AddChild(child, "小明")))
        assertTrue(m.apply(ParentingCommand.CreateDraft(draft, child, "读绘本", now.plusSeconds(3600), null, "")))
        assertEquals(ParentingArrangementState.Draft, m.workspace.arrangements.single().state)
        assertTrue(m.apply(ParentingCommand.ConfirmArrangement(draft)))
        assertEquals(ParentingArrangementState.Planned, m.workspace.arrangements.single().state)
    }

    @Test fun completeOneTapOffersUndoInsideWindow() = runBlocking {
        val m = model()
        m.load()
        val child = UUID.randomUUID()
        val arr = UUID.randomUUID()
        m.apply(ParentingCommand.AddChild(child, "小明"))
        m.apply(ParentingCommand.CreateDraft(arr, child, "读绘本", now.plusSeconds(3600), null, ""))
        m.apply(ParentingCommand.ConfirmArrangement(arr))
        val undo = m.completeOneTap(arr)
        assertNotNull(undo)
        assertEquals(ParentingArrangementState.Completed, m.workspace.arrangements.single().state)
        assertTrue(m.undoCompletion(undo))
        assertEquals(ParentingArrangementState.Planned, m.workspace.arrangements.single().state)
    }

    @Test fun undoExpiresAfterWindow() = runBlocking {
        val m = model()
        m.load()
        val child = UUID.randomUUID()
        val arr = UUID.randomUUID()
        m.apply(ParentingCommand.AddChild(child, "小明"))
        m.apply(ParentingCommand.CreateDraft(arr, child, "读绘本", now.plusSeconds(3600), null, ""))
        m.apply(ParentingCommand.ConfirmArrangement(arr))
        val undo = m.completeOneTap(arr)!!
        now = now.plusSeconds(4)
        assertFalse(m.undoCompletion(undo))
        assertNull(m.state.value.undo)
    }
}

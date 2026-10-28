package com.heydusk.mimi

import com.heydusk.mimi.data.account.LocalIdentityStore
import com.heydusk.mimi.data.ledger.AesGcmLedgerCipher
import com.heydusk.mimi.data.ledger.FileLedgerBlobStore
import com.heydusk.mimi.data.ledger.KeystoreLedgerKeyProvider
import com.heydusk.mimi.data.prefs.MimiDataStore
import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.parenting.ParentingArrangementState
import com.heydusk.mimi.feature.parenting.forms.ArrangementDraft
import com.heydusk.mimi.feature.parenting.model.ParentingSession
import java.nio.file.Files
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

/** ViewModel + 会话 + 加密账本 + AccountEpoch 的真实接线（只有 Keystore 换成 JCA 内存库）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParentingShellViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var epoch: AccountEpoch
    private lateinit var blobDir: java.io.File
    private lateinit var session: ParentingSession
    private lateinit var vm: ParentingShellViewModel
    private val opened = mutableListOf<String>()
    private val settings = object : SystemSettingsOpener {
        override fun notifications() { opened += "notifications" }
        override fun exactAlarms() { opened += "exactAlarms" }
        override fun battery() { opened += "battery" }
    }

    @BeforeTest fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dir = Files.createTempDirectory("mimi-shell-vm").toFile()
        blobDir = dir.resolve("ledger")
        epoch = AccountEpoch()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val pw = "t".toCharArray()
        val keys = KeystoreLedgerKeyProvider(
            ks,
            generate = { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() },
            store = { a, k -> ks.setEntry(a, KeyStore.SecretKeyEntry(k), KeyStore.PasswordProtection(pw)) },
            keyPassword = pw,
        )
        session = ParentingSession(
            LocalIdentityStore(MimiDataStore.create(dir.resolve(MimiDataStore.FILE_NAME), scope)),
            epoch, FileLedgerBlobStore(blobDir), AesGcmLedgerCipher(keys), keys, scope,
        )
        vm = ParentingShellViewModel(session, settings)
    }

    @AfterTest fun tearDown() = runBlocking {
        Dispatchers.resetMain()
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    private suspend fun ready(cond: (ParentingShellUi) -> Boolean = { it.loaded }): ParentingShellUi =
        withTimeout(10_000) { vm.state.first { !it.loading && cond(it) } }

    private fun ledgerFiles() = blobDir.listFiles().orEmpty().filter { it.name.endsWith(".sealed") }

    @Test fun startsLoadingThenShowsAnEmptyLedgerForAnAnonymousSubject() = runBlocking {
        val s = ready()
        assertTrue(s.workspace.children.isEmpty())
        assertNotNull(s.subjectID)
        assertEquals(s.subjectID.toString(), epoch.currentAccount)
        assertFalse(s.onboardingDone)
        // 读取不会创建密文（零写入）
        assertTrue(ledgerFiles().isEmpty())
    }

    @Test fun addingAChildWritesTheLedgerAndEmitsTheNewState() = runBlocking {
        ready()
        vm.actions.addChild("Xiaoming")
        val s = ready { it.workspace.children.isNotEmpty() }
        assertEquals("Xiaoming", s.workspace.children.single().name)
        assertEquals(1, ledgerFiles().size)
    }

    @Test fun draftThenConfirmThenOneTapCompleteThenUndo() = runBlocking {
        ready()
        vm.actions.addChild("Xiaoming")
        val child = ready { it.workspace.children.isNotEmpty() }.workspace.children.single().id
        vm.actions.addArrangement(child, ArrangementDraft(title = "Read", dueAt = Instant.now().plusSeconds(3600)))
        val arr = ready { it.workspace.arrangements.isNotEmpty() }.workspace.arrangements.single()
        assertEquals(ParentingArrangementState.Draft, arr.state)
        vm.actions.confirmArrangement(arr.id)
        ready { it.workspace.arrangements.single().state == ParentingArrangementState.Planned }
        vm.actions.completeOneTap(arr.id)
        val done = ready { it.workspace.arrangements.single().state == ParentingArrangementState.Completed }
        assertNotNull(done.undoSeconds)
        vm.actions.undoCompletion()
        ready { it.workspace.arrangements.single().state == ParentingArrangementState.Planned }
        assertNull(vm.state.value.undoSeconds)
    }

    @Test fun privacyShieldFollowsTheSetter() = runBlocking {
        ready()
        vm.setPrivacyShield(true)
        assertTrue(vm.state.value.privacyShield)
        vm.setPrivacyShield(false)
        assertFalse(vm.state.value.privacyShield)
    }

    @Test fun onboardingCompletionIsRemembered() = runBlocking {
        ready()
        vm.completeOnboarding().join()
        assertTrue(ready { it.onboardingDone }.onboardingDone)
    }

    @Test fun signOutWipesTheLedgerAndMovesToAFreshSubject() = runBlocking {
        val first = ready()
        vm.actions.addChild("Xiaoming")
        ready { it.workspace.children.isNotEmpty() }
        assertEquals(1, ledgerFiles().size)
        vm.signOut().join()
        val after = ready { it.subjectID != first.subjectID }
        assertNotEquals(first.subjectID, after.subjectID)
        assertTrue(after.workspace.children.isEmpty())
        assertTrue(ledgerFiles().isEmpty())
        assertEquals(after.subjectID.toString(), epoch.currentAccount)
    }

    @Test fun clearDataKeepsTheSubjectButEmptiesTheLedger() = runBlocking {
        val first = ready()
        vm.actions.addChild("Xiaoming")
        ready { it.workspace.children.isNotEmpty() }
        vm.clearData().join()
        val after = ready { it.workspace.children.isEmpty() }
        assertEquals(first.subjectID, after.subjectID)
        assertTrue(ledgerFiles().isEmpty())
    }

    @Test fun aStaleModelCannotWriteAfterSignOut() = runBlocking {
        ready()
        val old = (session.phase.value as ParentingSession.Phase.Ready).model
        vm.signOut().join()
        ready { it.subjectID != null && it.workspace.children.isEmpty() }
        val before = ledgerFiles().size
        val ok = old.apply(com.heydusk.mimi.domain.parenting.ParentingCommand.AddChild(UUID.randomUUID(), "ghost"))
        assertFalse(ok) // 旧代际的 token 被 AccountEpoch 拒绝
        assertEquals(before, ledgerFiles().size)
    }
}

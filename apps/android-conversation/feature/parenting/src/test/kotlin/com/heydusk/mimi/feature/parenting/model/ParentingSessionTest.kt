package com.heydusk.mimi.feature.parenting.model

import com.heydusk.mimi.data.account.LocalIdentityStore
import com.heydusk.mimi.data.ledger.AesGcmLedgerCipher
import com.heydusk.mimi.data.ledger.FileLedgerBlobStore
import com.heydusk.mimi.data.ledger.KeystoreLedgerKeyProvider
import com.heydusk.mimi.data.prefs.MimiDataStore
import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.parenting.ParentingCommand
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParentingSessionTest {
    private fun harness(): Triple<ParentingSession, LocalIdentityStore, () -> Unit> {
        val dir = Files.createTempDirectory("mimi-session").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = MimiDataStore.create(dir.resolve(MimiDataStore.FILE_NAME), scope)
        val identity = LocalIdentityStore(store)
        val epoch = AccountEpoch()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val pw = "t".toCharArray()
        val keys = KeystoreLedgerKeyProvider(
            ks,
            generate = { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() },
            store = { a, k -> ks.setEntry(a, KeyStore.SecretKeyEntry(k), KeyStore.PasswordProtection(pw)) },
            keyPassword = pw,
        )
        val session = ParentingSession(
            identity, epoch,
            FileLedgerBlobStore(dir.resolve("ledger")),
            AesGcmLedgerCipher(keys), keys, scope,
        )
        return Triple(session, identity) { scope.cancel() }
    }

    @Test fun bootstrapCreatesAnonymousSubjectAndEmptyLedger() = runBlocking {
        val (session, identity, close) = harness()
        try {
            session.bootstrap()
            val ready = assertIs<ParentingSession.Phase.Ready>(session.phase.value)
            assertEquals(ready.subjectID, identity.snapshot.first().subjectID)
            assertTrue(ready.model.loaded)
            assertTrue(ready.model.workspace.children.isEmpty())
        } finally { close() }
    }

    @Test fun signOutWipesLedgerAndStartsFreshSubject() = runBlocking {
        val (session, _, close) = harness()
        try {
            session.bootstrap()
            val first = assertIs<ParentingSession.Phase.Ready>(session.phase.value)
            assertTrue(first.model.apply(ParentingCommand.AddChild(UUID.randomUUID(), "小明")))
            assertEquals(1, first.model.workspace.children.size)
            session.signOutAndStartFresh()
            val second = assertIs<ParentingSession.Phase.Ready>(session.phase.value)
            assertNotEquals(first.subjectID, second.subjectID)
            assertTrue(second.model.workspace.children.isEmpty())
        } finally { close() }
    }

    @Test fun clearCurrentLedgerKeepsSubject() = runBlocking {
        val (session, _, close) = harness()
        try {
            session.bootstrap()
            val ready = assertIs<ParentingSession.Phase.Ready>(session.phase.value)
            val subject = ready.subjectID
            assertTrue(ready.model.apply(ParentingCommand.AddChild(UUID.randomUUID(), "小明")))
            session.clearCurrentLedger()
            val after = assertIs<ParentingSession.Phase.Ready>(session.phase.value)
            assertEquals(subject, after.subjectID)
            assertTrue(after.model.workspace.children.isEmpty())
        } finally { close() }
    }

    @Test fun firstCloudLoginAdoptsLocalTrialLedger() = runBlocking {
        val (session, identity, close) = harness()
        try {
            session.bootstrap()
            val trial = assertIs<ParentingSession.Phase.Ready>(session.phase.value)
            assertTrue(trial.model.apply(ParentingCommand.AddChild(UUID.randomUUID(), "小明")))
            val cloud = UUID.randomUUID()
            session.bindCloudSubject(cloud)
            val bound = assertIs<ParentingSession.Phase.Ready>(session.phase.value)
            assertEquals(cloud, bound.subjectID)
            assertEquals(listOf("小明"), bound.model.workspace.children.map { it.name })
            assertTrue(identity.snapshot.first().cloudBound)
            // 同一账号再绑定一次（恢复会话）什么都不变。
            session.bindCloudSubject(cloud)
            assertEquals(1, assertIs<ParentingSession.Phase.Ready>(session.phase.value).model.workspace.children.size)
        } finally { close() }
    }

    @Test fun switchingToAnotherCloudAccountWipesPreviousLedger() = runBlocking {
        val (session, _, close) = harness()
        try {
            session.bootstrap()
            val a = UUID.randomUUID()
            session.bindCloudSubject(a)
            assertTrue(assertIs<ParentingSession.Phase.Ready>(session.phase.value).model.apply(ParentingCommand.AddChild(UUID.randomUUID(), "甲")))
            session.bindCloudSubject(UUID.randomUUID())
            assertTrue(assertIs<ParentingSession.Phase.Ready>(session.phase.value).model.workspace.children.isEmpty())
            // 切回 A：A 的密文与密钥已删，不会读回旧数据。
            session.bindCloudSubject(a)
            assertTrue(assertIs<ParentingSession.Phase.Ready>(session.phase.value).model.workspace.children.isEmpty())
        } finally { close() }
    }

    @Test fun cloudSignOutStartsFreshLocalTrial() = runBlocking {
        val (session, identity, close) = harness()
        try {
            session.bootstrap()
            val cloud = UUID.randomUUID()
            session.bindCloudSubject(cloud)
            session.signOutAndStartFresh()
            val fresh = assertIs<ParentingSession.Phase.Ready>(session.phase.value)
            assertNotEquals(cloud, fresh.subjectID)
            assertTrue(!identity.snapshot.first().cloudBound)
        } finally { close() }
    }
}

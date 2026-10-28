package com.heydusk.mimi

import com.heydusk.mimi.data.account.LocalIdentityStore
import com.heydusk.mimi.data.ledger.AesGcmLedgerCipher
import com.heydusk.mimi.data.ledger.FileLedgerBlobStore
import com.heydusk.mimi.data.ledger.KeystoreLedgerKeyProvider
import com.heydusk.mimi.data.prefs.MimiDataStore
import com.heydusk.mimi.device.reminders.FakeReminderCenter
import com.heydusk.mimi.device.reminders.InMemoryReminderMemory
import com.heydusk.mimi.device.reminders.InMemoryReminderSettingsStore
import com.heydusk.mimi.device.reminders.NotificationAuthorization
import com.heydusk.mimi.device.reminders.ParentingReminderScheduler
import com.heydusk.mimi.device.reminders.ReminderCopy
import com.heydusk.mimi.device.reminders.ReminderOpen
import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.feature.parenting.forms.ArrangementDraft
import com.heydusk.mimi.feature.parenting.model.ParentingSession
import com.heydusk.mimi.feature.parenting.model.ReminderWiring
import java.nio.file.Files
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.crypto.KeyGenerator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

/** M4：壳 ViewModel 的提醒接线——状态、点通知的账号核对、系统设置入口、退出登录清空。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellRemindersTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val center = FakeReminderCenter(NotificationAuthorization.Authorized)
    private val settingsStore = InMemoryReminderSettingsStore()
    private val opened = mutableListOf<String>()
    private var signedOut = 0
    private lateinit var session: ParentingSession
    private lateinit var vm: ParentingShellViewModel

    private fun build(withReminders: Boolean) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dir = Files.createTempDirectory("mimi-shell-reminders").toFile()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val pw = "t".toCharArray()
        val keys = KeystoreLedgerKeyProvider(
            ks, generate = { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() },
            store = { a, k -> ks.setEntry(a, KeyStore.SecretKeyEntry(k), KeyStore.PasswordProtection(pw)) }, keyPassword = pw,
        )
        val factory = if (withReminders) ParentingSession.ReminderFactory { subject, isCurrent ->
            ReminderWiring(
                ParentingReminderScheduler(center, subject, { MimiCalendar(ZoneOffset.UTC) }, ReminderCopy.PLAIN, InMemoryReminderMemory(), isCurrent),
                settingsStore,
                onSignOut = { signedOut += 1; center.remove(center.requests.map { it.id }); settingsStore.erase() },
            )
        } else null
        session = ParentingSession(
            LocalIdentityStore(MimiDataStore.create(dir.resolve(MimiDataStore.FILE_NAME), scope)),
            AccountEpoch(), FileLedgerBlobStore(dir.resolve("ledger")), AesGcmLedgerCipher(keys), keys, scope, reminders = factory,
        )
        vm = ParentingShellViewModel(session, object : SystemSettingsOpener {
            override fun notifications() { opened += "notifications" }
            override fun exactAlarms() { opened += "exactAlarms" }
            override fun battery() { opened += "battery" }
        })
    }

    @AfterTest fun tearDown() = runBlocking {
        Dispatchers.resetMain()
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    private suspend fun ready(cond: (ParentingShellUi) -> Boolean = { it.loaded }): ParentingShellUi =
        withTimeout(10_000) { vm.state.first { !it.loading && cond(it) } }

    @BeforeTest fun noop() = Unit

    @Test fun shellWithoutAReminderFactoryReportsRemindersUnavailable() = runBlocking {
        build(withReminders = false)
        assertNull(ready().reminders)
    }

    @Test fun shellExposesReminderStateAndQueuesAfterTheParentTurnsThemOn() = runBlocking {
        build(withReminders = true)
        assertNotNull(ready().reminders)
        vm.actions.addChild("Xiaoming")
        val child = ready { it.workspace.children.isNotEmpty() }.workspace.children.single().id
        val due = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().plusDays(2).atTime(15, 0).toInstant(ZoneOffset.UTC)
        vm.actions.addArrangement(child, ArrangementDraft(title = "Swim", dueAt = due))
        val arr = ready { it.workspace.arrangements.isNotEmpty() }.workspace.arrangements.single()
        vm.actions.confirmArrangement(arr.id)
        ready { it.workspace.arrangements.single().state == com.heydusk.mimi.domain.parenting.ParentingArrangementState.Planned }
        assertTrue(center.requests.isEmpty(), "默认关闭：不排任何提醒")
        vm.reminderActions.setEnabled(true)
        val on = ready { it.reminders?.settings?.enabled == true }
        assertEquals(1, on.reminders?.status?.scheduledCount)
        assertEquals(1, center.requests.size)
        vm.actions.cancelArrangement(arr.id)
        ready { it.reminders?.status?.scheduledCount == 0 }
        assertTrue(center.requests.isEmpty())
    }

    @Test fun tappingANotificationOnlyOpensForTheSameAccountAndIsConsumedOnce() = runBlocking {
        build(withReminders = true)
        val subject = ready().subjectID.toString()
        vm.openFromReminder(ReminderOpen("00000000-0000-4000-8000-000000000000", "a1"))
        assertNull(ready().reminderOpen, "别的账号的通知不打开")
        vm.openFromReminder(ReminderOpen(subject.uppercase(), "a2"))
        val open = ready { it.reminderOpen != null }.reminderOpen!!
        assertEquals("a2", open.arrangementID)
        vm.reminderOpenHandled()
        assertNull(ready().reminderOpen)
        vm.openFromReminder(ReminderOpen(subject, "a2"))
        assertTrue(ready { it.reminderOpen != null }.reminderOpen!!.nonce > open.nonce, "同一个安排连点两次都要触发")
    }

    @Test fun systemSettingsEntriesGoThroughTheOpener() = runBlocking {
        build(withReminders = true)
        ready()
        vm.reminderActions.openNotificationSettings()
        vm.reminderActions.openExactAlarmSettings()
        vm.reminderActions.openBatterySettings()
        assertEquals(listOf("notifications", "exactAlarms", "battery"), opened)
    }

    @Test fun signOutClearsTheQueueAndSettings() = runBlocking {
        build(withReminders = true)
        ready()
        vm.reminderActions.setEnabled(true)
        ready { it.reminders?.settings?.enabled == true }
        vm.signOut().join()
        assertEquals(1, signedOut)
        assertTrue(center.requests.isEmpty())
        assertEquals(false, ready().reminders?.settings?.enabled)
    }
}

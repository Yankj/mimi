package com.heydusk.mimi.feature.parenting.model

import com.heydusk.mimi.data.ledger.AesGcmLedgerCipher
import com.heydusk.mimi.data.ledger.FileLedgerBlobStore
import com.heydusk.mimi.data.ledger.KeystoreLedgerKeyProvider
import com.heydusk.mimi.data.ledger.LedgerScope
import com.heydusk.mimi.data.ledger.ParentingWorkspaceStore
import com.heydusk.mimi.device.reminders.FakeReminderCenter
import com.heydusk.mimi.device.reminders.InMemoryReminderMemory
import com.heydusk.mimi.device.reminders.InMemoryReminderSettingsStore
import com.heydusk.mimi.device.reminders.NotificationAuthorization
import com.heydusk.mimi.device.reminders.ParentingReminderScheduler
import com.heydusk.mimi.device.reminders.ReminderCopy
import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.domain.parenting.ParentingReminderLead
import com.heydusk.mimi.domain.parenting.ParentingReminderSettings
import java.nio.file.Files
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 对应 iOS `ParentingReminderModelTests`：真实模型 + 真实加密账本 + 真实调度器，只有系统通知中心是假的。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderModelTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val subject = UUID.fromString("D2000000-0000-4000-8000-000000000001")
    private val childID = UUID.fromString("C2000000-0000-4000-8000-000000000001")
    private val itemID = UUID.fromString("C3000000-0000-4000-8000-000000000001")
    private val scope = LedgerScope(UUID.fromString("11111111-1111-4111-8111-111111111111"), UUID.fromString("11111111-2222-4222-8222-111111111111"))
    private val now: Instant = ZonedDateTime.of(2026, 10, 28, 10, 0, 0, 0, zone).toInstant()
    private val due: Instant = ZonedDateTime.of(2026, 10, 29, 15, 0, 0, 0, zone).toInstant()
    private val settings = InMemoryReminderSettingsStore()
    private var signedOut = 0

    private fun make(center: FakeReminderCenter, current: () -> Boolean = { true }): ParentingWorkspaceModel {
        val epoch = AccountEpoch(subject.toString())
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val pw = "t".toCharArray()
        val keys = KeystoreLedgerKeyProvider(
            ks, generate = { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() },
            store = { a, k -> ks.setEntry(a, KeyStore.SecretKeyEntry(k), KeyStore.PasswordProtection(pw)) }, keyPassword = pw,
        )
        val store = ParentingWorkspaceStore(FileLedgerBlobStore(Files.createTempDirectory("mimi-reminder-model").toFile()), scope, subject, epoch, AesGcmLedgerCipher(keys), clock = { now })
        val wiring = ReminderWiring(
            ParentingReminderScheduler(center, subject, { MimiCalendar(zone) }, ReminderCopy.PLAIN, InMemoryReminderMemory(), current),
            settings, onSignOut = { signedOut += 1 },
        )
        return ParentingWorkspaceModel(store, { now }, wiring)
    }

    @Test fun ledgerChangesDriveTheReminderQueueAndEraseCancelsThem() = runBlocking {
        val center = FakeReminderCenter(NotificationAuthorization.NotDetermined)
        val model = make(center)
        model.load()
        assertTrue(model.loaded)
        assertEquals(NotificationAuthorization.NotDetermined, model.state.value.reminderStatus?.authorization)
        assertTrue(model.apply(ParentingCommand.AddChild(childID, "合成小星")))
        assertTrue(model.apply(ParentingCommand.CreateDraft(itemID, childID, "合成绘本时间", due, null, "合成原话")))
        assertTrue(center.requests.isEmpty(), "Reminders are off until the parent turns them on")
        model.setReminders(true)
        assertEquals(1, center.authorizationRequests)
        assertTrue(model.state.value.reminderSettings.enabled)
        assertTrue(center.requests.isEmpty(), "A draft is not confirmed, so it has no reminder")
        assertTrue(model.apply(ParentingCommand.ConfirmArrangement(itemID)))
        assertEquals(1, center.requests.size)
        assertEquals(1, model.state.value.reminderStatus?.scheduledCount)
        // 改期：同一条请求换时间。
        val later = ZonedDateTime.of(2026, 10, 29, 17, 0, 0, 0, zone).toInstant()
        val current = model.workspace.arrangements.single { it.id == itemID }
        assertTrue(model.apply(ParentingCommand.ReviseArrangement(
            UUID.randomUUID(), com.heydusk.mimi.domain.parenting.ParentingArrangementEditTarget(current, model.workspace.revision),
            "合成绘本时间", later, null, "", emptyList(),
        )))
        assertEquals(1, center.requests.size)
        assertEquals(ZonedDateTime.of(2026, 10, 29, 16, 0, 0, 0, zone).toInstant(), center.requests.single().fireAt)
        // 完成与取消都撤掉提醒。
        assertTrue(model.apply(ParentingCommand.CancelArrangement(itemID)))
        assertTrue(center.requests.isEmpty())
        assertTrue(model.apply(ParentingCommand.CreateDraft(UUID.randomUUID(), childID, "合成第二件", due, null, "")))
        assertTrue(model.apply(ParentingCommand.DeleteAllRecords))
        assertTrue(model.workspace.children.isEmpty())
        assertTrue(center.requests.isEmpty())
    }

    @Test fun completingOrDeletingARemindedArrangementCancelsItsAlarm() = runBlocking {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        val model = make(center)
        model.load()
        model.setReminders(true)
        model.apply(ParentingCommand.AddChild(childID, "合成小星"))
        val second = UUID.randomUUID()
        for (id in listOf(itemID, second)) {
            model.apply(ParentingCommand.CreateDraft(id, childID, "合成$id", due.plusSeconds(if (id == itemID) 0 else 86_400), null, ""))
            model.apply(ParentingCommand.ConfirmArrangement(id))
        }
        assertEquals(2, center.requests.size)
        assertTrue(model.apply(ParentingCommand.CompleteArrangement(itemID, UUID.randomUUID(), "")))
        assertEquals(1, center.requests.size, "完成后撤销提醒")
        assertTrue(model.apply(ParentingCommand.DeleteArrangement(second)))
        assertTrue(center.requests.isEmpty(), "删除后撤销提醒")
    }

    @Test fun deniedPermissionIsReportedAndDoesNotBlockSavingTheLedger() = runBlocking {
        val center = FakeReminderCenter(NotificationAuthorization.NotDetermined, grantOnRequest = false)
        val model = make(center)
        model.load()
        model.setReminders(true)
        assertEquals(NotificationAuthorization.Denied, model.state.value.reminderStatus?.authorization)
        assertTrue(model.apply(ParentingCommand.AddChild(childID, "合成小星")), "A saved change never fails because reminders are unavailable")
        assertNull(model.state.value.error)
        assertEquals(0, model.state.value.reminderStatus?.scheduledCount)
        assertEquals(0, center.addCount)
    }

    @Test fun aSystemFailureNeverTurnsASavedChangeIntoAFailure() = runBlocking {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized).apply { failAdds = true }
        val model = make(center)
        model.load()
        model.setReminders(true)
        model.apply(ParentingCommand.AddChild(childID, "合成小星"))
        model.apply(ParentingCommand.CreateDraft(itemID, childID, "合成", due, null, ""))
        assertTrue(model.apply(ParentingCommand.ConfirmArrangement(itemID)))
        assertNull(model.state.value.error)
        assertTrue(model.state.value.reminderStatus?.systemRefused == true)
        assertEquals(0, model.state.value.reminderStatus?.scheduledCount)
    }

    @Test fun leadAndHideContentAreSavedAndRescheduleWithNewText() = runBlocking {
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        val model = make(center)
        model.load()
        model.setReminders(true)
        model.apply(ParentingCommand.AddChild(childID, "合成小星"))
        model.apply(ParentingCommand.CreateDraft(itemID, childID, "合成私密", due, null, ""))
        model.apply(ParentingCommand.ConfirmArrangement(itemID))
        assertEquals(ZonedDateTime.of(2026, 10, 29, 14, 0, 0, 0, zone).toInstant(), center.requests.single().fireAt)
        model.setReminderLead(ParentingReminderLead.Minutes30)
        assertEquals(ZonedDateTime.of(2026, 10, 29, 14, 30, 0, 0, zone).toInstant(), center.requests.single().fireAt)
        model.setReminderContentHidden(true)
        assertFalse(center.requests.single().body.contains("合成私密"))
        assertEquals(ParentingReminderSettings(true, ParentingReminderLead.Minutes30, true), settings.load(), "设置落到账本旁边的存储")
    }

    @Test fun refreshPicksUpAPermissionGrantedLaterInSystemSettings() = runBlocking {
        val center = FakeReminderCenter(NotificationAuthorization.Denied)
        val model = make(center)
        model.load()
        model.apply(ParentingCommand.AddChild(childID, "合成小星"))
        model.apply(ParentingCommand.CreateDraft(itemID, childID, "合成", due, null, ""))
        model.apply(ParentingCommand.ConfirmArrangement(itemID))
        model.setReminders(true)
        assertTrue(center.requests.isEmpty())
        center.status = NotificationAuthorization.Authorized // 家长去系统设置里打开了通知
        model.refreshReminders()
        assertEquals(1, center.requests.size)
        assertEquals(1, model.state.value.reminderStatus?.scheduledCount)
    }

    @Test fun anAccountThatIsNoLongerCurrentLeavesNothingQueued() = runBlocking {
        var current = true
        val center = FakeReminderCenter(NotificationAuthorization.Authorized)
        val model = make(center) { current }
        model.load()
        model.setReminders(true)
        model.apply(ParentingCommand.AddChild(childID, "合成小星"))
        model.apply(ParentingCommand.CreateDraft(itemID, childID, "合成", due, null, ""))
        model.apply(ParentingCommand.ConfirmArrangement(itemID))
        assertEquals(1, center.requests.size)
        val adds = center.addCount
        current = false
        model.refreshReminders()
        assertEquals(adds, center.addCount, "账号失效后调度器不再写")
        model.reminders!!.scheduler.clear()
        assertTrue(center.requests.isEmpty())
    }
}

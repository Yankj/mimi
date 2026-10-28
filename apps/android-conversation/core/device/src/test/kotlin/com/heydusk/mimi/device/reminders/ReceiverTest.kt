package com.heydusk.mimi.device.reminders

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import com.heydusk.mimi.domain.parenting.ParentingReminderPayload
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReceiverTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val subject = UUID.fromString("D1000000-0000-4000-8000-000000000001")
    private val arrangement = UUID.fromString("A0000001-0000-4000-8000-000000000001")
    private val id = ParentingReminderPayload.identifier(subject, arrangement)
    private val prefs = ReminderPrefs(context)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    @AfterTest fun reset() { ReminderHooks.resync = null }

    private fun fire(subjectKey: String = subject.toString().lowercase(), marker: String? = "1"): Intent =
        Intent(ReminderReceiver.ACTION_FIRE).setClass(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.EXTRA_ID, id)
            .putExtra(ReminderReceiver.EXTRA_TITLE, "小咪提醒")
            .putExtra(ReminderReceiver.EXTRA_BODY, "团团：游泳\n10月29日 15:00 开始 · 按你的设置，提前 1 小时提醒")
            .apply { if (marker != null) putExtra(ParentingReminderPayload.MARKER, marker) }
            .putExtra(ParentingReminderPayload.SUBJECT_KEY, subjectKey)
            .putExtra(ParentingReminderPayload.ARRANGEMENT_KEY, arrangement.toString().lowercase())

    // ── 到点接收器 ────────────────────────────────────────────

    @Test fun firingPostsOneNotificationOnTheReminderChannelAndMarksItSent() {
        prefs.activeSubject = subject.toString()
        prefs.register(id, java.time.Instant.now().plusSeconds(60), "sig")
        ReminderReceiver().onReceive(context, fire())
        val posted = shadowOf(notifications).allNotifications
        assertEquals(1, posted.size)
        val n = posted.single()
        assertEquals(ReminderChannels.ID, n.channelId)
        assertEquals("小咪提醒", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertTrue(n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().startsWith("团团：游泳"))
        assertEquals(android.app.Notification.CATEGORY_REMINDER, n.category)
        assertTrue(prefs.registered().isEmpty(), "已响的从登记表里划掉")
        assertNotNull(notifications.getNotificationChannel(ReminderChannels.ID), "渠道在需要时创建")
    }

    @Test fun tappingTheNotificationOpensTheAppWithTheDeepLinkAndAccountAndArrangement() {
        prefs.activeSubject = subject.toString()
        ReminderReceiver().onReceive(context, fire())
        val n = shadowOf(notifications).allNotifications.single()
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals("mimi://parenting/arrangement/${arrangement.toString().lowercase()}", intent.dataString)
        val open = ReminderOpen.parse(intent)
        assertEquals(ReminderOpen(subject.toString().lowercase(), arrangement.toString().lowercase()), open)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0 && intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test fun aReminderOfAnotherOrSignedOutAccountIsDroppedSilently() {
        prefs.activeSubject = UUID.randomUUID().toString()
        ReminderReceiver().onReceive(context, fire())
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
        prefs.activeSubject = null // 退出登录之后迟到的闹钟
        ReminderReceiver().onReceive(context, fire())
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test fun malformedOrForeignBroadcastsAreIgnored() {
        prefs.activeSubject = subject.toString()
        ReminderReceiver().onReceive(context, fire(marker = null))
        ReminderReceiver().onReceive(context, Intent("some.other.action").setClass(context, ReminderReceiver::class.java))
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test fun nothingIsPostedWhenTheSystemHasNotificationsOff() {
        prefs.activeSubject = subject.toString()
        shadowOf(notifications).setNotificationsEnabled(false)
        ReminderReceiver().onReceive(context, fire())
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test fun reminderOpenIgnoresOrdinaryLaunches() {
        assertNull(ReminderOpen.parse(null))
        assertNull(ReminderOpen.parse(Intent(Intent.ACTION_MAIN)))
        assertNull(ReminderOpen.parse(Intent(Intent.ACTION_MAIN).putExtra(ParentingReminderPayload.MARKER, "0")))
    }

    // ── 系统广播接收器 ─────────────────────────────────────────

    @Test fun everyTriggerThatCanDisturbAlarmsMapsToAResyncReason() {
        assertEquals("boot", ReminderSystemReceiver.reasonFor(Intent.ACTION_BOOT_COMPLETED))
        assertEquals("package-replaced", ReminderSystemReceiver.reasonFor(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals("timezone", ReminderSystemReceiver.reasonFor(Intent.ACTION_TIMEZONE_CHANGED))
        assertEquals("time", ReminderSystemReceiver.reasonFor(Intent.ACTION_TIME_CHANGED))
        assertEquals("exact-alarm-permission", ReminderSystemReceiver.reasonFor(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))
        assertNull(ReminderSystemReceiver.reasonFor(Intent.ACTION_SCREEN_ON))
        assertNull(ReminderSystemReceiver.reasonFor(null))
    }

    private fun deliver(action: String): String? {
        val reason = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        ReminderHooks.resync = { r -> reason.set(r); latch.countDown() }
        ReminderSystemReceiver().onReceive(context, Intent(action))
        latch.await(2, TimeUnit.SECONDS)
        return reason.get()
    }

    @Test fun bootTimezoneTimeAndPackageReplaceWakeTheResyncHook() {
        for ((action, expected) in listOf(
            Intent.ACTION_BOOT_COMPLETED to "boot",
            Intent.ACTION_TIMEZONE_CHANGED to "timezone",
            Intent.ACTION_TIME_CHANGED to "time",
            Intent.ACTION_MY_PACKAGE_REPLACED to "package-replaced",
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED to "exact-alarm-permission",
        )) assertEquals(expected, deliver(action), action)
    }

    @Test fun unrelatedBroadcastsDoNotResync() {
        assertNull(deliver(Intent.ACTION_SCREEN_ON))
    }

    @Test fun withoutAHookTheReceiverIsHarmless() {
        ReminderHooks.resync = null
        ReminderSystemReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test fun theManifestRegistersTheReceiversForEverySystemTrigger() {
        val pm = context.packageManager
        for (action in listOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )) {
            val found = pm.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            assertTrue(found.any { it.activityInfo.name == ReminderSystemReceiver::class.java.name }, "未注册: $action")
        }
        val fire = pm.queryBroadcastReceivers(Intent(ReminderReceiver.ACTION_FIRE).setClass(context, ReminderReceiver::class.java), 0)
        assertTrue(fire.isNotEmpty())
        assertEquals(false, fire.first().activityInfo.exported, "到点接收器不对外导出")
    }
}

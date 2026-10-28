package com.heydusk.mimi.device.reminders

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import org.robolectric.RuntimeEnvironment
import com.heydusk.mimi.domain.parenting.ParentingReminderPayload
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** AlarmManager / NotificationManager 上的真实适配器（Robolectric 的系统影子）。不证明真机会准点响——只证明我们提交给系统的内容与读回。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmNotificationCenterTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private var now: Instant = Instant.parse("2026-10-28T02:00:00Z")
    private val bridge = NotificationPermissionBridge()
    private val prefs = ReminderPrefs(context)
    private val center = AlarmNotificationCenter(context, prefs, bridge) { now }
    private val subject = UUID.fromString("D1000000-0000-4000-8000-000000000001")
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    @org.junit.Before fun grantExactAlarms() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    private fun request(n: Int, fireAt: Instant = now.plusSeconds(3600L * n)): NotificationRequest {
        val arrangement = UUID.fromString("A000000$n-0000-4000-8000-000000000001")
        return NotificationRequest(
            ParentingReminderPayload.identifier(subject, arrangement), "小咪提醒", "团团：游泳", fireAt,
            mapOf(
                ParentingReminderPayload.MARKER to "1",
                ParentingReminderPayload.SUBJECT_KEY to subject.toString(),
                ParentingReminderPayload.ARRANGEMENT_KEY to arrangement.toString(),
                ParentingReminderPayload.SIGNATURE_KEY to "sig$n",
            ),
        )
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    @Test fun addSchedulesAnExactIdleAlarmAtTheRequestedTimeAndReadsBack() = run {
        val r = request(1)
        center.add(r)
        val scheduled = shadowOf(alarms).scheduledAlarms
        assertEquals(1, scheduled.size)
        assertEquals(r.fireAt.toEpochMilli(), scheduled.single().triggerAtMs)
        assertTrue(scheduled.single().isAllowWhileIdle)
        assertEquals(AlarmPrecision.Exact, center.lastPrecision)
        assertEquals(AlarmManager.RTC_WAKEUP, scheduled.single().type)
        assertTrue(center.exactAlarms())
        val back = center.pending().single()
        assertEquals(r.id, back.id); assertEquals("sig1", back.signature); assertEquals(r.fireAt, back.fireAt)
    }

    @Test fun withoutExactAlarmPermissionItFallsBackToAnInexactIdleAlarmAndSaysSo() = run {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        try {
            assertFalse(center.exactAlarms())
            center.add(request(1))
            val alarm = shadowOf(alarms).scheduledAlarms.single()
            assertTrue(alarm.isAllowWhileIdle)
            assertEquals(AlarmPrecision.Inexact, center.lastPrecision)
            assertEquals(1, center.pending().size, "仍然排上了，只是可能晚几分钟")
        } finally {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
        }
    }

    @Test fun addingTheSameIdentifierReplacesInsteadOfDuplicating() = run {
        center.add(request(1, now.plusSeconds(3600)))
        center.add(request(1, now.plusSeconds(7200)))
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        assertEquals(now.plusSeconds(7200), center.pending().single().fireAt)
    }

    @Test fun removeCancelsTheAlarmAndTheReadBack() = run {
        val a = request(1); val b = request(2)
        center.add(a); center.add(b)
        center.remove(listOf(a.id))
        assertEquals(listOf(b.id), center.pending().map { it.id })
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
    }

    @Test fun elapsedRemindersAndForeignIdentifiersAreNotListedOrTouched() = run {
        center.add(request(1, now.plusSeconds(60)))
        now = now.plusSeconds(120)
        assertTrue(center.pending().isEmpty(), "时间已过：不再算待发")
        assertFailsWith<IllegalArgumentException> { center.add(request(2).copy(id = "someone.else.1")) }
        center.remove(listOf("someone.else.1")) // 不是我们的前缀：忽略，不抛
    }

    @Test fun anAlarmThatTheSystemLostIsNotReadBackAfterARestart() = run {
        center.add(request(1))
        // 模拟重启：系统清空闹钟与 PendingIntent，登记表（SharedPreferences）还在。
        shadowOf(alarms).scheduledAlarms.forEach { alarms.cancel(it.operation!!); it.operation!!.cancel() }
        assertTrue(center.pending().isEmpty(), "读回要求系统里真的还有，登记表不算数")
        assertTrue(prefs.registered().isEmpty(), "对不上的登记项会被清理")
    }

    @Test fun removeAllClearsEveryReminderAndTheirDeliveredNotifications() = run {
        center.add(request(1)); center.add(request(2))
        notifications.notify(request(1).id, ReminderReceiver.notificationId(request(1).id), android.app.Notification.Builder(context, ReminderChannels.ID).setSmallIcon(android.R.drawable.ic_dialog_info).build())
        center.removeAll()
        assertTrue(center.pending().isEmpty())
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test fun authorizationFollowsSystemStateAndTheAskedMarker() = run {
        shadowOf(notifications).setNotificationsEnabled(true)
        assertEquals(NotificationAuthorization.Authorized, center.authorization())
        shadowOf(notifications).setNotificationsEnabled(false)
        assertEquals(NotificationAuthorization.NotDetermined, center.authorization(), "从来没问过")
        prefs.permissionAsked = true
        assertEquals(NotificationAuthorization.Denied, center.authorization(), "问过了且仍关着")
    }

    @Test fun aBlockedChannelCountsAsDenied() = run {
        shadowOf(notifications).setNotificationsEnabled(true)
        ReminderChannels.ensure(context)
        notifications.getNotificationChannel(ReminderChannels.ID).also { assertNotNull(it) }
        val blocked = android.app.NotificationChannel(ReminderChannels.ID, "x", NotificationManager.IMPORTANCE_NONE)
        notifications.deleteNotificationChannel(ReminderChannels.ID)
        notifications.createNotificationChannel(blocked)
        assertEquals(NotificationAuthorization.Denied, center.authorization())
    }

    @Test fun requestWithoutAnyActivityDoesNotPromptAndDoesNotMarkAsked() = run {
        shadowOf(notifications).setNotificationsEnabled(false)
        assertFalse(center.requestAuthorization())
        assertFalse(prefs.permissionAsked, "没有界面可弹：不能把它当成「被拒绝」")
        assertEquals(NotificationAuthorization.NotDetermined, center.authorization())
    }

    @Test fun requestThroughTheBridgeMarksAskedAndReportsTheAnswer() {
        shadowOf(notifications).setNotificationsEnabled(false)
        bridge.attach {
            shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            shadowOf(notifications).setNotificationsEnabled(true)
            bridge.onResult(true)
        }
        val granted = runBlocking {
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).let { scope ->
                scope.async { center.requestAuthorization() }
            }
            val deadline = System.currentTimeMillis() + 5000
            while (!job.isCompleted && System.currentTimeMillis() < deadline) { shadowOf(android.os.Looper.getMainLooper()).idle(); kotlinx.coroutines.delay(20) }
            job.await()
        }
        assertTrue(granted)
        assertTrue(prefs.permissionAsked)
        assertEquals(NotificationAuthorization.Authorized, runBlocking { center.authorization() })
    }

    @Test fun denyingThroughTheBridgeLeavesDeniedAndAsked() {
        shadowOf(notifications).setNotificationsEnabled(false)
        bridge.attach { bridge.onResult(false) }
        val granted = runBlocking {
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { center.requestAuthorization() }
            val deadline = System.currentTimeMillis() + 5000
            while (!job.isCompleted && System.currentTimeMillis() < deadline) { shadowOf(android.os.Looper.getMainLooper()).idle(); kotlinx.coroutines.delay(20) }
            job.await()
        }
        assertFalse(granted)
        assertEquals(NotificationAuthorization.Denied, runBlocking { center.authorization() })
    }

    @Test @Config(sdk = [31]) fun beforeAndroid13ThereIsNothingToAskAndTheSystemSwitchDecides() = run {
        shadowOf(notifications).setNotificationsEnabled(false)
        assertEquals(NotificationAuthorization.Denied, center.authorization(), "13 以下没有运行时权限：关着就是被关了，只能引导去设置")
        assertFalse(center.requestAuthorization())
        shadowOf(notifications).setNotificationsEnabled(true)
        assertEquals(NotificationAuthorization.Authorized, center.authorization())
    }
}

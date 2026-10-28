package com.heydusk.mimi.device.reminders

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heydusk.mimi.domain.parenting.ParentingReminderPayload
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * 真机检查：这台手机上的通知与闹钟能力。**不弹权限框、不发通知**——只读通知授权与精确闹钟状态，
 * 并在一小时后排一个本应用的提醒、读回、再取消（读回 = 登记表 + 系统里确有 PendingIntent）。
 * 结果打到 logcat（tag `MimiReminders`）；授权/精确闹钟状态是诊断，不是门槛。
 */
@RunWith(AndroidJUnit4::class)
class ReminderDeviceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun reportsAuthorizationAndExactAlarmState() = runBlocking {
        val center = AlarmNotificationCenter(context)
        val authorization = center.authorization()
        android.util.Log.i("MimiReminders", "authorization=$authorization exactAlarms=${center.exactAlarms()}")
        assertNotNull(authorization)
    }

    @Test fun schedulesReadsBackAndCancelsOneRealAlarm() = runBlocking {
        val center = AlarmNotificationCenter(context)
        val id = ParentingReminderPayload.IDENTIFIER_PREFIX + UUID.randomUUID()
        val request = NotificationRequest(id, "Synthetic", "Synthetic body", Instant.now().plusSeconds(3600), mapOf(ParentingReminderPayload.SIGNATURE_KEY to "sig"))
        try {
            center.add(request)
            val pending = center.pending().single { it.id == id }
            assertEquals("sig", pending.signature)
            assertTrue(pending.fireAt!!.isAfter(Instant.now()))
            android.util.Log.i("MimiReminders", "precision=${center.lastPrecision}")
        } finally {
            center.remove(listOf(id))
        }
        assertTrue(center.pending().none { it.id == id })
    }
}

package com.heydusk.mimi.device.reminders

import android.app.Application
import com.heydusk.mimi.domain.parenting.ParentingReminderLead
import com.heydusk.mimi.domain.parenting.ParentingReminderSettings
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test fun settingsPersistPerAccountAndStartOff() {
        val a = UUID.randomUUID()
        val store = SharedPrefsReminderSettingsStore(context, a)
        assertEquals(ParentingReminderSettings.DEFAULT, store.load())
        assertFalse(store.load().enabled)
        store.save(ParentingReminderSettings(true, ParentingReminderLead.EveningBefore, true))
        assertEquals(ParentingReminderSettings(true, ParentingReminderLead.EveningBefore, true), SharedPrefsReminderSettingsStore(context, a).load())
        assertEquals(ParentingReminderSettings.DEFAULT, SharedPrefsReminderSettingsStore(context, UUID.randomUUID()).load())
        store.erase()
        assertEquals(ParentingReminderSettings.DEFAULT, store.load())
    }

    @Test fun memoryRoundTripsPerAccountAndForgets() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val item = UUID.randomUUID()
        val memory = SharedPrefsReminderMemory(context, a)
        val at = Instant.parse("2026-10-29T06:00:00Z")
        memory.save(mapOf(item to at))
        assertEquals(mapOf(item to at), SharedPrefsReminderMemory(context, a).load())
        assertTrue(SharedPrefsReminderMemory(context, b).load().isEmpty())
        memory.save(emptyMap())
        assertTrue(memory.load().isEmpty())
    }

    @Test fun registryHoldsNoContentOnlyIdsTimesAndSignatures() {
        val prefs = ReminderPrefs(context)
        prefs.register("mimi.parenting.v1.a.b", Instant.parse("2026-10-29T06:00:00Z"), "sig")
        val raw = context.getSharedPreferences(ReminderPrefs.FILE, 0).all.values.joinToString()
        assertFalse(raw.contains("团团") || raw.contains("游泳"), "登记表是明文：不能出现孩子名字或标题")
        assertEquals(mapOf("mimi.parenting.v1.a.b" to (Instant.parse("2026-10-29T06:00:00Z") to "sig")), prefs.registered())
    }

    @Test fun activeSubjectComparisonIgnoresCase() {
        val prefs = ReminderPrefs(context)
        val s = UUID.randomUUID()
        prefs.activeSubject = s.toString().uppercase()
        assertTrue(prefs.isActive(s.toString().lowercase()))
        prefs.activeSubject = null
        assertFalse(prefs.isActive(s.toString()))
    }

    @Test fun channelIsCreatedWithAHighImportanceAndLocalizedName() {
        ReminderChannels.ensure(context)
        val channel = context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(ReminderChannels.ID)
        assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertTrue(channel.name.isNotBlank())
        ReminderChannels.ensure(context) // 幂等
    }
}

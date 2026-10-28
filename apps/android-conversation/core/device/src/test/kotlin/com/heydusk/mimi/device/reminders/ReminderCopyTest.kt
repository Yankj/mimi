package com.heydusk.mimi.device.reminders

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderCopyTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val at: Instant = Instant.parse("2026-10-29T07:00:00Z")

    @Test fun chineseTextComesFromTheSharedCatalogAndFillsPlaceholders() {
        val copy = ReminderCopy.forContext(context, { ZoneId.of("Asia/Shanghai") }, { Locale.SIMPLIFIED_CHINESE })
        assertEquals("小咪提醒", copy.ui("小咪提醒", emptyList()))
        assertEquals("团团：游泳课", copy.ui("{0}：{1}", listOf("团团", "游泳课")))
        assertTrue(copy.formatTime(at).contains("15:00"), copy.formatTime(at))
    }

    @Test fun englishTextAndTimeFollowTheSystemLanguageAndZone() {
        val copy = ReminderCopy.forContext(context, { ZoneId.of("America/Los_Angeles") }, { Locale.US })
        assertTrue(copy.ui("小咪提醒", emptyList()).any { it.code < 128 }, "English catalog entry expected: " + copy.ui("小咪提醒", emptyList()))
        assertTrue(copy.formatTime(at).contains("Oct"), copy.formatTime(at))
    }
}

package com.heydusk.mimi.domain.parenting

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 对应 iOS `ParentingRescheduleIntakeTests` 里直接断言"改期时间校验器"的用例
 * （绝对 ISO、相对日期、本地 ISO、歧义/无效/不支持、夏令时空档/重复/半小时偏移/跳过的一天）。
 * 会话控制器、重试与恢复文件（Controller*）以及 `ParentingIntakeDraft.parse` 的契约检查属 M5。
 */
class RescheduleTimeTest {
    private fun instant(s: String): Instant = Instant.parse(s)
    private val now = instant("2026-10-23T16:30:00Z")
    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun verify(text: String, proposed: String, evidence: String = text, zone: ZoneId = shanghai, at: Instant = now) =
        RescheduleTime.verify(instant(proposed), text, evidence, at, zone)

    @Test fun absoluteISOUsesItsExplicitOffsetAcrossRequestZones() {
        for (zone in listOf("America/New_York", "Pacific/Auckland", "Asia/Shanghai").map(ZoneId::of)) {
            assertEquals(instant("2027-02-18T17:00:00Z"), verify("Change to 2027-02-18T17:00:00Z.", "2027-02-18T17:00:00Z", "2027-02-18T17:00:00Z", zone).dueAt)
            assertEquals(instant("2027-02-18T09:00:00Z"), verify("改到2027-02-18T17:00:00+08:00", "2027-02-18T09:00:00Z", "2027-02-18T17:00:00+08:00", zone).dueAt)
        }
    }

    @Test fun relativeDateUsesFrozenLocalDayAndExplicitDayPeriod() {
        for (text in listOf("改到明天下午五点", "改到明天17:00", "改到明天下午五点半")) {
            val expected = if (text.endsWith("半")) "2026-10-25T09:30:00Z" else "2026-10-25T09:00:00Z"
            val evidence = text.removePrefix("改到")
            assertEquals(instant(expected), verify(text, expected, evidence).dueAt, text)
        }
        assertEquals(instant("2027-01-02T09:00:00Z"), verify("改到后天17:00", "2027-01-02T09:00:00Z", "后天17:00", at = instant("2026-12-30T16:30:00Z")).dueAt)
    }

    @Test fun localISOUsesFrozenZoneAndPreservesValidLeapDayAndClockBoundaries() {
        for ((text, expected) in listOf(
            "2028-02-29 17:00" to "2028-02-29T09:00:00Z", "2027-02-18 00:00" to "2027-02-17T16:00:00Z",
            "2027-02-18 23:59" to "2027-02-18T15:59:00Z", "2027-02-18 17:00:30" to "2027-02-18T09:00:30Z",
        )) {
            val v = verify(text, expected)
            assertEquals(instant(expected), v.dueAt, text); assertNull(v.clarification, text)
        }
    }

    @Test fun ambiguousInvalidUnsupportedAndMismatchedTimesStayClarifications() {
        val cases = listOf(
            "明天五点" to "2026-10-25T09:00:00Z", "明天上午十二点" to "2026-10-25T04:00:00Z", "明天晚上十二点" to "2026-10-25T16:00:00Z",
            "2027-02-30 17:00" to "2027-03-02T09:00:00Z", "2027-02-18 24:00" to "2027-02-18T16:00:00Z",
            "明天17:00或后天17:00" to "2026-10-25T09:00:00Z", "明天17:00到18:00" to "2026-10-25T09:00:00Z",
            "大后天17:00" to "2026-10-27T09:00:00Z", "下周五17:00" to "2026-10-30T09:00:00Z",
            "明天17:00北京时间" to "2026-10-25T09:00:00Z", "明天17:00以后" to "2026-10-25T09:00:00Z", "不是明天17:00" to "2026-10-25T09:00:00Z",
            "明天下午五点" to "2026-10-24T09:00:00Z", "明天17:00" to "2026-10-25T17:00:00Z", "明天17:00" to "2026-10-25T09:00:30Z",
            "2027-02-18 17:00:30:00" to "2027-02-17T16:30:00Z", "2027-02-18 17: 12:30" to "2027-02-18T04:30:00Z",
            "2027-02-18 17:00 12：" to "2027-02-18T09:00:00Z", "2027-02-18 17:00 ：12" to "2027-02-18T09:00:00Z",
            "2027-02-18 17:00Z" to "2027-02-18T09:00:00Z", "2027-02-18 17:00 z" to "2027-02-18T09:00:00Z",
            "明天17:00AM" to "2026-10-25T09:00:00Z", "明天17:00am" to "2026-10-25T09:00:00Z", "明天17:00pM" to "2026-10-25T09:00:00Z",
        )
        for ((text, proposed) in cases) {
            val v = verify(text, proposed)
            assertNull(v.dueAt, text); assertNotNull(v.clarification, text)
        }
    }

    @Test fun calendarRejectsGapFoldHalfHourTransitionsAndSkippedDay() {
        val cases = listOf(
            Triple("America/New_York", "2026-03-08 02:30", "2026-03-08T07:30:00Z"),
            Triple("America/New_York", "2026-11-01 01:30", "2026-11-01T05:30:00Z"),
            Triple("Australia/Lord_Howe", "2026-10-04 02:15", "2026-10-03T15:45:00Z"),
            Triple("Australia/Lord_Howe", "2026-04-05 01:45", "2026-04-04T14:45:00Z"),
            Triple("Australia/Lord_Howe", "2026-04-05 01:45", "2026-04-04T15:15:00Z"),
            Triple("Europe/Berlin", "2026-10-25 02:30", "2026-10-25T00:30:00Z"),
            Triple("Pacific/Apia", "2011-12-30 12:00", "2011-12-30T22:00:00Z"),
        )
        for ((zone, text, proposed) in cases) {
            val v = verify(text, proposed, zone = ZoneId.of(zone))
            assertNull(v.dueAt, zone + text); assertNotNull(v.clarification)
        }
        assertNull(verify("明天12:00", "2011-12-30T22:00:00Z", zone = ZoneId.of("Pacific/Apia"), at = instant("2011-12-29T22:00:00Z")).dueAt)
        val lordHowe = ZoneId.of("Australia/Lord_Howe")
        assertEquals(11 * 3600, lordHowe.rules.getOffset(instant("2026-04-04T14:45:00Z")).totalSeconds)
        assertEquals(10 * 3600 + 30 * 60, lordHowe.rules.getOffset(instant("2026-04-04T15:15:00Z")).totalSeconds)
        for ((text, proposed) in listOf("2026-04-05 01:15" to "2026-04-04T14:15:00Z", "2026-04-05 02:15" to "2026-04-04T15:45:00Z", "2026-10-04 02:30" to "2026-10-03T15:30:00Z")) {
            val v = verify(text, proposed, zone = lordHowe)
            assertEquals(instant(proposed), v.dueAt, text); assertNull(v.clarification, text)
        }
        for ((text, proposed) in listOf("2026-04-05T01:45:00+11:00" to "2026-04-04T14:45:00Z", "2026-04-05T01:45:00+10:30" to "2026-04-04T15:15:00Z")) {
            assertEquals(instant(proposed), verify(text, proposed, zone = lordHowe).dueAt, "An explicit offset disambiguates the repeated wall clock")
        }
    }
}

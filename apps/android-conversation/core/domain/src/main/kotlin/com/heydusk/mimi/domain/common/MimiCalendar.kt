package com.heydusk.mimi.domain.common

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * iOS 里到处传的 `Calendar`（公历 + 时区）的 JVM 对应物。所有"哪一天""几点""星期几"都经由它，
 * 领域代码从不读系统时钟或默认时区（调用方注入 `now` 与本类），保证确定性。
 */
class MimiCalendar(val zone: ZoneId) {
    private fun zdt(date: Instant) = date.atZone(zone)

    fun startOfDay(date: Instant): Instant = zdt(date).toLocalDate().atStartOfDay(zone).toInstant()

    /** `calendar.date(byAdding: .day, value:, to:)`：保持当地钟点。 */
    fun addingDays(date: Instant, days: Int): Instant = zdt(date).plusDays(days.toLong()).toInstant()

    fun year(date: Instant): Int = zdt(date).year
    fun month(date: Instant): Int = zdt(date).monthValue
    fun day(date: Instant): Int = zdt(date).dayOfMonth
    fun hour(date: Instant): Int = zdt(date).hour
    fun minute(date: Instant): Int = zdt(date).minute

    /** Swift `.weekday`：周日 = 1 … 周六 = 7。 */
    fun weekday(date: Instant): Int = when (zdt(date).dayOfWeek) {
        DayOfWeek.SUNDAY -> 1
        DayOfWeek.MONDAY -> 2
        DayOfWeek.TUESDAY -> 3
        DayOfWeek.WEDNESDAY -> 4
        DayOfWeek.THURSDAY -> 5
        DayOfWeek.FRIDAY -> 6
        DayOfWeek.SATURDAY -> 7
    }

    fun isSameDay(a: Instant, b: Instant): Boolean = zdt(a).toLocalDate() == zdt(b).toLocalDate()

    /** `calendar.date(from: DateComponents(year:month:day:))`，日期不存在（如 2 月 30 日）返回 null；结果是当天零点。 */
    fun date(year: Int, month: Int, day: Int): Instant? {
        val local = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        return local.atStartOfDay(zone).toInstant()
    }

    /** `calendar.date(bySettingHour:minute:second:of:)`：当天的这个钟点（落在夏令时空档则顺延，与系统一致）。 */
    fun date(day: Instant, hour: Int, minute: Int, second: Int = 0): Instant? {
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        val local = zdt(day).toLocalDate().atTime(hour, minute, second)
        return local.atZone(zone).toInstant()
    }

    /** 两个日期零点之间相隔的整日历日（`dateComponents([.day], from:to:)`）。 */
    fun daysBetweenStartsOfDay(earlier: Instant, later: Instant): Int =
        ChronoUnit.DAYS.between(zdt(earlier).toLocalDate(), zdt(later).toLocalDate()).toInt()

    /** `calendar.ordinality(of: .day, in: .era, for:)`：公元 1 年 1 月 1 日 = 1。 */
    fun dayOrdinalInEra(date: Instant): Int = (zdt(date).toLocalDate().toEpochDay() + 719_163L).toInt()

    companion object {
        fun of(zoneId: String) = MimiCalendar(ZoneId.of(zoneId))
        fun system() = MimiCalendar(ZoneId.systemDefault())
    }
}

internal fun localDateTimeOf(date: Instant, zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(date, zone)

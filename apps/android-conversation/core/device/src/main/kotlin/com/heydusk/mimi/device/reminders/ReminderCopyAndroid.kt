package com.heydusk.mimi.device.reminders

import android.content.Context
import android.text.format.DateFormat
import com.heydusk.mimi.domain.language.AppLanguage
import com.heydusk.mimi.i18n.UiCatalog
import com.heydusk.mimi.i18n.UiStrings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 通知文案的生产实现：文案取共享目录（语言跟系统语言，缺则回落中文键），开始时间按系统语言与当下时区格式化
 * （「10月29日 15:00」/「Oct 29, 15:00」，24 小时制跟随系统设置）。
 */
fun ReminderCopy.Companion.forContext(
    context: Context,
    zone: () -> ZoneId = { ZoneId.systemDefault() },
    locale: () -> Locale = { Locale.getDefault() },
): ReminderCopy {
    val app = context.applicationContext
    return ReminderCopy(
        ui = { key, arguments ->
            val catalog: UiCatalog = UiStrings.catalog(app)
            catalog.text(key, AppLanguage.fromLocale(locale()), *arguments.toTypedArray())
        },
        formatTime = { at: Instant ->
            val loc = locale()
            val skeleton = if (DateFormat.is24HourFormat(app)) "MMMdHm" else "MMMdjm"
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(loc, skeleton), loc).withZone(zone()).format(at)
        },
    )
}

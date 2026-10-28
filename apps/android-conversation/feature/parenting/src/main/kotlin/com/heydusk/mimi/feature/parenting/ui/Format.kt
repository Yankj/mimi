package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.runtime.Composable
import com.heydusk.mimi.domain.language.AppLanguage
import com.heydusk.mimi.i18n.currentAppLanguage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val zh = DateTimeFormatter.ofPattern("M\u6708d\u65e5 HH:mm", Locale.SIMPLIFIED_CHINESE) // 日期格式，不是界面文案
private val en = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH)

/** 「5月3日 18:00」/「May 3, 18:00」：家长看到的是自己时区的本地时间。 */
fun formatWhen(instant: Instant, zone: ZoneId, language: AppLanguage): String =
    (if (language == AppLanguage.English) en else zh).format(instant.atZone(zone))

@Composable
fun formatWhen(instant: Instant, zone: ZoneId): String = formatWhen(instant, zone, currentAppLanguage())

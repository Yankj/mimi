package com.heydusk.mimi.device.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.heydusk.mimi.domain.language.AppLanguage
import com.heydusk.mimi.i18n.UiStrings
import java.util.Locale

/** 通知渠道：「安排提醒」。渠道名与说明走文案目录（按系统语言）。 */
object ReminderChannels {
    const val ID = "mimi_parenting_reminders"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val catalog = UiStrings.catalog(context)
        val language = AppLanguage.fromLocale(Locale.getDefault())
        val channel = NotificationChannel(ID, catalog.text("安排提醒", language), NotificationManager.IMPORTANCE_HIGH).apply {
            description = catalog.text("在已确认的安排开始前提醒你。只在本机发送。", language)
        }
        manager.createNotificationChannel(channel)
    }
}

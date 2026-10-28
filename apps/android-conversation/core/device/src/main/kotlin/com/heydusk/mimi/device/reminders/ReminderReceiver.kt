package com.heydusk.mimi.device.reminders

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.heydusk.mimi.device.R
import com.heydusk.mimi.domain.parenting.ParentingReminderPayload

/**
 * 到点：AlarmManager 触发。只有提醒属于当前账号时才发通知——退出登录后迟到的闹钟直接丢弃（对应 iOS `belongsToCurrent`）。
 * 通知点按 → 打开应用并带上安排与账号（见 [ReminderOpen]）。
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val info = intent.extras?.keySet()?.associateWith { intent.extras?.getString(it) } ?: return
        if (!ParentingReminderPayload.isParenting(info)) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val prefs = ReminderPrefs(context)
        prefs.unregister(id) // 这一条已经响了
        if (!ParentingReminderPayload.belongsToCurrent(info, prefs.activeSubject)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        ReminderChannels.ensure(context)
        val open = ReminderOpen.intent(
            context,
            subject = intent.getStringExtra(ParentingReminderPayload.SUBJECT_KEY).orEmpty(),
            arrangement = intent.getStringExtra(ParentingReminderPayload.ARRANGEMENT_KEY).orEmpty(),
        )
        val content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val notification = Notification.Builder(context, ReminderChannels.ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(body.substringBefore('\n'))
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(content)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        manager.notify(id, notificationId(id), notification)
    }

    companion object {
        const val ACTION_FIRE = "com.heydusk.mimi.action.PARENTING_REMINDER_FIRE"
        const val EXTRA_ID = "mimi_reminder_id"
        const val EXTRA_TITLE = "mimi_reminder_title"
        const val EXTRA_BODY = "mimi_reminder_body"
        fun notificationId(id: String): Int = id.hashCode()
    }
}

/** 点通知打开应用的深链：`mimi://parenting/arrangement/<id>`，附带账号与标记；应用据此回到「今天」。 */
data class ReminderOpen(val subject: String, val arrangement: String) {
    companion object {
        fun intent(context: Context, subject: String, arrangement: String): Intent {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
            return launch
                .setData(Uri.parse("mimi://parenting/arrangement/$arrangement"))
                .putExtra(ParentingReminderPayload.MARKER, "1")
                .putExtra(ParentingReminderPayload.SUBJECT_KEY, subject)
                .putExtra(ParentingReminderPayload.ARRANGEMENT_KEY, arrangement)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        /** 从启动/新的 Intent 里取出提醒点击；不是提醒点击返回 null。账号核对由调用方做（只有同一账号才打开）。 */
        fun parse(intent: Intent?): ReminderOpen? {
            val extras = intent?.extras ?: return null
            if (extras.getString(ParentingReminderPayload.MARKER) != "1") return null
            val subject = extras.getString(ParentingReminderPayload.SUBJECT_KEY) ?: return null
            val arrangement = extras.getString(ParentingReminderPayload.ARRANGEMENT_KEY) ?: return null
            return ReminderOpen(subject, arrangement)
        }
    }
}

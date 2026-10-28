package com.heydusk.mimi.device.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.heydusk.mimi.domain.parenting.ParentingReminderPayload
import java.time.Instant

enum class AlarmPrecision { Exact, Inexact }

/**
 * 生产用的系统通知中心：AlarmManager 排程 + NotificationManager 发送。
 *
 * - 只有 `mimi.parenting.v1.` 前缀的请求会被列出或移除，别的永远不碰。
 * - 读回（`pending`）= 登记表里的项 **且** 系统里对应的 PendingIntent 真的还在（`FLAG_NO_CREATE`）**且** 时间未过；
 *   重启后系统清空闹钟，PendingIntent 随之消失，读回自然为空，下一次对账会据账本重排。
 * - 精确闹钟：有「闹钟和提醒」权限用 `setExactAndAllowWhileIdle`，没有则降级为 `setAndAllowWhileIdle`（可能晚几分钟）。
 *   Doze 下系统对每个应用的「空闲放行」闹钟大约每 9 分钟才放行一次，这是平台限制。
 */
class AlarmNotificationCenter(
    context: Context,
    private val prefs: ReminderPrefs = ReminderPrefs(context),
    private val permission: NotificationPermissionBridge = NotificationPermissionBridge(),
    private val clock: () -> Instant = { Instant.now() },
) : ReminderNotificationCenter {
    private val context = context.applicationContext
    /** 最近一次实际用的闹钟精度（诊断与测试用）。 */
    @Volatile var lastPrecision: AlarmPrecision? = null; private set

    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    override suspend fun authorization(): NotificationAuthorization {
        if (notifications.areNotificationsEnabled()) {
            val channel = notifications.getNotificationChannel(ReminderChannels.ID)
            return if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) NotificationAuthorization.Denied
            else NotificationAuthorization.Authorized
        }
        val runtimePermission = Build.VERSION.SDK_INT >= 33
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return if (runtimePermission && !granted && !prefs.permissionAsked) NotificationAuthorization.NotDetermined
        else NotificationAuthorization.Denied
    }

    override suspend fun requestAuthorization(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return notifications.areNotificationsEnabled()
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return notifications.areNotificationsEnabled()
        }
        val answer = permission.request() ?: return false // 没有界面可弹：什么都没问，不记「问过」
        prefs.permissionAsked = true
        return answer && notifications.areNotificationsEnabled()
    }

    override suspend fun pending(): List<PendingNotification> {
        val now = clock()
        val result = mutableListOf<PendingNotification>()
        for ((id, entry) in prefs.registered()) {
            if (!id.startsWith(ParentingReminderPayload.IDENTIFIER_PREFIX)) continue
            val (fireAt, signature) = entry
            if (!fireAt.isAfter(now) || pendingIntent(id, null, PendingIntent.FLAG_NO_CREATE) == null) {
                prefs.unregister(id) // 已响过，或系统已经丢掉它（重启、被清除）
                continue
            }
            result.add(PendingNotification(id, signature, fireAt))
        }
        return result
    }

    override suspend fun add(request: NotificationRequest) {
        require(request.id.startsWith(ParentingReminderPayload.IDENTIFIER_PREFIX)) { "not a parenting reminder id" }
        val operation = checkNotNull(pendingIntent(request.id, request, PendingIntent.FLAG_UPDATE_CURRENT)) { "PendingIntent unavailable" }
        val at = request.fireAt.toEpochMilli()
        if (exactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            lastPrecision = AlarmPrecision.Exact
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            lastPrecision = AlarmPrecision.Inexact
        }
        prefs.register(request.id, request.fireAt, request.extras[ParentingReminderPayload.SIGNATURE_KEY].orEmpty())
    }

    override suspend fun remove(ids: List<String>) {
        for (id in ids.filter { it.startsWith(ParentingReminderPayload.IDENTIFIER_PREFIX) }) {
            pendingIntent(id, null, PendingIntent.FLAG_NO_CREATE)?.let {
                alarms.cancel(it)
                it.cancel()
            }
            prefs.unregister(id)
            notifications.cancel(id, ReminderReceiver.notificationId(id))
        }
    }

    /** 退出登录/清除数据：任何账号的提醒都不能留在队列或锁屏上。 */
    fun removeAll() {
        for ((id, _) in prefs.registered()) {
            pendingIntent(id, null, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
            prefs.unregister(id)
        }
        notifications.activeNotifications
            .filter { it.tag?.startsWith(ParentingReminderPayload.IDENTIFIER_PREFIX) == true }
            .forEach { notifications.cancel(it.tag, it.id) }
    }

    override fun exactAlarms(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    private fun pendingIntent(id: String, request: NotificationRequest?, flag: Int): PendingIntent? {
        val intent = Intent(ReminderReceiver.ACTION_FIRE)
            .setClass(context, ReminderReceiver::class.java)
            .setData(Uri.parse("mimi-reminder://$id"))
        if (request != null) {
            intent.putExtra(ReminderReceiver.EXTRA_ID, request.id)
            intent.putExtra(ReminderReceiver.EXTRA_TITLE, request.title)
            intent.putExtra(ReminderReceiver.EXTRA_BODY, request.body)
            request.extras.forEach { (k, v) -> intent.putExtra(k, v) }
        }
        return PendingIntent.getBroadcast(context, 0, intent, flag or PendingIntent.FLAG_IMMUTABLE)
    }
}

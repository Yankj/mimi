package com.heydusk.mimi.device.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 应用在进程启动时注册（Application.onCreate），接收器唤醒进程后就能调到。 */
object ReminderHooks {
    /** 按账本重新对账提醒。reason 只用于日志/测试。 */
    @Volatile var resync: (suspend (reason: String) -> Unit)? = null
}

/**
 * 开机、应用更新、时区变化、校时、精确闹钟权限变化：系统会清掉或搅乱闹钟，或者"当地一天/免打扰"的含义变了，
 * 所以都要按账本重排一遍。真正的规则在调度器里；这里只负责唤醒并交给 [ReminderHooks.resync]。
 */
class ReminderSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = reasonFor(intent.action) ?: return
        val hook = ReminderHooks.resync ?: return
        val pending: PendingResult? = goAsync() // 直接调用 onReceive（测试）时为 null
        scope.launch {
            try { withTimeoutOrNull(TIMEOUT_MS) { hook(reason) } } finally { pending?.finish() }
        }
    }

    companion object {
        const val TIMEOUT_MS = 8_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun reasonFor(action: String?): String? = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "package-replaced"
            Intent.ACTION_TIMEZONE_CHANGED -> "timezone"
            Intent.ACTION_TIME_CHANGED -> "time"
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> "exact-alarm-permission"
            else -> null
        }
    }
}

package com.heydusk.mimi

import android.app.Application
import com.heydusk.mimi.device.reminders.ReminderChannels
import com.heydusk.mimi.device.reminders.ReminderHooks
import com.heydusk.mimi.feature.parenting.model.ParentingSession
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Hilt 的根。依赖注入只在 :app 里装配；:core:* 模块保持无框架。 */
@HiltAndroidApp
class MimiApplication : Application() {
    @Inject lateinit var session: Lazy<ParentingSession>

    override fun onCreate() {
        super.onCreate()
        ReminderChannels.ensure(this)
        // 开机、时区/时间变化、应用更新、精确闹钟权限变化：系统接收器唤醒进程后，在没有界面的情况下按账本重排提醒。
        ReminderHooks.resync = { _ ->
            session.get().awaitReady(RESYNC_TIMEOUT_MS)?.model?.refreshReminders()
        }
    }

    private companion object {
        const val RESYNC_TIMEOUT_MS = 6_000L
    }
}

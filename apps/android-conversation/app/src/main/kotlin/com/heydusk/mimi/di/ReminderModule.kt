package com.heydusk.mimi.di

import android.content.Context
import com.heydusk.mimi.AndroidSystemSettingsOpener
import com.heydusk.mimi.SystemSettingsOpener
import com.heydusk.mimi.device.reminders.AlarmNotificationCenter
import com.heydusk.mimi.device.reminders.NotificationPermissionBridge
import com.heydusk.mimi.device.reminders.ParentingReminderScheduler
import com.heydusk.mimi.device.reminders.ReminderCopy
import com.heydusk.mimi.device.reminders.ReminderPrefs
import com.heydusk.mimi.device.reminders.SharedPrefsReminderMemory
import com.heydusk.mimi.device.reminders.SharedPrefsReminderSettingsStore
import com.heydusk.mimi.device.reminders.forContext
import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.feature.parenting.model.ParentingSession
import com.heydusk.mimi.feature.parenting.model.ReminderWiring
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import javax.inject.Singleton

/**
 * M4：本机提醒的装配。系统通知中心（AlarmManager + NotificationManager）只在这里 new；
 * 账号相关的部分（调度器、设置、「已响过」记录）由工厂按账号造，退出登录时一并清掉。
 */
@Module
@InstallIn(SingletonComponent::class)
object ReminderModule {
    @Provides @Singleton
    fun permissionBridge() = NotificationPermissionBridge()

    @Provides @Singleton
    fun systemSettings(@ApplicationContext context: Context): SystemSettingsOpener = AndroidSystemSettingsOpener(context)

    @Provides @Singleton
    fun reminderPrefs(@ApplicationContext context: Context) = ReminderPrefs(context)

    @Provides @Singleton
    fun notificationCenter(
        @ApplicationContext context: Context,
        prefs: ReminderPrefs,
        bridge: NotificationPermissionBridge,
    ) = AlarmNotificationCenter(context, prefs, bridge)

    @Provides @Singleton
    fun reminderFactory(
        @ApplicationContext context: Context,
        center: AlarmNotificationCenter,
        prefs: ReminderPrefs,
    ): ParentingSession.ReminderFactory = ParentingSession.ReminderFactory { subject, isCurrent ->
        val memory = SharedPrefsReminderMemory(context, subject)
        val settings = SharedPrefsReminderSettingsStore(context, subject)
        ReminderWiring(
            scheduler = ParentingReminderScheduler(
                center, subject, { MimiCalendar(ZoneId.systemDefault()) }, ReminderCopy.forContext(context), memory, isCurrent,
            ),
            settings = settings,
            // 迟到的闹钟只对当前账号发通知：激活时登记，退出时撤销。
            onActivate = { prefs.activeSubject = subject.toString() },
            onSignOut = {
                prefs.activeSubject = null
                center.removeAll()
                settings.erase()
                memory.save(emptyMap())
            },
        )
    }
}

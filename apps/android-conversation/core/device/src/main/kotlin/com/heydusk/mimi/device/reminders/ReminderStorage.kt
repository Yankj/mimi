package com.heydusk.mimi.device.reminders

import android.content.Context
import android.content.SharedPreferences
import com.heydusk.mimi.domain.parenting.ParentingReminderLead
import com.heydusk.mimi.domain.parenting.ParentingReminderSettings
import java.time.Instant
import java.util.UUID

/**
 * 提醒相关的本机小状态，全部放在账本**旁边**（不进加密账本）：
 * - 「已排队」登记表：标识符 → 触发时间 + 签名。**不含孩子名字和标题**（那些只在系统的 PendingIntent 里，进程外），
 *   所以明文 SharedPreferences 不会泄露账本内容；它的作用是让 `pending()` 能做读回、让到点的接收器知道哪些已发。
 * - 「问过通知权限」标记（Android 13+ 区分「从未问过」与「拒绝过」）。
 * - 「当前账号」：到点接收器只在提醒属于当前账号时才发通知（退出登录后迟到的闹钟直接丢弃）。
 */
class ReminderPrefs(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun register(id: String, fireAt: Instant, signature: String) {
        prefs.edit().putString(REGISTRY + id, "${fireAt.toEpochMilli()}|$signature").apply()
    }

    fun unregister(id: String) { prefs.edit().remove(REGISTRY + id).apply() }

    /** 登记表里的全部项：id → (触发时间, 签名)。 */
    fun registered(): Map<String, Pair<Instant, String>> =
        prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(REGISTRY) || value !is String) return@mapNotNull null
            val parts = value.split('|', limit = 2)
            val millis = parts.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
            key.removePrefix(REGISTRY) to (Instant.ofEpochMilli(millis) to parts.getOrElse(1) { "" })
        }.toMap()

    var permissionAsked: Boolean
        get() = prefs.getBoolean(ASKED, false)
        set(value) { prefs.edit().putBoolean(ASKED, value).apply() }

    var activeSubject: String?
        get() = prefs.getString(ACTIVE, null)
        set(value) { prefs.edit().apply { if (value == null) remove(ACTIVE) else putString(ACTIVE, value.lowercase()) }.apply() }

    fun isActive(subject: String?): Boolean = subject != null && subject.equals(activeSubject, ignoreCase = true)

    companion object {
        const val FILE = "mimi.reminders"
        private const val REGISTRY = "registry."
        private const val ASKED = "permissionAsked"
        private const val ACTIVE = "activeSubject"
    }
}

/** 按账号保存提醒设置（键名模仿 iOS：`mimi.parenting.reminders.v1.<subject>`）。默认关闭。 */
class SharedPrefsReminderSettingsStore(context: Context, subjectID: UUID) : ReminderSettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val base = "mimi.parenting.reminders.v1." + subjectID.toString().lowercase()

    override fun load(): ParentingReminderSettings {
        if (!prefs.contains("$base.enabled")) return ParentingReminderSettings.DEFAULT
        val lead = ParentingReminderLead.entries.firstOrNull { it.rawValue == prefs.getString("$base.lead", null) }
            ?: ParentingReminderSettings.DEFAULT.lead
        return ParentingReminderSettings(prefs.getBoolean("$base.enabled", false), lead, prefs.getBoolean("$base.hideContent", false))
    }

    override fun save(settings: ParentingReminderSettings) {
        prefs.edit()
            .putBoolean("$base.enabled", settings.enabled)
            .putString("$base.lead", settings.lead.rawValue)
            .putBoolean("$base.hideContent", settings.hideContent)
            .apply()
    }

    override fun erase() {
        prefs.edit().remove("$base.enabled").remove("$base.lead").remove("$base.hideContent").apply()
    }

    companion object { const val FILE = "mimi.reminders.settings" }
}

class SharedPrefsReminderMemory(context: Context, subjectID: UUID) : ReminderMemory {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val prefix = "mimi.parenting.reminders.memory.v1." + subjectID.toString().lowercase() + "."

    override fun load(): Map<UUID, Instant> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(prefix) || value !is Long) return@mapNotNull null
        val id = runCatching { UUID.fromString(key.removePrefix(prefix)) }.getOrNull() ?: return@mapNotNull null
        id to Instant.ofEpochMilli(value)
    }.toMap()

    override fun save(value: Map<UUID, Instant>) {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
            value.forEach { (id, at) -> putLong(prefix + id.toString(), at.toEpochMilli()) }
        }.apply()
    }

    fun erase() = save(emptyMap())

    companion object { const val FILE = "mimi.reminders.memory" }
}

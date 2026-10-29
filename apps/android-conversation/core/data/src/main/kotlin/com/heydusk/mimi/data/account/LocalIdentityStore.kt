package com.heydusk.mimi.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 本机匿名身份（M2；真正的 Supabase 登录在 M5）。
 *
 * 语义对齐 iOS 的「体验账号」路径：进程里有一个稳定的 subject UUID，写入 DataStore；
 * 账本与 Keystore 别名都按这个 UUID 分区。退出登录 = 丢掉这个 UUID（并清掉它的密文与密钥），
 * 下次启动再发一个新的，旧账本永远读不到。
 */
class LocalIdentityStore(private val store: DataStore<Preferences>) {
    /** [cloudBound] = 主体是云端（Supabase）账号的 id（M5）；false = 仅本机的体验主体。 */
    data class Snapshot(val subjectID: UUID?, val onboardingDone: Boolean, val cloudBound: Boolean = false)

    val snapshot: Flow<Snapshot> = store.data
        .catch { if (it is java.io.IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw it }
        .map { prefs ->
            Snapshot(
                subjectID = prefs[SUBJECT]?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                onboardingDone = prefs[ONBOARDING] ?: false,
                cloudBound = prefs[CLOUD] ?: false,
            )
        }
        .distinctUntilChanged()

    /** 没有主体时创建一个并持久化；已有则原样返回。 */
    suspend fun ensureSubject(): UUID {
        var created: UUID? = null
        store.edit { prefs ->
            val existing = prefs[SUBJECT]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (existing != null) {
                created = existing
            } else {
                val id = UUID.randomUUID()
                prefs[SUBJECT] = id.toString()
                created = id
            }
        }
        return created!!
    }

    suspend fun clearSubject() {
        store.edit { it.remove(SUBJECT); it.remove(CLOUD) }
    }

    /** M5：把账本主体换成登录账号的 id。 */
    suspend fun setSubject(subject: UUID, cloudBound: Boolean) {
        store.edit { it[SUBJECT] = subject.toString(); it[CLOUD] = cloudBound }
    }

    suspend fun setOnboardingDone(done: Boolean = true) {
        store.edit { it[ONBOARDING] = done }
    }

    private companion object {
        val SUBJECT = stringPreferencesKey("local_anonymous_subject")
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val CLOUD = booleanPreferencesKey("subject_cloud_bound")
    }
}

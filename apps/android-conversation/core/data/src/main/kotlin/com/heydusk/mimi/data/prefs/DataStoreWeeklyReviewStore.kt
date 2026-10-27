package com.heydusk.mimi.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.heydusk.mimi.domain.parenting.ParentingWeeklyReviewStore
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first

/**
 * 「上次周复盘时间」，每个登录主体一个键（与 iOS 的 UserDefaults 键同名同形），存毫秒时间戳。
 * 它只是起手卡的提示，不是账本的一部分。
 */
class DataStoreWeeklyReviewStore(private val store: DataStore<Preferences>, subjectID: UUID) : ParentingWeeklyReviewStore {
    private val key = longPreferencesKey(ParentingWeeklyReviewStore.key(subjectID))

    override suspend fun load(): Instant? = store.data.first()[key]?.let(Instant::ofEpochMilli)
    override suspend fun save(date: Instant) { store.edit { it[key] = date.toEpochMilli() } }
    override suspend fun erase() { store.edit { it.remove(key) } }

    companion object {
        /** 清除所有主体的周复盘日期（永久删除全部数据时用）。 */
        suspend fun purgeAll(store: DataStore<Preferences>) {
            store.edit { prefs ->
                prefs.asMap().keys.filter { it.name.startsWith(ParentingWeeklyReviewStore.KEY_PREFIX) }.forEach { prefs.remove(it) }
            }
        }
    }
}

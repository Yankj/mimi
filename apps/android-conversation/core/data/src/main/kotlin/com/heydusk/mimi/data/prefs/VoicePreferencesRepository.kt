package com.heydusk.mimi.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 语音偏好。只有一项：家长是否已经看过「首次说话前的站内说明」（键名与 iOS 一致：`mimi.voice.permissionExplained`）。
 * 它只决定要不要在第一次按麦克风时先讲一句话，不是权限本身——权限永远以系统状态为准。
 */
class VoicePreferencesRepository(private val store: DataStore<Preferences>) {
    val permissionExplained: Flow<Boolean> = store.data
        .catch { if (it is java.io.IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw it }
        .map { it[EXPLAINED] ?: false }
        .distinctUntilChanged()

    suspend fun setPermissionExplained() { store.edit { it[EXPLAINED] = true } }

    companion object {
        const val EXPLAINED_KEY = "mimi.voice.permissionExplained"
        private val EXPLAINED = booleanPreferencesKey(EXPLAINED_KEY)
    }
}

package com.heydusk.mimi.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.heydusk.mimi.domain.theme.Appearance
import com.heydusk.mimi.domain.theme.Palette
import com.heydusk.mimi.domain.theme.ThemePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 主题偏好的持久化。键名与 iOS 的 @AppStorage 一样："palette" 与 "theme"；未知或缺失的值回落到默认。 */
class ThemePreferencesRepository(private val store: DataStore<Preferences>) {
    val preferences: Flow<ThemePreferences> = store.data
        .catch { if (it is java.io.IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw it }
        .map { ThemePreferences.fromRaw(it[PALETTE], it[APPEARANCE]) }
        .distinctUntilChanged()

    suspend fun setPalette(palette: Palette) { store.edit { it[PALETTE] = palette.rawValue } }
    suspend fun setAppearance(appearance: Appearance) { store.edit { it[APPEARANCE] = appearance.rawValue } }

    private companion object {
        val PALETTE = stringPreferencesKey(Palette.PREFERENCE_KEY)
        val APPEARANCE = stringPreferencesKey(Appearance.PREFERENCE_KEY)
    }
}

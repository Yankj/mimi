package com.heydusk.mimi.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * 应用偏好（主题、每主体的周复盘日期……）的 DataStore。同一个文件在进程里只能有一个实例，所以由 Hilt 在 :app 里以单例提供。
 * 文件损坏时回到空偏好：这里没有账本数据，丢了只是主题回到默认。账本在 [com.heydusk.mimi.data.ledger] 里，有自己的损坏处理。
 */
object MimiDataStore {
    const val FILE_NAME = "mimi_preferences.preferences_pb"

    fun create(file: File, scope: CoroutineScope): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { file },
    )
}

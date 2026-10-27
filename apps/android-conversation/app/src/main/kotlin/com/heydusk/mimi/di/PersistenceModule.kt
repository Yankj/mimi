package com.heydusk.mimi.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.heydusk.mimi.data.prefs.MimiDataStore
import com.heydusk.mimi.data.prefs.ThemePreferencesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

/** DataStore 与主题偏好。单测用 `@TestInstallIn` 换成临时文件。 */
@Module
@InstallIn(SingletonComponent::class)
object PersistenceModule {
    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides @Singleton
    fun preferencesDataStore(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): DataStore<Preferences> =
        MimiDataStore.create(File(context.filesDir, "datastore/" + MimiDataStore.FILE_NAME), scope)

    @Provides @Singleton
    fun themePreferences(store: DataStore<Preferences>) = ThemePreferencesRepository(store)
}

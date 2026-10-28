package com.heydusk.mimi

import com.heydusk.mimi.data.prefs.MimiDataStore
import com.heydusk.mimi.data.prefs.ThemePreferencesRepository
import com.heydusk.mimi.domain.theme.Appearance
import com.heydusk.mimi.domain.theme.Palette
import com.heydusk.mimi.domain.theme.ThemePreferences
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

/** 偏好经 ViewModel 写入、"杀进程"（取消 DataStore 的作用域）后全新的 ViewModel 读回同一份。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeViewModelTest {
    @BeforeTest fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun resetMain() = Dispatchers.resetMain()

    private suspend fun ThemeViewModel.ready(): ThemePreferences =
        assertIs<ThemeUiState.Ready>(withTimeout(5_000) { state.first { it is ThemeUiState.Ready } }).preferences

    @Test
    fun savedPreferencesSurviveAColdRestartAndStartInLoading() = runBlocking {
        val file = Files.createTempDirectory("mimi-theme-vm").resolve(MimiDataStore.FILE_NAME).toFile()
        val saved = ThemePreferences(Palette.entries.first { it != Palette.default }, Appearance.Dark)

        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = ThemeViewModel(ThemePreferencesRepository(MimiDataStore.create(file, firstScope)))
        assertEquals(ThemePreferences(), first.ready(), "nothing saved yet: defaults")
        first.update(saved)
        assertEquals(saved, withTimeout(5_000) { first.state.first { (it as? ThemeUiState.Ready)?.preferences == saved } }.let { (it as ThemeUiState.Ready).preferences })
        firstScope.coroutineContext[Job]!!.cancelAndJoin() // 进程结束（等 DataStore 释放文件登记）

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val second = ThemeViewModel(ThemePreferencesRepository(MimiDataStore.create(file, secondScope)))
            assertEquals(saved, second.ready())
        } finally {
            secondScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}

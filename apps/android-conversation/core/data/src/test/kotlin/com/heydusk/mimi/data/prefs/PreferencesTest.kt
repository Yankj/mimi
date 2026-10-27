package com.heydusk.mimi.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.heydusk.mimi.domain.parenting.ParentingWeeklyReviewStore
import com.heydusk.mimi.domain.theme.Appearance
import com.heydusk.mimi.domain.theme.Palette
import com.heydusk.mimi.domain.theme.ThemePreferences
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class PreferencesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file by lazy { File(tmp.newFolder(), MimiDataStore.FILE_NAME) }
    private fun open(scope: CoroutineScope): DataStore<Preferences> = MimiDataStore.create(file, scope)
    private fun <T> withStore(body: suspend (DataStore<Preferences>) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { body(open(scope)) } finally { scope.cancel() }
    }

    @Test fun themeDefaultsThenPersistsAcrossAColdStoreWithTheIosKeys() {
        withStore { ds ->
            val repo = ThemePreferencesRepository(ds)
            assertEquals(ThemePreferences(), repo.preferences.first())
            repo.setPalette(Palette.entries.first { it != Palette.default })
            repo.setAppearance(Appearance.entries.first { it != Appearance.default })
        }
        // 全新的 DataStore 实例读同一个文件
        withStore { ds ->
            val loaded = ThemePreferencesRepository(ds).preferences.first()
            assertEquals(Palette.entries.first { it != Palette.default }, loaded.palette)
            assertEquals(Appearance.entries.first { it != Appearance.default }, loaded.appearance)
            val raw = ds.data.first()
            assertEquals(loaded.palette.rawValue, raw[stringPreferencesKey("palette")])
            assertEquals(loaded.appearance.rawValue, raw[stringPreferencesKey("theme")])
        }
    }

    @Test fun unknownStoredValuesFallBackToDefaults() {
        withStore { ds ->
            ds.edit { it[stringPreferencesKey("palette")] = "no-such-palette"; it[stringPreferencesKey("theme")] = "???" }
            assertEquals(ThemePreferences(), ThemePreferencesRepository(ds).preferences.first())
        }
    }

    @Test fun corruptPreferencesFileResetsToDefaultsInsteadOfCrashing() {
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x55))
        withStore { ds -> assertEquals(ThemePreferences(), ThemePreferencesRepository(ds).preferences.first()) }
    }

    @Test fun weeklyReviewIsPerSubjectUsesTheIosKeyAndErases() {
        val a = UUID.fromString("D1000000-0000-4000-8000-000000000001"); val b = UUID.fromString("D1000000-0000-4000-8000-000000000002")
        withStore { ds ->
            val sa = DataStoreWeeklyReviewStore(ds, a); val sb = DataStoreWeeklyReviewStore(ds, b)
            assertNull(sa.load())
            val when1 = Instant.parse("2026-10-25T12:00:00Z")
            sa.save(when1)
            assertEquals(when1, sa.load()); assertNull(sb.load(), "another signed-in subject has its own value")
            assertEquals("mimi.parenting.weeklyReview.lastAt.v1." + a.toString().lowercase(), ParentingWeeklyReviewStore.key(a))
            assertEquals(when1, DataStoreWeeklyReviewStore(ds, a).load())
            sa.erase(); assertNull(sa.load())
        }
    }

    @Test fun purgeAllRemovesEverySubjectsReviewDateButKeepsTheme() {
        withStore { ds ->
            val repo = ThemePreferencesRepository(ds)
            repo.setPalette(Palette.entries.first { it != Palette.default })
            val s1 = DataStoreWeeklyReviewStore(ds, UUID.randomUUID()); val s2 = DataStoreWeeklyReviewStore(ds, UUID.randomUUID())
            s1.save(Instant.parse("2026-10-25T12:00:00Z")); s2.save(Instant.parse("2026-10-26T12:00:00Z"))
            DataStoreWeeklyReviewStore.purgeAll(ds)
            assertNull(s1.load()); assertNull(s2.load())
            assertEquals(Palette.entries.first { it != Palette.default }, repo.preferences.first().palette)
        }
    }
}

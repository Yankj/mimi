package com.heydusk.mimi

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.heydusk.mimi.data.ledger.LedgerScope
import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.di.ParentingLedgerFactory
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import android.os.Looper
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 冒烟：真实的 MainActivity（含启动页主题、Hilt 图）能在 Robolectric 里起来并显示三 Tab 空壳；
 * 在"我们家"点选配色会落进 DataStore（键 "palette"）；账本工厂能从 Hilt 图里拿到并读写一个加密账本。
 * 测试用的图把 DataStore 与 Keystore 换成临时文件与 JCA 内存密钥库（见 TestAppModule）。
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp", application = HiltTestApplication::class)
class MainActivityTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var store: DataStore<Preferences>
    @Inject lateinit var ledgers: ParentingLedgerFactory
    @Inject lateinit var epoch: AccountEpoch

    @Before
    fun inject() = hilt.inject()

    @Test
    fun launchesIntoTheParentingShell() {
        rule.onNodeWithTag("parenting.tabBar").assertIsDisplayed()
        rule.onNodeWithTag("parenting.tab.today").assertIsSelected()
    }

    @Test
    fun applicationIdIsFixed() {
        assertEquals("com.heydusk.mimi", RuntimeEnvironment.getApplication().packageName)
    }

    @Test
    fun pickingAPaletteIsWrittenToDataStoreUnderTheIosKey() {
        rule.onNodeWithTag("parenting.tab.family").performClick()
        rule.onNodeWithTag("parenting.palette.softBlush").performScrollTo().performClick()
        awaitStored("palette", "softBlush")
        rule.onNodeWithTag("parenting.palette.softBlush").assertIsSelected()
        rule.onNodeWithTag("parenting.appearance.dark").performScrollTo().performClick()
        awaitStored("theme", "dark")
        awaitStored("palette", "softBlush")
    }

    /** 写入在 IO 线程上、`launch` 在（Robolectric 的）主 Looper 上：每轮先放行主 Looper，再读 DataStore。 */
    private fun awaitStored(key: String, expected: String) {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
            if (runBlocking { store.data.first()[stringPreferencesKey(key)] } == expected) return
            Thread.sleep(50)
        }
        assertEquals(expected, runBlocking { store.data.first()[stringPreferencesKey(key)] })
    }

    @Test
    fun theHiltGraphYieldsAWorkingEncryptedLedger() = runBlocking {
        val subject = UUID.randomUUID()
        epoch.activate(subject.toString())
        val ledger = ledgers.open(LedgerScope(UUID.randomUUID(), UUID.randomUUID()), subject)
        assertTrue(ledger.load().children.isEmpty())
        val child = UUID.randomUUID()
        val saved = ledger.apply(ParentingCommand.AddChild(child, "合成"), 0)
        assertEquals(listOf(child), saved.children.map { it.id })
        assertEquals(saved, ledger.load())
    }
}

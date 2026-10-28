package com.heydusk.mimi

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.heydusk.mimi.feature.parenting.model.ParentingSession
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import android.os.Looper
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
 * 冒烟 + 端到端：真实的 MainActivity（Hilt 图、本机匿名会话、加密账本）能起来，过首次引导后显示三 Tab；
 * 设置里点选配色会落进 DataStore（键 "palette"）；手动添加孩子写进加密账本，放弃则不写。
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
    @Inject lateinit var session: ParentingSession

    @Before
    fun inject() = hilt.inject()

    /** 写入与会话在 IO 线程、状态收集在（Robolectric 的）主 Looper：每轮先放行主 Looper 再检查。 */
    private fun await(what: String, condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun passOnboarding() {
        await("onboarding") { rule.onAllNodesWithTag("parenting.onboarding").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("parenting.onboarding.continue").performClick()
        await("shell") { rule.onAllNodesWithTag("parenting.tabBar").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun readyModel() = (session.phase.value as ParentingSession.Phase.Ready).model

    @Test
    fun firstLaunchShowsOnboardingThenTheShellOnToday() {
        passOnboarding()
        rule.onNodeWithTag("parenting.tabBar").assertIsDisplayed()
        rule.onNodeWithTag("parenting.tab.today").assertIsSelected()
        rule.onNodeWithTag("parenting.hero.addChild").assertIsDisplayed()
    }

    @Test
    fun applicationIdIsFixed() {
        assertEquals("com.heydusk.mimi", RuntimeEnvironment.getApplication().packageName)
    }

    @Test
    fun pickingAPaletteInSettingsIsWrittenToDataStoreUnderTheIosKey() {
        passOnboarding()
        rule.onNodeWithTag("parenting.tab.family").performClick()
        rule.onNodeWithTag("parenting.family.settings").performClick()
        rule.onNodeWithTag("parenting.palette.softBlush").performScrollTo().performClick()
        awaitStored("palette", "softBlush")
        rule.onNodeWithTag("parenting.palette.softBlush").assertIsSelected()
        rule.onNodeWithTag("parenting.appearance.dark").performScrollTo().performClick()
        awaitStored("theme", "dark")
        awaitStored("palette", "softBlush")
    }

    private fun awaitStored(key: String, expected: String) {
        await("$key=$expected") { runBlocking { store.data.first()[stringPreferencesKey(key)] } == expected }
    }

    @Test
    fun addingAChildThroughTheFormIsSavedInTheEncryptedLedgerAndDiscardWritesNothing() {
        passOnboarding()
        await("ledger loaded") { runCatching { readyModel().loaded }.getOrDefault(false) }
        // 放弃：零写入
        rule.onNodeWithTag("parenting.hero.addChild").performClick()
        rule.onNodeWithTag("parenting.child.name").performTextInput("Xiaoming")
        rule.onNodeWithTag("parenting.form.discard").performClick()
        await("form closed") { rule.onAllNodesWithTag("parenting.form").fetchSemanticsNodes().isEmpty() }
        assertTrue(readyModel().workspace.children.isEmpty())
        assertEquals(0, readyModel().workspace.revision)
        // 保存：账本里有，Family 页显示
        rule.onNodeWithTag("parenting.hero.addChild").performClick()
        rule.onNodeWithTag("parenting.child.name").performTextInput("Xiaoming")
        rule.onNodeWithTag("parenting.name.save").performClick()
        await("child saved") { readyModel().workspace.children.size == 1 }
        assertEquals("Xiaoming", readyModel().workspace.children.single().name)
        rule.onNodeWithTag("parenting.tab.family").performClick()
        await("child on family") { rule.onAllNodesWithTag("parenting.family.child.${readyModel().workspace.children.single().id}").fetchSemanticsNodes().isNotEmpty() }
    }
}

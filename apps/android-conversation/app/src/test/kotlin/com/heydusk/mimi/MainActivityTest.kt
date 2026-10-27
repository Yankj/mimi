package com.heydusk.mimi

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 冒烟：真实的 MainActivity（含启动页主题）能在 Robolectric 里起来并显示三 Tab 空壳；applicationId 不被改动。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
class MainActivityTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesIntoTheParentingShell() {
        rule.onNodeWithTag("parenting.tabBar").assertIsDisplayed()
        rule.onNodeWithTag("parenting.tab.today").assertIsSelected()
    }

    @Test
    fun applicationIdIsFixed() {
        assertEquals("com.heydusk.mimi", RuntimeEnvironment.getApplication().packageName)
    }
}

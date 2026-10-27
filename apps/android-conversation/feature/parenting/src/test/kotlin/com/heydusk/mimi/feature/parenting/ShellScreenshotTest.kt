package com.heydusk.mimi.feature.parenting

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.asAndroidBitmap
import com.heydusk.mimi.designsystem.MimiTheme
import com.heydusk.mimi.domain.theme.Appearance
import com.heydusk.mimi.domain.theme.Palette
import com.heydusk.mimi.domain.theme.ThemePreferences
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 在 Linux 上用 Robolectric 原生图形把 B/C × 浅/深 四种主题的“我们家”页渲染成 PNG，
 * 输出到 build/screenshots/（不入库）。Haze 的真实模糊需要 GPU，这里看到的是降级色罩；
 * 真机上的模糊效果以后在手机上验收。主要用途：目测配色、着色按钮、底栏选中态是否正确。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-w411dp-h891dp-xxhdpi")
class ShellScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private var preferences by mutableStateOf(ThemePreferences())

    @Test
    fun rendersFourThemeCombinationsOfTheFamilyPage() {
        rule.setContent {
            MimiTheme(palette = preferences.palette, appearance = preferences.appearance) {
                ParentingHomeScreen(preferences = preferences, onPreferencesChange = { preferences = it })
            }
        }
        rule.onNodeWithTag("parenting.tab.family").performClick()
        val dir = File("build/screenshots").apply { mkdirs() }
        for (palette in Palette.entries) {
            for (appearance in listOf(Appearance.Light, Appearance.Dark)) {
                rule.runOnIdle { preferences = ThemePreferences(palette, appearance) }
                rule.waitForIdle()
                val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
                val name = "family-${if (palette == Palette.InkWhite) "C" else "B"}-${if (appearance == Appearance.Light) "day" else "night"}.png"
                File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
            }
        }
    }
}

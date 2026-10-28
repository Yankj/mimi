package com.heydusk.mimi.feature.parenting.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.heydusk.mimi.designsystem.MimiTheme
import com.heydusk.mimi.domain.parenting.ParentingArrangementEditTarget
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.domain.theme.Appearance
import com.heydusk.mimi.domain.theme.Palette
import com.heydusk.mimi.domain.theme.ThemePreferences
import com.heydusk.mimi.feature.parenting.ParentingHomeScreen
import com.heydusk.mimi.feature.parenting.ParentingTab
import com.heydusk.mimi.feature.parenting.actions.ParentingActions
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 有数据态截图基线（浅色 + 深色 × 今天/成长/时间线/目标/回顾/我们家/孩子详情/全部安排/调整表单/完成表单）。
 *
 * 仓库禁止提交 PNG，所以基线是 `src/test/resources/screenshot-baselines.json` 里的**亮度指纹**：
 * 把渲染结果缩成 24×48 的灰度格子、量化到 16 级，比较平均差与最大差。数据、时间、时区、UUID 全部固定。
 * 重录：`MIMI_RECORD_BASELINES=1 ./gradlew :feature:parenting:testDebugUnitTest --tests '*PopulatedBaselineTest*'`
 * （同时把 PNG 写到 build/screenshots/ 供目测）。指纹只在 Robolectric 原生图形（Linux）下录制；Haze 模糊真机效果不在此验收。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-w411dp-h891dp-xxhdpi")
class PopulatedBaselineTest {
    @get:Rule val rule = createComposeRule()

    private val now = Instant.parse("2027-03-10T06:00:00Z")
    private fun id(name: String) = UUID.nameUUIDFromBytes("baseline-$name".toByteArray())
    private val child = id("child")
    private val goal = id("goal")
    private val planned = id("planned")
    private val draft = id("draft")
    private val done = id("done")
    private val moved = id("moved")
    private val cancelled = id("cancelled")

    private val workspace: ParentingWorkspace by lazy {
        var w = ParentingWorkspace.EMPTY
        fun apply(c: ParentingCommand) { w = w.applying(c, now) }
        apply(ParentingCommand.AddChild(child, "Xiaoming"))
        apply(ParentingCommand.AddGoal(goal, child, "Read every day"))
        apply(ParentingCommand.CreateDraft(planned, child, "Read together", now.plusSeconds(2 * 3600), goal, ""))
        apply(ParentingCommand.ConfirmArrangement(planned))
        apply(ParentingCommand.CreateDraft(draft, child, "Piano practice", now.plusSeconds(4 * 3600), null, ""))
        apply(ParentingCommand.CreateDraft(done, child, "Swim class", now.minusSeconds(3600), null, ""))
        apply(ParentingCommand.ConfirmArrangement(done))
        apply(ParentingCommand.CompleteArrangement(done, id("done-record"), "Kicked without the board for the first time"))
        apply(ParentingCommand.CreateDraft(moved, child, "Drawing", now.plusSeconds(26 * 3600), null, ""))
        apply(ParentingCommand.ConfirmArrangement(moved))
        val movedItem = w.arrangements.first { it.id == moved }
        apply(
            ParentingCommand.ReviseArrangement(
                id("edit"), ParentingArrangementEditTarget(movedItem, w.revision), "Drawing", now.plusSeconds(50 * 3600), null,
                "Move to the day after tomorrow.", emptyList(),
            ),
        )
        apply(ParentingCommand.CreateDraft(cancelled, child, "Zoo trip", now.plusSeconds(72 * 3600), null, ""))
        apply(ParentingCommand.ConfirmArrangement(cancelled))
        apply(ParentingCommand.CancelArrangement(cancelled))
        apply(ParentingCommand.AddObservation(id("obs"), child, goal, "Finished a whole chapter on her own"))
        w
    }

    private fun scene(
        name: String,
        dark: Boolean,
        tab: ParentingTab = ParentingTab.Today,
        routes: List<ParentingRoute> = emptyList(),
        steps: () -> Unit = {},
    ) {
        if (!started) {
            started = true
            rule.setContent {
                val spec = current
                if (spec != null) key(spec.n) {
                    MimiTheme(palette = Palette.default, appearance = if (spec.dark) Appearance.Dark else Appearance.Light) {
                        ParentingHomeScreen(
                            preferences = ThemePreferences(),
                            onPreferencesChange = {},
                            workspace = workspace,
                            loaded = true,
                            error = null,
                            onboardingDone = true,
                            privacyShield = false,
                            undoSeconds = null,
                            versionName = "0.1.0",
                            versionCode = 1,
                            actions = ParentingActions.None,
                            onCompleteOnboarding = {},
                            onClearData = {},
                            onSignOut = {},
                            initialTab = spec.tab,
                            initialRoutes = spec.routes,
                            now = now,
                            zone = ZoneOffset.UTC,
                        )
                    }
                }
            }
        }
        rule.runOnIdle { current = Spec(captured.size + 1000 * (++sceneCounter), dark, tab, routes) }
        rule.waitForIdle()
        steps()
        rule.waitForIdle()
        capture("$name-${if (dark) "dark" else "light"}")
    }

    private class Spec(val n: Int, val dark: Boolean, val tab: ParentingTab, val routes: List<ParentingRoute>)

    private var started = false
    private var sceneCounter = 0
    private var current by mutableStateOf<Spec?>(null)
    private val captured = linkedMapOf<String, String>()

    private fun capture(key: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        File("build/screenshots").apply { mkdirs() }.resolve("populated-$key.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        captured[key] = fingerprint(bitmap)
    }

    private fun runAll(dark: Boolean) {
        scene("today", dark)
        scene("growth", dark, ParentingTab.Growth)
        scene("growth-timeline", dark, ParentingTab.Growth, listOf(ParentingRoute.GrowthTimeline(null)))
        scene("growth-goals", dark, ParentingTab.Growth, listOf(ParentingRoute.GrowthGoals))
        scene("goal-review", dark, ParentingTab.Growth, listOf(ParentingRoute.GrowthGoals, ParentingRoute.GoalReview(goal)))
        scene("family", dark, ParentingTab.Family)
        scene("child-detail", dark, ParentingTab.Family, listOf(ParentingRoute.ChildDetail(child)))
        scene("all-arrangements", dark, ParentingTab.Today, listOf(ParentingRoute.TodayAll))
        scene("adjust-form", dark, ParentingTab.Today, listOf(ParentingRoute.TodayAll)) {
            rule.onNodeWithTag("parenting.adjust.$planned").performClick()
        }
        scene("complete-form", dark, ParentingTab.Today, listOf(ParentingRoute.TodayAll)) {
            rule.onNodeWithTag("parenting.complete.$planned").performClick()
        }
    }

    @Test fun populatedStatesMatchTheirBaselinesInLightAndDark() {
        runAll(dark = false)
        runAll(dark = true)
        val file = File("src/test/resources/$RESOURCE")
        if (System.getenv("MIMI_RECORD_BASELINES") == "1") {
            file.parentFile.mkdirs()
            file.writeText(
                captured.entries.joinToString(",\n", "{\n", "\n}\n") { (k, v) -> "  \"$k\": \"$v\"" },
            )
            return
        }
        val stored = checkNotNull(javaClass.classLoader!!.getResourceAsStream(RESOURCE)) { "missing $RESOURCE; record it first" }
            .bufferedReader().readText()
            .let { text -> Regex("\"([^\"]+)\": \"([0-9a-f]+)\"").findAll(text).associate { it.groupValues[1] to it.groupValues[2] } }
        assertTrue("baseline keys differ: ${stored.keys.symmetricDifference(captured.keys)}", stored.keys == captured.keys)
        val failures = captured.mapNotNull { (k, v) ->
            val (mean, max) = distance(stored.getValue(k), v)
            if (mean > MEAN_LIMIT || max > MAX_LIMIT) "$k mean=%.2f max=%d".format(mean, max) else null
        }
        assertTrue("screenshots drifted from baselines: $failures", failures.isEmpty())
    }

    private fun <T> Set<T>.symmetricDifference(o: Set<T>) = (this - o) + (o - this)

    companion object {
        const val RESOURCE = "screenshot-baselines.json"
        private const val COLS = 24
        private const val ROWS = 48
        private const val MEAN_LIMIT = 0.6
        private const val MAX_LIMIT = 6

        fun fingerprint(bitmap: Bitmap): String {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            val sb = StringBuilder()
            for (r in 0 until ROWS) for (c in 0 until COLS) {
                val x0 = c * w / COLS
                val x1 = (c + 1) * w / COLS
                val y0 = r * h / ROWS
                val y1 = (r + 1) * h / ROWS
                var sum = 0L
                var n = 0
                for (y in y0 until y1) for (x in x0 until x1) {
                    val p = pixels[y * w + x]
                    sum += (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    n += 1
                }
                sb.append(((sum / maxOf(n, 1)).toInt() shr 4).toString(16))
            }
            return sb.toString()
        }

        fun distance(a: String, b: String): Pair<Double, Int> {
            if (a.length != b.length) return 99.0 to 99
            var total = 0
            var max = 0
            for (i in a.indices) {
                val d = abs(a[i].digitToInt(16) - b[i].digitToInt(16))
                total += d
                if (d > max) max = d
            }
            return total.toDouble() / a.length to max
        }
    }
}

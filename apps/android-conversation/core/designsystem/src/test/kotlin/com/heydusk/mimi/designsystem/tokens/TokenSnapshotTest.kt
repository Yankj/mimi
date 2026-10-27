package com.heydusk.mimi.designsystem.tokens

import com.heydusk.mimi.domain.theme.Palette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用 docs/design/mimi-theme-tokens.json（scripts/export-theme-tokens.py 从 iOS 的 CatTheme.swift 导出）
 * 逐值校验 Kotlin 的 [ThemeTokens]。这是对手工移植的独立核对：任何一端改值而另一端没跟上，这里就会失败。
 */
class TokenSnapshotTest {
    private val root: JsonObject = run {
        // Gradle 的单测工作目录是模块目录：apps/android-conversation/core/designsystem
        val file = File("../../../../docs/design/mimi-theme-tokens.json")
        assertTrue("snapshot missing: ${file.absolutePath}", file.isFile)
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun hex(p: JsonPrimitive): Int = p.content.removePrefix("#").toInt(16)

    private val combos = mapOf(
        "C-day" to (Palette.InkWhite to Mode.Light), "C-night" to (Palette.InkWhite to Mode.Dark),
        "B-day" to (Palette.SoftBlush to Mode.Light), "B-night" to (Palette.SoftBlush to Mode.Dark),
    )

    @Test
    fun logoOrangeMatches() {
        assertEquals(ThemeTokens.LOGO_ORANGE, hex(root.getValue("logoOrange").jsonPrimitive))
    }

    @Test
    fun everyRoleKidAndButtonSpecMatchesTheSwiftSource() {
        val roleByName: Map<String, ColorRole> = mapOf(
            "canvas" to ColorRole.Canvas, "card" to ColorRole.Card, "surface" to ColorRole.Surface,
            "hairline" to ColorRole.Hairline, "track" to ColorRole.Track, "ink" to ColorRole.Ink,
            "ink2" to ColorRole.Ink2, "ink3" to ColorRole.Ink3, "accent" to ColorRole.Accent,
            "accentOn" to ColorRole.AccentOn, "accentOnPressed" to ColorRole.AccentOnPressed,
            "orangeText" to ColorRole.OrangeText, "success" to ColorRole.Success, "successFill" to ColorRole.SuccessFill,
            "attention" to ColorRole.Attention, "attentionFill" to ColorRole.AttentionFill,
            "danger" to ColorRole.Danger, "dangerFill" to ColorRole.DangerFill,
        )
        val snapshotCombos = root.getValue("combos").jsonObject
        assertEquals(combos.keys, snapshotCombos.keys)
        for ((name, pm) in combos) {
            val set = ThemeTokens.of(pm.first).set(pm.second)
            val entry = snapshotCombos.getValue(name).jsonObject
            val roles = entry.getValue("roles").jsonObject
            assertEquals("$name role names", roleByName.keys, roles.keys)
            assertEquals("every ColorRole is covered", ColorRole.entries.toSet(), roleByName.values.toSet())
            for ((roleName, role) in roleByName) {
                assertEquals("$name $roleName", hex(roles.getValue(roleName).jsonPrimitive), set.hex(role))
            }
            assertEquals("$name kids", entry.getValue("kids").jsonArray.map { hex(it.jsonPrimitive) }, set.kids)

            val button = entry.getValue("button").jsonObject
            fun b(key: String) = button.getValue(key).jsonPrimitive.double
            assertEquals("$name button.fill", b("fill"), set.button.fill, 0.0)
            assertEquals("$name button.fillPressed", b("fillPressed"), set.button.fillPressed, 0.0)
            assertEquals("$name button.border", b("border"), set.button.border, 0.0)
            assertEquals("$name button.borderPressed", b("borderPressed"), set.button.borderPressed, 0.0)
            assertEquals("$name button.baseAlpha", b("baseAlpha"), set.button.baseAlpha, 0.0)
            // voiceHold* 在 Swift 里是默认值，快照里没有；Kotlin 侧的默认值由 ThemeTokensTest 钉住。
            val tab = entry.getValue("tabSelectedFill").jsonObject
            assertEquals("$name tab hex", hex(tab.getValue("hex").jsonPrimitive), set.tabSelectedFill.hex)
            assertEquals("$name tab alpha", tab.getValue("alpha").jsonPrimitive.double, set.tabSelectedFill.alpha, 0.0)
        }
    }

    @Test
    fun washMatchesTheSwiftSource() {
        val snapshotCombos = root.getValue("combos").jsonObject
        for ((name, pm) in combos) {
            val wash = ThemeTokens.of(pm.first).set(pm.second).wash
            val snap = snapshotCombos.getValue(name).jsonObject.getValue("wash")
            if (wash == null) {
                assertTrue("$name wash must be null", snap !is JsonObject)
                continue
            }
            val obj = snap.jsonObject
            assertEquals("$name wash top", hex(obj.getValue("topHex").jsonPrimitive), wash.topHex)
            assertEquals("$name wash fade", obj.getValue("fadeEnd").jsonPrimitive.double, wash.fadeEnd, 0.0)
            val spots = obj.getValue("spots").jsonArray
            assertEquals("$name spot count", spots.size, wash.spots.size)
            spots.forEachIndexed { i, s ->
                val o = s.jsonObject
                val k = wash.spots[i]
                assertEquals(hex(o.getValue("hex").jsonPrimitive), k.hex)
                assertEquals(o.getValue("alpha").jsonPrimitive.double, k.alpha, 0.0)
                assertEquals(o.getValue("centerX").jsonPrimitive.double, k.centerX, 0.0)
                assertEquals(o.getValue("centerY").jsonPrimitive.double, k.centerY, 0.0)
                assertEquals(o.getValue("radiusX").jsonPrimitive.double, k.radiusX, 0.0)
                assertEquals(o.getValue("radiusY").jsonPrimitive.double, k.radiusY, 0.0)
            }
        }
        assertNull(ThemeTokens.InkWhite.light.wash)
    }

    @Test
    fun tabSelectedStyleMatches() {
        val styles = root.getValue("tabSelectedStyle").jsonObject
        assertEquals("neutral", styles.getValue("C").jsonPrimitive.content)
        assertEquals("orangeTint", styles.getValue("B").jsonPrimitive.content)
        assertEquals(TabSelectedStyle.Neutral, ThemeTokens.InkWhite.tabSelectedStyle)
        assertEquals(TabSelectedStyle.OrangeTint, ThemeTokens.SoftBlush.tabSelectedStyle)
    }
}

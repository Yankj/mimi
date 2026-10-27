package com.heydusk.mimi.domain.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePreferencesTest {
    @Test
    fun defaultsAreInkWhiteAndFollowSystem() {
        val prefs = ThemePreferences()
        assertEquals(Palette.InkWhite, prefs.palette)
        assertEquals(Appearance.System, prefs.appearance)
        assertEquals("C", Palette.InkWhite.letter)
        assertEquals("B", Palette.SoftBlush.letter)
    }

    @Test
    fun preferenceKeysMatchIos() {
        assertEquals("palette", Palette.PREFERENCE_KEY)
        assertEquals("theme", Appearance.PREFERENCE_KEY)
        assertEquals(listOf("inkWhite", "softBlush"), Palette.entries.map { it.rawValue })
        assertEquals(listOf("system", "light", "dark"), Appearance.entries.map { it.rawValue })
    }

    @Test
    fun unknownRawValuesFallBackToDefaults() {
        assertEquals(Palette.InkWhite, Palette.fromRaw(null))
        assertEquals(Palette.InkWhite, Palette.fromRaw("nope"))
        assertEquals(Palette.SoftBlush, Palette.fromRaw("softBlush"))
        assertEquals(Appearance.System, Appearance.fromRaw("???"))
        assertEquals(Appearance.Dark, Appearance.fromRaw("dark"))
    }

    @Test
    fun appearanceResolvesAgainstSystem() {
        assertTrue(Appearance.System.resolveDark(true))
        assertFalse(Appearance.System.resolveDark(false))
        assertFalse(Appearance.Light.resolveDark(true))
        assertTrue(Appearance.Dark.resolveDark(false))
    }
}

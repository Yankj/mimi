package com.heydusk.mimi.domain.theme

/**
 * 配色（与 iOS `CatPalette` 对齐）：与外观（浅色/深色）是两条互相独立的轴。
 * 偏好键 "palette"，缺省 [InkWhite]（C 素白墨黑）。
 */
enum class Palette(val rawValue: String, val letter: String) {
    /** C 素白墨黑，默认。 */
    InkWhite("inkWhite", "C"),

    /** B 雾粉燕麦。 */
    SoftBlush("softBlush", "B");

    companion object {
        const val PREFERENCE_KEY = "palette"
        val default: Palette = InkWhite

        /** 未知或缺失的值回落到默认，与 iOS `CatPalette(rawValue:) ?? .inkWhite` 一致。 */
        fun fromRaw(raw: String?): Palette = entries.firstOrNull { it.rawValue == raw } ?: default
    }
}

/**
 * 外观（与 iOS `CatAppearance` 对齐）。偏好键 "theme"，缺省 [System]（跟随系统）。
 */
enum class Appearance(val rawValue: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    /** 给定系统是否处于深色时，最终是否使用深色。 */
    fun resolveDark(systemIsDark: Boolean): Boolean = when (this) {
        System -> systemIsDark
        Light -> false
        Dark -> true
    }

    companion object {
        const val PREFERENCE_KEY = "theme"
        val default: Appearance = System

        fun fromRaw(raw: String?): Appearance = entries.firstOrNull { it.rawValue == raw } ?: default
    }
}

/** 主题偏好的值对象；M1 起由 DataStore 持久化。 */
data class ThemePreferences(
    val palette: Palette = Palette.default,
    val appearance: Appearance = Appearance.default,
) {
    companion object {
        fun fromRaw(palette: String?, appearance: String?) =
            ThemePreferences(Palette.fromRaw(palette), Appearance.fromRaw(appearance))
    }
}

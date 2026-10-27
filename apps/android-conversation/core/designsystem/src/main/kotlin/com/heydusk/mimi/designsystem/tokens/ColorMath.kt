package com.heydusk.mimi.designsystem.tokens


/**
 * sRGB 颜色（0xRRGGBB）加透明度。纯值类型，不依赖任何 UI 框架，与 iOS `CatRGBA` 一一对应。
 */
data class Rgba(val hex: Int, val alpha: Double = 1.0) {
    val red: Double get() = ((hex shr 16) and 255) / 255.0
    val green: Double get() = ((hex shr 8) and 255) / 255.0
    val blue: Double get() = (hex and 255) / 255.0

    /**
     * 叠加到**不透明**背景上（source-over）。结果不透明，每个通道四舍五入到 8 位（遇 .5 取偶，
     * 与 Swift `.toNearestOrEven` 一致），因此与提案里公布的 hex 相等。
     */
    fun composited(over: Rgba): Rgba {
        fun mix(fg: Int, back: Int): Int = Math.rint(fg * alpha + back * (1 - alpha)).toInt()
        val r = mix((hex shr 16) and 255, (over.hex shr 16) and 255)
        val g = mix((hex shr 8) and 255, (over.hex shr 8) and 255)
        val b = mix(hex and 255, over.hex and 255)
        return Rgba((r shl 16) or (g shl 8) or b)
    }
}

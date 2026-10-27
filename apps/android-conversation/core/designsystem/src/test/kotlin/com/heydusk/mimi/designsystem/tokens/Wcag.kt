package com.heydusk.mimi.designsystem.tokens

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x 相对亮度/对比度。有意只放在测试里、独立实现：生产的令牌代码不能用自己的算术来验证自己。
 */
object Wcag {
    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(hex: Int): Double =
        0.2126 * linear((hex shr 16) and 255) + 0.7152 * linear((hex shr 8) and 255) + 0.0722 * linear(hex and 255)

    fun contrast(a: Int, b: Int): Double {
        val hi = max(luminance(a), luminance(b))
        val lo = min(luminance(a), luminance(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    fun hexString(v: Int): String = "#%06X".format(v)
}

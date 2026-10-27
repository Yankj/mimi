package com.heydusk.mimi.domain.json

import java.math.BigDecimal
import java.security.MessageDigest

/**
 * 确定性的协议编码（iOS `ConversationCanonicalJSON` 用 JavaScriptCore 跑一个固定函数；这里直接用 JVM 实现同一语义，
 * 不需要脚本引擎）。语义就是 JS 的 `JSON.stringify(JSON.parse(raw))` 且对象键按 UTF-16 单元排序：
 * - 字符串按 JSON.stringify 转义（`\b \f \n \r \t \" \\`，其余 < 0x20 为小写 `\u00xx`，孤立代理项为小写 `\udxxx`，其余原样）；
 * - 数字按 ECMAScript Number::toString（最短往返；整数不带小数点；±0 → "0"；≥ 1e21 或 < 1e-6 用指数）；
 * - 先过 [StrictJson]（拒绝重复键等），再编码。
 */
object CanonicalJson {
    const val DEFAULT_MAX_BYTES = 16_000

    @Throws(StrictJsonException::class)
    fun data(input: ByteArray, maxBytes: Int = DEFAULT_MAX_BYTES): ByteArray {
        val root = StrictJson.parseObject(input, maxBytes)
        return stringify(root).toByteArray(Charsets.UTF_8)
    }

    @Throws(StrictJsonException::class)
    fun hash(input: ByteArray, maxBytes: Int = DEFAULT_MAX_BYTES): String = sha256Hex(data(input, maxBytes))

    /** 把已经解析好的值树编码成规范文本。 */
    fun stringify(value: JsonValue): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Num -> out.append(jsNumber(value.value))
            is JsonValue.Str -> quote(value.value, out)
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { i, v -> if (i > 0) out.append(','); write(v, out) }
                out.append(']')
            }
            is JsonValue.Obj -> {
                out.append('{')
                value.members.keys.sorted().forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    quote(k, out); out.append(':'); write(value.members.getValue(k), out)
                }
                out.append('}')
            }
        }
    }

    /** `JSON.stringify(string)`（well-formed JSON.stringify：孤立代理项转义）。 */
    fun quote(s: String, out: StringBuilder) {
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 -> out.append("\\u").append(String.format("%04x", c.code))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    out.append(c).append(s[i + 1]); i++
                }
                Character.isSurrogate(c) -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    /** ECMAScript `Number::toString(10)`。 */
    fun jsNumber(v: Double): String {
        if (v == 0.0) return "0"
        val negative = v < 0
        // ECMAScript Number::toString：取能往返的最少有效位，同样位数里取最接近的。
        // （不能用 Double.toString：它对 5e-324 这类次正规数会给 4.9E-324。）
        val exact = BigDecimal(kotlin.math.abs(v))
        var bd = exact
        for (precision in 1..17) {
            val candidate = exact.round(java.math.MathContext(precision, java.math.RoundingMode.HALF_EVEN))
            if (candidate.toDouble() == kotlin.math.abs(v)) { bd = candidate; break }
        }
        bd = bd.stripTrailingZeros()
        val digits = bd.unscaledValue().toString()
        val k = digits.length
        val n = k - bd.scale() // 十进制点位置：值 = 0.digits × 10^n
        val sb = StringBuilder()
        if (negative) sb.append('-')
        when {
            n in k..21 -> sb.append(digits).append("0".repeat(n - k))
            n in 1..21 -> sb.append(digits, 0, n).append('.').append(digits, n, k)
            n in -5..0 -> sb.append("0.").append("0".repeat(-n)).append(digits)
            else -> {
                val e = n - 1
                sb.append(digits[0])
                if (k > 1) sb.append('.').append(digits, 1, k)
                sb.append('e').append(if (e < 0) '-' else '+').append(kotlin.math.abs(e))
            }
        }
        return sb.toString()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format("%02x", it) }
}

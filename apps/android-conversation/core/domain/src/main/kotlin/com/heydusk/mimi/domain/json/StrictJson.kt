package com.heydusk.mimi.domain.json

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** JSON 值树（保留数字原文，解析不丢精度）。 */
sealed interface JsonValue {
    data class Obj(val members: LinkedHashMap<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val raw: String, val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

class StrictJsonException(message: String) : Exception(message)

/**
 * 解码前的有界结构校验（iOS `ConversationStrictJSON`）：
 * 顶层必须是对象；拒绝重复键（包括转义后相同的键，按解码后的 UTF-16 单元比较，不做 Unicode 规范化）、
 * 嵌套深度 > 64、非法转义、字符串里的控制字符、非有限数字、尾随内容、非法 UTF-8。
 */
object StrictJson {
    const val DEFAULT_MAX_BYTES = 1_500_000
    const val MAX_DEPTH = 64

    @Throws(StrictJsonException::class)
    fun parseObject(data: ByteArray, maxBytes: Int = DEFAULT_MAX_BYTES): JsonValue.Obj {
        if (data.isEmpty() || data.size > maxBytes) throw StrictJsonException("invalid_event")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw StrictJsonException("invalid_event")
        }
        val scanner = Scanner(text)
        val value = scanner.value(0)
        scanner.space()
        if (scanner.index != text.length || value !is JsonValue.Obj) throw StrictJsonException("invalid_event")
        return value
    }

    private class Scanner(val s: String) {
        var index = 0

        fun fail(): Nothing = throw StrictJsonException("invalid_event")

        fun space() {
            while (index < s.length && (s[index] == '\t' || s[index] == '\n' || s[index] == '\r' || s[index] == ' ')) index++
        }

        fun take(c: Char): Boolean {
            if (index < s.length && s[index] == c) { index++; return true }
            return false
        }

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail()
            space()
            if (index >= s.length) fail()
            return when (s[index]) {
                '{' -> obj(depth + 1)
                '[' -> arr(depth + 1)
                '"' -> JsonValue.Str(string())
                't' -> { literal("true"); JsonValue.Bool(true) }
                'f' -> { literal("false"); JsonValue.Bool(false) }
                'n' -> { literal("null"); JsonValue.Null }
                else -> number()
            }
        }

        fun obj(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail()
            index++
            space()
            val members = LinkedHashMap<String, JsonValue>()
            if (take('}')) return JsonValue.Obj(members)
            while (true) {
                space()
                val key = string()
                if (members.containsKey(key)) fail()
                space()
                if (!take(':')) fail()
                members[key] = value(depth)
                space()
                if (take('}')) return JsonValue.Obj(members)
                if (!take(',')) fail()
            }
        }

        fun arr(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail()
            index++
            space()
            val items = mutableListOf<JsonValue>()
            if (take(']')) return JsonValue.Arr(items)
            while (true) {
                items.add(value(depth))
                space()
                if (take(']')) return JsonValue.Arr(items)
                if (!take(',')) fail()
            }
        }

        fun string(): String {
            if (!take('"')) fail()
            val sb = StringBuilder()
            while (index < s.length) {
                val c = s[index++]
                if (c == '"') {
                    // 原始字节已按 UTF-8 校验；只有 \uXXXX 转义可能带来不成对的代理项，iOS 解码成 String 时同样拒绝。
                    for (i in 0 until sb.length) {
                        val ch = sb[i]
                        if (Character.isHighSurrogate(ch)) { if (i + 1 >= sb.length || !Character.isLowSurrogate(sb[i + 1])) fail() }
                        else if (Character.isLowSurrogate(ch) && (i == 0 || !Character.isHighSurrogate(sb[i - 1]))) fail()
                    }
                    return sb.toString()
                }
                if (c.code < 32) fail()
                if (c == '\\') {
                    if (index >= s.length) fail()
                    val e = s[index++]
                    when (e) {
                        '"' -> sb.append('"')
                        '/' -> sb.append('/')
                        '\\' -> sb.append('\\')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (index + 4 > s.length) fail()
                            var code = 0
                            for (k in 0 until 4) {
                                val d = Character.digit(s[index + k], 16)
                                if (d < 0 || s[index + k].code > 127) fail()
                                code = code * 16 + d
                            }
                            index += 4
                            sb.append(code.toChar())
                        }
                        else -> fail()
                    }
                } else {
                    sb.append(c)
                }
            }
            fail()
        }

        fun digits() {
            while (index < s.length && s[index] in '0'..'9') index++
        }

        fun number(): JsonValue {
            val start = index
            take('-')
            if (index >= s.length) fail()
            if (take('0')) Unit else {
                if (s[index] !in '1'..'9') fail()
                digits()
            }
            if (take('.')) {
                val d = index; digits(); if (index == d) fail()
            }
            if (take('e') || take('E')) {
                if (!take('+')) take('-')
                val d = index; digits(); if (index == d) fail()
            }
            val raw = s.substring(start, index)
            val v = raw.toDoubleOrNull() ?: fail()
            if (!v.isFinite()) fail()
            return JsonValue.Num(raw, v)
        }

        fun literal(expected: String) {
            if (!s.startsWith(expected, index)) fail()
            index += expected.length
        }
    }
}

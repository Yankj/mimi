package com.heydusk.mimi.domain.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 对应 iOS `ConversationStrictJSONTests` 的全部场景（有界对象、转义键、畸形字节）。 */
class StrictJsonTest {
    private fun obj(s: String, max: Int = StrictJson.DEFAULT_MAX_BYTES) = StrictJson.parseObject(s.toByteArray(Charsets.UTF_8), max)
    private fun rejects(raw: String) = assertFailsWith<StrictJsonException>(raw) { obj(raw) }

    @Test fun acceptsBoundedObjectsAndEscapedKeys() {
        val good = """{"a":[null,true,false,-2.5e+3,{"b":"中文 😺 \"quote\" \u2028"}],"x":{"a":1},"y":{"a":2}}"""
        assertEquals(3, obj(good).members.size)
        // 这是两个不同的 JSON 名字，即使 Swift 字符串相等会做规范化。
        assertEquals(2, obj("""{"e\u0301":1,"\u00e9":2}""").members.size)
        val legalDepth = "{\"a\":".repeat(64) + "0" + "}".repeat(64)
        assertTrue(obj(legalDepth).members.containsKey("a"))
    }

    @Test fun rejectsMalformedAndAmbiguousInput() {
        for (raw in listOf(
            """{"a":1,"a":2}""", """{"a":1,"\u0061":2}""", """{"x":{"a":1,"a":2}}""", """{"x":[{"a":1,"a":2}]}""",
            """{"a":01}""", """{"a":1.}""", """{"a":1e}""", """{"a":+1}""", """{"a":true,}""", """{"a":[1,]}""",
            """{"a":"\x22"}""", """{"a":"unterminated}""", "{\"a\":\"\n\"}", "{\"a\":null}{}", "[]",
            "{\"a\":".repeat(65) + "0" + "}".repeat(65),
        )) rejects(raw)
    }

    @Test fun rejectsInvalidUtf8LoneSurrogatesNonFiniteAndOversize() {
        assertFailsWith<StrictJsonException> { StrictJson.parseObject(byteArrayOf(123, 34, 97, 34, 58, 34, -1, 34, 125)) }
        rejects("""{"a":"\ud800"}""")
        rejects("""{"a":1e9999}""")
        assertFailsWith<StrictJsonException> { obj("""{"a":[null,true,false,-2.5e+3]}""", max = 10) }
        assertFailsWith<StrictJsonException> { StrictJson.parseObject(ByteArray(0)) }
    }
}

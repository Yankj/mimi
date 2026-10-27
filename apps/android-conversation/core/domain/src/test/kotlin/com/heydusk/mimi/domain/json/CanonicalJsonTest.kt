package com.heydusk.mimi.domain.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 对应 iOS 的 canonical JSON 黄金用例。`memory-canonical-v1.json` 是从 iOS 测试资源原样复制的向量文件，
 * 里面的期望值来自维护中的 Node 服务端编码器（ECMAScript `JSON.stringify` 语义），所以这里是在对服务端字节做交叉验证。
 */
class CanonicalJsonTest {
    private val golden: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/memory-canonical-v1.json")) { "fixture missing" }.readBytes().toString(Charsets.UTF_8),
    ) as JsonObject

    private fun rows(key: String) = (golden.getValue(key) as JsonArray).map { it as JsonObject }
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    @Test fun goldenCasesMatchTheServiceEncoderByteForByte() {
        val cases = rows("cases")
        assertEquals(5, cases.size)
        for (row in cases) {
            val actual = CanonicalJson.data(row.str("raw").toByteArray())
            assertEquals(row.str("canonical"), actual.toString(Charsets.UTF_8), "canonical bytes: ${row.str("name")}")
            assertEquals(row.str("sha256"), CanonicalJson.sha256Hex(actual), "sha256: ${row.str("name")}")
        }
        val numeric = cases.first { it.str("name") == "recorded_memory_numeric_request" }
        assertEquals(golden.str("original_numeric_request_hash").length, 64)
        assertEquals(numeric.str("sha256"), CanonicalJson.hash(numeric.str("raw").toByteArray()))
    }

    @Test fun invalidInputIsRejectedBeforeEncoding() {
        val invalid = rows("invalid")
        assertEquals(6, invalid.size)
        for (row in invalid) {
            val max = row["max_bytes"]?.jsonPrimitive?.int ?: CanonicalJson.DEFAULT_MAX_BYTES
            assertFailsWith<StrictJsonException>(row.str("name")) { CanonicalJson.data(row.str("raw").toByteArray(), max) }
        }
    }

    @Test fun numbersFollowEcmaScriptNumberToString() {
        fun canon(v: String) = CanonicalJson.data("""{"n":$v}""".toByteArray()).toString(Charsets.UTF_8)
        assertEquals("""{"n":1}""", canon("1.0"))
        assertEquals("""{"n":0}""", canon("-0"))
        assertEquals("""{"n":100000000000000000000}""", canon("1e20"))
        assertEquals("""{"n":1e+21}""", canon("1e21"))
        assertEquals("""{"n":0.000001}""", canon("0.000001"))
        assertEquals("""{"n":1e-7}""", canon("0.0000001"))
        assertEquals("""{"n":0.1}""", canon("0.10"))
        assertEquals("""{"n":-2500}""", canon("-2.5e+3"))
        assertEquals("""{"n":5e-324}""", canon("5e-324"))
        assertEquals("""{"n":1.7976931348623157e+308}""", canon("1.7976931348623157e308"))
    }

    @Test fun keysSortByUtf16UnitsAndStringsEscapeLikeJsonStringify() {
        // U+FFFF (BMP) sorts after U+1F600 (surrogate pair D83D…) in UTF-16 order — the opposite of code-point order.
        val out = CanonicalJson.data("{\"\uFFFF\":1,\"\uD83D\uDE00\":2,\"b\":3,\"B\":4}".toByteArray()).toString(Charsets.UTF_8)
        assertEquals("{\"B\":4,\"b\":3,\"\uD83D\uDE00\":2,\"\uFFFF\":1}", out)
        assertEquals("{\"s\":\"\\u0001\\b\\f\\n\\r\\t\\\"\\\\\u007f\u2028é\"}", CanonicalJson.data("{\"s\":\"\\u0001\\b\\f\\n\\r\\t\\\"\\\\\\u007f\\u2028\\u00e9\"}".toByteArray()).toString(Charsets.UTF_8))
        assertTrue(CanonicalJson.hash("{}".toByteArray()).length == 64)
    }
}

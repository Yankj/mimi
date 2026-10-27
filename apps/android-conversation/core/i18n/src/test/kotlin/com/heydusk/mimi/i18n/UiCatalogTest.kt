package com.heydusk.mimi.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class UiCatalogTest {
    private val shared = File("../../../ios-conversation/Resources/UIStrings.json")
    private val supplement = File("src/main/assets/UIStringsAndroid.json")
    private val placeholder = Regex("""\{([0-9]+)\}""")

    @Test
    fun substitutesPlaceholdersLikeIos() {
        assertEquals("a-1-b-2", UiCatalog.substitute("a-{0}-b-{1}", listOf("1", "2")))
        assertEquals("a-{1}", UiCatalog.substitute("a-{1}", listOf("x")))
        assertEquals("x{0}x", UiCatalog.substitute("x{0}x", emptyList()))
        assertEquals("{0}{0}", UiCatalog.substitute("{0}{0}", emptyList()))
        assertEquals("zz", UiCatalog.substitute("{0}{0}", listOf("z")))
    }

    @Test
    fun fallsBackToTheKeyWhenMissing() {
        val catalog = UiCatalog.parse("""{"你好 {0}":{"en":"Hello {0}","zh-Hans":"你好 {0}"}, "只有中文":{"zh-Hans":"只有中文"}}""")
        assertEquals("Hello Kun", catalog.text("你好 {0}", AppLanguage.English, "Kun"))
        assertEquals("你好 Kun", catalog.text("你好 {0}", AppLanguage.Chinese, "Kun"))
        assertEquals("只有中文", catalog.text("只有中文", AppLanguage.English))
        assertEquals("没有这个键", catalog.text("没有这个键", AppLanguage.English))
        // 缺键时回落为键本身，占位符照样替换（与 iOS 一致）。
        assertEquals("未知 7", catalog.text("未知 {0}", AppLanguage.English, "7"))
    }

    @Test
    fun languageFollowsLocalePrefix() {
        assertEquals(AppLanguage.English, AppLanguage.fromLocale(Locale.forLanguageTag("en-US")))
        assertEquals(AppLanguage.English, AppLanguage.fromLocale(Locale.forLanguageTag("en")))
        assertEquals(AppLanguage.Chinese, AppLanguage.fromLocale(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals(AppLanguage.Chinese, AppLanguage.fromLocale(Locale.forLanguageTag("ja-JP")))
        assertEquals("zh-Hans", AppLanguage.Chinese.rawValue)
    }

    @Test
    fun sharedCatalogParsesAndIsConsistent() {
        assertTrue("UIStrings.json not found at ${shared.absolutePath}", shared.isFile)
        val catalog = UiCatalog.parse(shared.readText())
        assertTrue("expected >2000 keys, got ${catalog.entries.size}", catalog.entries.size > 2000)
        val problems = mutableListOf<String>()
        for ((key, langs) in catalog.entries) {
            val en = langs["en"]
            val zh = langs["zh-Hans"]
            if (en == null || zh == null) {
                problems += "缺语言: $key"
                continue
            }
            val enHolders = placeholder.findAll(en).map { it.value }.toSortedSet()
            val zhHolders = placeholder.findAll(zh).map { it.value }.toSortedSet()
            if (enHolders != zhHolders) problems += "占位符不一致: $key"
        }
        assertTrue(problems.take(10).joinToString("\n"), problems.isEmpty())
        // 与 M0 壳直接相关的键必须存在（不能静默回落成键本身）。
        assertEquals("Today", catalog.text("今天", AppLanguage.English))
        assertEquals("Growth", catalog.text("成长", AppLanguage.English))
        assertEquals("Our family", catalog.text("我们家", AppLanguage.English))
        assertEquals("Appearance", catalog.text("外观", AppLanguage.English))
    }

    @Test
    fun androidSupplementIsWellFormedAndNeverShadowsTheSharedCatalog() {
        val extra = UiCatalog.parse(supplement.readText())
        assertTrue(extra.entries.isNotEmpty())
        for ((key, langs) in extra.entries) {
            assertTrue("补充目录缺语言: $key", langs.keys.containsAll(listOf("en", "zh-Hans")))
        }
        val base = UiCatalog.parse(shared.readText())
        val merged = base.withSupplement(extra)
        // 共享目录优先：同名键用共享的值。
        for (key in base.entries.keys.intersect(extra.entries.keys)) {
            assertEquals(base.entries[key], merged.entries[key])
        }
        assertEquals(base.entries.size + extra.entries.keys.minus(base.entries.keys).size, merged.entries.size)
    }
}

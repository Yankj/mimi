package com.heydusk.mimi.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.util.Locale

/** 界面语言（与 iOS `AppLanguage` 对齐，原始值是目录里的语言键）。 */
enum class AppLanguage(val rawValue: String) {
    Chinese("zh-Hans"),
    English("en");

    companion object {
        /** 与 iOS 一致：locale 以 "en" 开头为英文，其余都是简体中文。 */
        fun fromLocale(locale: Locale): AppLanguage =
            if (locale.toLanguageTag().startsWith("en") || locale.language == "en") English else Chinese
    }
}

/**
 * UIStrings.json 的目录：键是中文原句（可含 {0} 占位符），值是 {语言 → 文案}。
 * 取词规则与 iOS `UICatalog.text` 相同：缺键或缺语言时回落为键本身；{n} 占位符按下标替换，缺参数时原样保留。
 */
class UiCatalog(val entries: Map<String, Map<String, String>>) {

    fun text(key: String, language: AppLanguage, vararg arguments: String): String {
        val template = entries[key]?.get(language.rawValue) ?: key
        return substitute(template, arguments.asList())
    }

    /** 以 [overrides] 补充；同一个键以 [this]（共享目录）为准。 */
    fun withSupplement(overrides: UiCatalog): UiCatalog = UiCatalog(overrides.entries + entries)

    companion object {
        private val placeholder = Regex("""\{([0-9]+)\}""")

        fun substitute(template: String, arguments: List<String>): String =
            placeholder.replace(template) { match ->
                arguments.getOrNull(match.groupValues[1].toInt()) ?: match.value
            }

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(stream: InputStream): UiCatalog = parse(stream.readBytes().toString(Charsets.UTF_8))

        fun parse(text: String): UiCatalog {
            val root: JsonObject = json.parseToJsonElement(text).jsonObject
            return UiCatalog(
                root.mapValues { (_, value) ->
                    value.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }
                },
            )
        }

        val Empty = UiCatalog(emptyMap())
    }
}

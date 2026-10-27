package com.heydusk.mimi.domain.language

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

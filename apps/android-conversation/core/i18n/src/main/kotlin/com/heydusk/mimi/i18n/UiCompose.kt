package com.heydusk.mimi.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.heydusk.mimi.domain.language.AppLanguage
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** 界面语言；缺省跟随系统（只区分英文 / 简体中文，与 iOS 一致）。设置页上线后由偏好覆盖。 */
val LocalAppLanguage = compositionLocalOf<AppLanguage?> { null }

@Composable
fun currentAppLanguage(): AppLanguage =
    LocalAppLanguage.current ?: AppLanguage.fromLocale(LocalConfiguration.current.locales[0])

/** 等价于 iOS 的 `language.ui(key, arguments:)`：用中文原句做键，查共享目录。 */
@Composable
fun ui(key: String, vararg arguments: String): String {
    val language = currentAppLanguage()
    val catalog = UiStrings.catalog(LocalContext.current)
    return catalog.text(key, language, *arguments)
}

package com.heydusk.mimi.i18n

import android.content.Context

/**
 * 进程内的文案目录：assets 里的 UIStrings.json（构建时从 iOS 的 Resources 同步）加上
 * Android 补充目录 UIStringsAndroid.json（共享目录里还没有的 Android 专有文案；共享目录优先）。
 * 找不到资源时返回空目录——和 iOS 一样，宁可显示中文键，也不崩溃。
 */
object UiStrings {
    private const val SHARED = "UIStrings.json"
    private const val SUPPLEMENT = "UIStringsAndroid.json"

    @Volatile
    private var catalog: UiCatalog? = null

    fun catalog(context: Context): UiCatalog =
        catalog ?: synchronized(this) {
            catalog ?: load(context).also { catalog = it }
        }

    private fun load(context: Context): UiCatalog {
        val assets = context.applicationContext.assets
        fun read(name: String): UiCatalog =
            runCatching { assets.open(name).use(UiCatalog::parse) }.getOrDefault(UiCatalog.Empty)
        return read(SHARED).withSupplement(read(SUPPLEMENT))
    }

    /** 测试或预览中直接注入目录。 */
    fun install(catalog: UiCatalog) {
        this.catalog = catalog
    }
}

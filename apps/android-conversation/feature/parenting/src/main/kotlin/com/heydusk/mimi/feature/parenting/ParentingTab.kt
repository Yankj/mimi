package com.heydusk.mimi.feature.parenting

import androidx.compose.ui.graphics.vector.ImageVector
import com.heydusk.mimi.designsystem.MimiIcons

/**
 * 三个主 Tab：今天｜成长｜我们家。`id` 与 iOS 的 `ParentingTab.rawValue` 相同，
 * 测试标签 `parenting.tab.<id>` 也与 iOS 的 accessibilityIdentifier 相同。
 */
enum class ParentingTab(val id: String, val titleKey: String, val icon: ImageVector) {
    Today("today", "今天", MimiIcons.Sun),
    Growth("growth", "成长", MimiIcons.Leaf),
    Family("family", "我们家", MimiIcons.House);

    val tag: String get() = "parenting.tab.$id"

    companion object {
        const val TAB_BAR_TAG = "parenting.tabBar"
    }
}

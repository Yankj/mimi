package com.heydusk.mimi.feature.parenting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.LocalReduceTransparency
import com.heydusk.mimi.designsystem.mimiBarBlurStyle
import com.heydusk.mimi.designsystem.toColor
import com.heydusk.mimi.i18n.ui
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * 悬浮底栏：玻璃胶囊（Haze 背景模糊 + 85% 卡片色，降低透明度时为不透明卡片色），悬在滚动页面之上。
 * 选中项：C 为中性胶囊，B 为橙色着色胶囊（由令牌的 `tabSelectedFill` 决定）；图标用 accentOn。
 */
@Composable
fun ParentingTabBar(
    selected: ParentingTab,
    onSelect: (ParentingTab) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    val reduce = LocalReduceTransparency.current
    val barModifier = if (reduce) {
        Modifier.background(colors.card, CircleShape)
    } else {
        Modifier
            .hazeBlur(input = HazeInput.Sources(hazeState), style = mimiBarBlurStyle(colors))
            .background(colors.card.copy(alpha = 0.85f), CircleShape)
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .then(barModifier)
            .border(1.dp, colors.hairline, CircleShape)
            .padding(5.dp)
            .semantics { testTag = ParentingTab.TAB_BAR_TAG },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ParentingTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val label = ui(tab.titleKey)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 52.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.set.tabSelectedFill.toColor() else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .semantics {
                        this.selected = isSelected
                        testTag = tab.tag
                    }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = null,
                    tint = if (isSelected) colors.accentOn else colors.ink.copy(alpha = 0.72f),
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = label,
                    color = if (isSelected) colors.ink else colors.ink.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

package com.heydusk.mimi.feature.parenting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.heydusk.mimi.feature.parenting.chrome.ParentingChromeTags
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
    enabled: Boolean = true,
    onDismiss: (() -> Unit)? = null,
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
        verticalAlignment = Alignment.CenterVertically,
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
                    .clickable(enabled = enabled, role = Role.Tab) { onSelect(tab) }
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
        if (onDismiss != null) {
            val dismissLabel = ui("回到输入")
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onDismiss)
                    .semantics {
                        contentDescription = dismissLabel
                        testTag = ParentingChromeTags.DISMISS_PICK_TAB
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ChevronDown,
                    contentDescription = null,
                    tint = colors.ink.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private val ChevronDown: ImageVector by lazy {
    ImageVector.Builder("ChevronDown", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(6f, 9.5f)
            lineTo(12f, 15.5f)
            lineTo(18f, 9.5f)
        }
    }.build()
}

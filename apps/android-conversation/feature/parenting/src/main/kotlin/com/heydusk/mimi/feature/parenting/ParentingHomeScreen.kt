package com.heydusk.mimi.feature.parenting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.MascotPose
import com.heydusk.mimi.designsystem.MimiCanvasBackground
import com.heydusk.mimi.designsystem.MimiMascot
import com.heydusk.mimi.designsystem.MimiTintedButton
import com.heydusk.mimi.designsystem.toColor
import com.heydusk.mimi.designsystem.tokens.Rgba
import com.heydusk.mimi.domain.theme.Appearance
import com.heydusk.mimi.domain.theme.Palette
import com.heydusk.mimi.domain.theme.ThemePreferences
import com.heydusk.mimi.i18n.ui
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.material3.MaterialTheme

/**
 * M0 的空壳：今天｜成长｜我们家 三个 Tab，每页只有标题和吉祥物；
 * "我们家" 页临时放配色/外观切换与着色按钮预览，用来在真机上目测 B/C × 浅/深（M2 起换成真正的设置页）。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ParentingHomeScreen(
    preferences: ThemePreferences,
    onPreferencesChange: (ThemePreferences) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: ParentingTab = ParentingTab.Today,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val hazeState = rememberHazeState()
    Box(modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        MimiCanvasBackground()
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            ParentingTabPage(tab, preferences, onPreferencesChange, hazeState)
        }
        ParentingTabBar(
            selected = tab,
            onSelect = { tab = it },
            hazeState = hazeState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParentingTabPage(
    tab: ParentingTab,
    preferences: ThemePreferences,
    onPreferencesChange: (ThemePreferences) -> Unit,
    hazeState: HazeState,
) {
    val colors = LocalMimiColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 120.dp)
            .semantics { testTag = "parenting.page.${tab.id}" },
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = ui(tab.titleKey),
            style = MaterialTheme.typography.headlineLarge,
            color = colors.ink,
            modifier = Modifier.semantics { testTag = "parenting.title" },
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MimiMascot(
                pose = when (tab) {
                    ParentingTab.Today -> MascotPose.Front
                    ParentingTab.Growth -> MascotPose.Idle
                    ParentingTab.Family -> MascotPose.Note
                },
            )
        }
        if (tab == ParentingTab.Family) {
            ThemeDemo(preferences, onPreferencesChange, hazeState)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeDemo(
    preferences: ThemePreferences,
    onPreferencesChange: (ThemePreferences) -> Unit,
    hazeState: HazeState,
) {
    val colors = LocalMimiColors.current
    Text(ui("配色"), style = MaterialTheme.typography.titleMedium, color = colors.ink2)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Palette.entries.forEach { palette ->
            ThemeChip(
                selected = preferences.palette == palette,
                label = ui(if (palette == Palette.InkWhite) "素白墨黑" else "雾粉燕麦"),
                tag = "parenting.palette.${palette.rawValue}",
                onClick = { onPreferencesChange(preferences.copy(palette = palette)) },
            )
        }
    }
    Text(ui("外观"), style = MaterialTheme.typography.titleMedium, color = colors.ink2)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Appearance.entries.forEach { appearance ->
            ThemeChip(
                selected = preferences.appearance == appearance,
                label = ui(
                    when (appearance) {
                        Appearance.System -> "跟随系统"
                        Appearance.Light -> "浅色"
                        Appearance.Dark -> "深色"
                    },
                ),
                tag = "parenting.appearance.${appearance.rawValue}",
                onClick = { onPreferencesChange(preferences.copy(appearance = appearance)) },
            )
        }
    }
    Text(ui("着色按钮预览"), style = MaterialTheme.typography.titleMedium, color = colors.ink2)
    ButtonRow {
        MimiTintedButton(
            onClick = {},
            hazeState = hazeState,
            modifier = Modifier.semantics { testTag = "parenting.demo.tintedButton" },
        ) { Text(ui("着色玻璃按钮")) }
        MimiTintedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.semantics { testTag = "parenting.demo.tintedButtonDisabled" },
        ) { Text(ui("禁用按钮")) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
    }
}

@Composable
private fun ThemeChip(selected: Boolean, label: String, tag: String, onClick: () -> Unit) {
    val colors = LocalMimiColors.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.semantics { testTag = tag },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.card,
            labelColor = colors.ink,
            selectedContainerColor = colors.set.tabSelectedFill
                .composited(Rgba(colors.set.card)).toColor(),
            selectedLabelColor = colors.ink,
        ),
    )
}

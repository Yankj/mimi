package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.MascotPose
import com.heydusk.mimi.designsystem.MimiMascot
import com.heydusk.mimi.designsystem.MimiTintedButton
import com.heydusk.mimi.domain.theme.Appearance
import com.heydusk.mimi.domain.theme.ThemePreferences
import com.heydusk.mimi.i18n.ui

/** 首次引导：选外观后进入。毛绒猫 + 外观三选一（完整引导视觉留到真机验收时再打磨）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingOverlay(
    preferences: ThemePreferences,
    onPreferencesChange: (ThemePreferences) -> Unit,
    onContinue: () -> Unit,
) {
    val colors = LocalMimiColors.current
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .semantics { testTag = "parenting.onboarding" },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            MimiMascot(pose = MascotPose.Front)
            Text(ui("外观"), style = MaterialTheme.typography.headlineMedium, color = colors.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Appearance.entries.forEach { appearance ->
                    FilterChip(
                        selected = preferences.appearance == appearance,
                        onClick = { onPreferencesChange(preferences.copy(appearance = appearance)) },
                        label = {
                            Text(
                                ui(
                                    when (appearance) {
                                        Appearance.System -> "跟随系统"
                                        Appearance.Light -> "浅色"
                                        Appearance.Dark -> "深色"
                                    },
                                ),
                            )
                        },
                        modifier = Modifier.semantics { testTag = "parenting.onboarding.appearance.${appearance.rawValue}" },
                    )
                }
            }
            MimiTintedButton(onClick = onContinue, modifier = Modifier.semantics { testTag = "parenting.onboarding.continue" }) {
                Text(ui("开始"))
            }
        }
    }
}

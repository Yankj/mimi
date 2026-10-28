package com.heydusk.mimi.feature.parenting.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.toColor
import com.heydusk.mimi.designsystem.tokens.Rgba
import com.heydusk.mimi.domain.dictation.DictationEngineTier
import com.heydusk.mimi.domain.theme.Appearance
import com.heydusk.mimi.domain.theme.Palette
import com.heydusk.mimi.domain.theme.ThemePreferences
import com.heydusk.mimi.i18n.ui

/**
 * 设置页（M2）：外观/主题、账户（体验账号 + 退出）、数据清除、关于/版本。
 * 配色名仍走 Android 补充目录（共享 UIStrings 没有「素白墨黑」等词，M1 已说明）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    preferences: ThemePreferences,
    onPreferencesChange: (ThemePreferences) -> Unit,
    versionName: String,
    versionCode: Int,
    onClearData: () -> Unit,
    onSignOut: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** 这台手机的语音识别档位（null = 没有语音输入卡，不显示这一行）。如实告知是否可能在线识别。 */
    voiceTier: DictationEngineTier? = null,
) {
    val colors = LocalMimiColors.current
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(24.dp)
            .semantics { testTag = "parenting.settings" },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onClose, modifier = Modifier.semantics { testTag = "parenting.settings.close" }) {
            Text(ui("取消"))
        }
        Text(ui("设置"), style = MaterialTheme.typography.headlineLarge, color = colors.ink)

        Text(ui("外观"), style = MaterialTheme.typography.titleMedium, color = colors.ink2)
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
                    modifier = Modifier.semantics { testTag = "parenting.appearance.${appearance.rawValue}" },
                    colors = chipColors(),
                )
            }
        }

        Text(ui("配色"), style = MaterialTheme.typography.titleMedium, color = colors.ink2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Palette.entries.forEach { palette ->
                FilterChip(
                    selected = preferences.palette == palette,
                    onClick = { onPreferencesChange(preferences.copy(palette = palette)) },
                    label = { Text(ui(if (palette == Palette.InkWhite) "素白墨黑" else "雾粉燕麦")) },
                    modifier = Modifier.semantics { testTag = "parenting.palette.${palette.rawValue}" },
                    colors = chipColors(),
                )
            }
        }

        Text(ui("账户"), style = MaterialTheme.typography.titleMedium, color = colors.ink2)
        Text(ui("体验账号"), color = colors.ink, modifier = Modifier.semantics { testTag = "parenting.settings.account" })
        TextButton(onClick = onSignOut, modifier = Modifier.semantics { testTag = "parenting.settings.signOut" }) {
            Text(ui("退出登录"))
        }

        if (voiceTier != null) {
            Text(ui("语音输入"), style = MaterialTheme.typography.titleMedium, color = colors.ink2)
            Text(
                when (voiceTier) {
                    DictationEngineTier.OnDeviceRecognizer -> ui("本机语音识别：声音只在这台手机上识别，不上传。")
                    DictationEngineTier.ServerRecognizer -> ui("系统语音服务：这台手机没有本机识别，说话时可能经系统语音服务在线识别，小咪不保存录音。")
                    else -> ui("这台手机暂时没有可用的语音识别服务，可以用键盘输入。")
                },
                color = colors.ink,
                modifier = Modifier.semantics(mergeDescendants = true) { testTag = "parenting.settings.voice" },
            )
        }

        Text(ui("数据与隐私"), style = MaterialTheme.typography.titleMedium, color = colors.ink2)
        TextButton(onClick = onClearData, modifier = Modifier.semantics { testTag = "parenting.settings.clearData" }) {
            Text(ui("清除全部数据"))
        }

        Text(ui("关于"), style = MaterialTheme.typography.titleMedium, color = colors.ink2)
        Text(
            "${ui("版本")} $versionName (${ui("构建")} $versionCode)",
            color = colors.ink,
            modifier = Modifier.semantics { testTag = "parenting.settings.about" },
        )
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    containerColor = LocalMimiColors.current.card,
    labelColor = LocalMimiColors.current.ink,
    selectedContainerColor = LocalMimiColors.current.set.tabSelectedFill
        .composited(Rgba(LocalMimiColors.current.set.card)).toColor(),
    selectedLabelColor = LocalMimiColors.current.ink,
)

package com.heydusk.mimi.feature.parenting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.MimiCanvasBackground
import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.parenting.FamilyLaunchChecklist
import com.heydusk.mimi.domain.parenting.ParentingFamilySummary
import com.heydusk.mimi.domain.parenting.ParentingGrowthInsights
import com.heydusk.mimi.domain.parenting.ParentingGrowthJourney
import com.heydusk.mimi.domain.parenting.ParentingTodayDeck
import com.heydusk.mimi.domain.parenting.ParentingTodayHome
import com.heydusk.mimi.domain.parenting.ParentingWeekDensity
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.domain.theme.ThemePreferences
import com.heydusk.mimi.feature.parenting.forms.ArrangementDraft
import com.heydusk.mimi.feature.parenting.forms.ArrangementEntryDialog
import com.heydusk.mimi.feature.parenting.forms.ChildEntryDialog
import com.heydusk.mimi.feature.parenting.forms.GoalEntryDialog
import com.heydusk.mimi.feature.parenting.forms.ManualSheet
import com.heydusk.mimi.feature.parenting.forms.ObservationEntryDialog
import com.heydusk.mimi.feature.parenting.settings.SettingsScreen
import com.heydusk.mimi.feature.parenting.ui.FamilyTabContent
import com.heydusk.mimi.feature.parenting.ui.FamilyUi
import com.heydusk.mimi.feature.parenting.ui.GrowthTabContent
import com.heydusk.mimi.feature.parenting.ui.GrowthUi
import com.heydusk.mimi.feature.parenting.ui.OnboardingOverlay
import com.heydusk.mimi.feature.parenting.ui.PrivacyShield
import com.heydusk.mimi.feature.parenting.ui.TodayTabContent
import com.heydusk.mimi.feature.parenting.ui.TodayUi
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * M2 三 Tab 壳：今天 / 成长 / 我们家，接真实账本投影；手动表单只在点保存时写入。
 * @param workspace 已加载的账本；null 表示还在载入。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ParentingHomeScreen(
    preferences: ThemePreferences,
    onPreferencesChange: (ThemePreferences) -> Unit,
    workspace: ParentingWorkspace?,
    loaded: Boolean,
    error: String?,
    onboardingDone: Boolean,
    privacyShield: Boolean,
    undoSeconds: Int?,
    versionName: String,
    versionCode: Int,
    onAddChild: (String) -> Unit,
    onAddGoal: (childID: UUID, title: String) -> Unit,
    onAddArrangement: (childID: UUID, draft: ArrangementDraft) -> Unit,
    onAddObservation: (childID: UUID, text: String) -> Unit,
    onConfirmArrangement: (UUID) -> Unit,
    onCompleteArrangement: (UUID) -> Unit,
    onUndoCompletion: () -> Unit,
    onCompleteOnboarding: () -> Unit,
    onClearData: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: ParentingTab = ParentingTab.Today,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var sheet by remember { mutableStateOf<ManualSheet?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val calendar = remember(zone) { MimiCalendar(zone) }

    Box(modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        MimiCanvasBackground()
        when {
            !loaded || workspace == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.semantics { testTag = "parenting.loading" })
                }
            }
            showSettings -> {
                SettingsScreen(
                    preferences = preferences,
                    onPreferencesChange = onPreferencesChange,
                    versionName = versionName,
                    versionCode = versionCode,
                    onClearData = onClearData,
                    onSignOut = onSignOut,
                    onClose = { showSettings = false },
                )
            }
            else -> {
                Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                    val pageModifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 120.dp)
                        .semantics { testTag = "parenting.page.${tab.id}" }
                    when (tab) {
                        ParentingTab.Today -> {
                            val deck = ParentingTodayDeck.build(workspace, now, calendar)
                            val home = ParentingTodayHome.build(workspace, deck.childID, now, calendar)
                            TodayTabContent(
                                TodayUi(home, deck, workspace),
                                onAddChild = { sheet = ManualSheet.AddChild },
                                onAddArrangement = { sheet = ManualSheet.AddArrangement(it) },
                                onConfirmArrangement = onConfirmArrangement,
                                onCompleteArrangement = onCompleteArrangement,
                                onUndo = onUndoCompletion,
                                undoSeconds = undoSeconds,
                                modifier = pageModifier,
                            )
                        }
                        ParentingTab.Growth -> {
                            val child = ParentingGrowthInsights.scopedChild(workspace, null)
                            val week = ParentingWeekDensity.build(workspace, child?.id, now, calendar)
                            val journey = ParentingGrowthJourney.build(workspace, child?.id, calendar = calendar)
                            GrowthTabContent(
                                GrowthUi(workspace, week, journey, child?.id),
                                onAddGoal = { sheet = ManualSheet.AddGoal(it) },
                                onAddObservation = { sheet = ManualSheet.AddObservation(it) },
                                modifier = pageModifier,
                            )
                        }
                        ParentingTab.Family -> {
                            FamilyTabContent(
                                FamilyUi(
                                    ParentingFamilySummary.children(workspace),
                                    FamilyLaunchChecklist.current(workspace, remindersEnabled = false),
                                ),
                                onAddChild = { sheet = ManualSheet.AddChild },
                                onOpenSettings = { showSettings = true },
                                modifier = pageModifier,
                            )
                        }
                    }
                    if (error != null) {
                        Text(
                            error,
                            color = LocalMimiColors.current.ink,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                                .semantics { testTag = "parenting.error" },
                        )
                    }
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

        when (val s = sheet) {
            ManualSheet.AddChild -> ChildEntryDialog(
                onConfirm = { onAddChild(it); sheet = null },
                onDismiss = { sheet = null },
            )
            is ManualSheet.AddGoal -> GoalEntryDialog(
                onConfirm = { onAddGoal(s.childID, it); sheet = null },
                onDismiss = { sheet = null },
            )
            is ManualSheet.AddArrangement -> ArrangementEntryDialog(
                onConfirm = { onAddArrangement(s.childID, it); sheet = null },
                onDismiss = { sheet = null },
            )
            is ManualSheet.AddObservation -> ObservationEntryDialog(
                onConfirm = { onAddObservation(s.childID, it); sheet = null },
                onDismiss = { sheet = null },
            )
            else -> Unit
        }

        if (!onboardingDone && loaded) {
            OnboardingOverlay(preferences, onPreferencesChange, onCompleteOnboarding)
        }
        PrivacyShield(privacyShield)
    }
}

/** M0 兼容：没有账本时的空壳预览（截图 / 旧测试）。 */
@Composable
fun ParentingHomeScreen(
    preferences: ThemePreferences,
    onPreferencesChange: (ThemePreferences) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: ParentingTab = ParentingTab.Today,
) {
    ParentingHomeScreen(
        preferences = preferences,
        onPreferencesChange = onPreferencesChange,
        workspace = ParentingWorkspace.EMPTY,
        loaded = true,
        error = null,
        onboardingDone = true,
        privacyShield = false,
        undoSeconds = null,
        versionName = "0.1.0",
        versionCode = 1,
        onAddChild = {},
        onAddGoal = { _, _ -> },
        onAddArrangement = { _, _ -> },
        onAddObservation = { _, _ -> },
        onConfirmArrangement = {},
        onCompleteArrangement = {},
        onUndoCompletion = {},
        onCompleteOnboarding = {},
        onClearData = {},
        onSignOut = {},
        modifier = modifier,
        initialTab = initialTab,
    )
}

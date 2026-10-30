package com.heydusk.mimi.feature.parenting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import com.heydusk.mimi.device.reminders.NotificationAuthorization
import com.heydusk.mimi.feature.parenting.reminders.LocalReminders
import com.heydusk.mimi.feature.parenting.reminders.ReminderOpenRequest
import com.heydusk.mimi.feature.parenting.reminders.RemindersActions
import com.heydusk.mimi.feature.parenting.reminders.RemindersContext
import com.heydusk.mimi.feature.parenting.reminders.RemindersPage
import com.heydusk.mimi.feature.parenting.reminders.RemindersUi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import com.heydusk.mimi.domain.parenting.ParentingStartContext
import com.heydusk.mimi.domain.parenting.ParentingTodayDeck
import com.heydusk.mimi.domain.parenting.ParentingTodayHome
import com.heydusk.mimi.domain.parenting.ParentingWeekDensity
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.domain.theme.ThemePreferences
import com.heydusk.mimi.domain.parenting.ParentingIntentKind
import com.heydusk.mimi.domain.parenting.ParentingNaturalContext
import com.heydusk.mimi.domain.parenting.ParentingNaturalProposal
import com.heydusk.mimi.feature.parenting.actions.ParentingActions
import com.heydusk.mimi.feature.parenting.composer.NaturalInputModel
import com.heydusk.mimi.feature.parenting.composer.ParentingComposerBar
import com.heydusk.mimi.feature.parenting.composer.ParentingNaturalSheet
import com.heydusk.mimi.feature.parenting.composer.VoiceDeps
import com.heydusk.mimi.feature.parenting.composer.rememberVoiceState
import com.heydusk.mimi.feature.parenting.ui.LocalFloatingBottomInset
import com.heydusk.mimi.feature.parenting.forms.ArrangementEntryDialog
import com.heydusk.mimi.feature.parenting.forms.ChildEntryDialog
import com.heydusk.mimi.feature.parenting.forms.CompleteEntryDialog
import com.heydusk.mimi.feature.parenting.forms.ConfirmOverlay
import com.heydusk.mimi.feature.parenting.forms.FormStatus
import com.heydusk.mimi.feature.parenting.forms.GoalEntryDialog
import com.heydusk.mimi.feature.parenting.forms.ManualSheet
import com.heydusk.mimi.feature.parenting.forms.ObservationEntryDialog
import com.heydusk.mimi.feature.parenting.forms.PendingConfirm
import com.heydusk.mimi.feature.parenting.settings.SettingsScreen
import com.heydusk.mimi.feature.parenting.ui.ChildDetailPage
import com.heydusk.mimi.feature.parenting.ui.FamilyTabContent
import com.heydusk.mimi.feature.parenting.ui.FamilyUi
import com.heydusk.mimi.feature.parenting.ui.GoalReviewPage
import com.heydusk.mimi.feature.parenting.ui.GrowthGoalsPage
import com.heydusk.mimi.feature.parenting.ui.GrowthTabContent
import com.heydusk.mimi.feature.parenting.ui.GrowthTimelinePage
import com.heydusk.mimi.feature.parenting.ui.GrowthUi
import com.heydusk.mimi.feature.parenting.ui.OnboardingOverlay
import com.heydusk.mimi.feature.parenting.ui.PageHandlers
import com.heydusk.mimi.feature.parenting.ui.ParentingRoute
import com.heydusk.mimi.feature.parenting.ui.PrivacyShield
import com.heydusk.mimi.feature.parenting.ui.TodayAllPage
import com.heydusk.mimi.feature.parenting.ui.TodayTabContent
import com.heydusk.mimi.feature.parenting.ui.TodayUi
import com.heydusk.mimi.i18n.ui
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * 三 Tab 壳（今天 / 成长 / 我们家）+ 二级页 + 手动表单。接真实账本投影；所有写入都经 [actions]，且只在家长点
 * 「保存 / 确认」之后：表单里的草稿只活在内存里，放弃零写入；写失败时表单保持打开、输入保留、错误显示在表单里。
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
    actions: ParentingActions,
    onCompleteOnboarding: () -> Unit,
    onClearData: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: ParentingTab = ParentingTab.Today,
    initialRoutes: List<ParentingRoute> = emptyList(),
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    /** 语音输入卡的依赖；null = 不显示输入卡（M2 行为，预览与旧测试）。 */
    voice: VoiceDeps? = null,
    /** 账本正忙（写入中）：输入卡暂停提交。 */
    busy: Boolean = false,
    /** 提醒是否开启：确认卡里据此显示提醒说明；null = 不显示（提醒在 M4）。 */
    remindersEnabled: Boolean? = null,
    /** 本机提醒的状态与操作；null = 这个壳不发提醒（预览与 M2/M3 旧测试）。 */
    reminders: RemindersUi? = null,
    reminderActions: RemindersActions = RemindersActions.None,
    /** 点通知打开：回到「今天」。处理完调用 [onReminderOpenHandled]。 */
    reminderOpen: ReminderOpenRequest? = null,
    onReminderOpenHandled: () -> Unit = {},
    /** 云端账号（M5）；null = 没有云端（预览、旧测试、未配置）。 */
    cloud: com.heydusk.mimi.feature.parenting.cloud.CloudAccount? = null,
    /** 用 Custom Tabs 打开 OAuth 授权页。 */
    onOpenAuthUrl: (String) -> Unit = {},
) {
    val cloudUi = cloud?.state?.collectAsState()?.value
    var showLogin by remember { mutableStateOf(false) }
    var showEnroll by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val stack = remember { mutableStateListOf<ParentingRoute>().apply { addAll(initialRoutes) } }
    var sheet by remember { mutableStateOf<ManualSheet?>(null) }
    var pending by remember { mutableStateOf<PendingConfirm?>(null) }
    var selectedChild by remember { mutableStateOf<UUID?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val calendar = remember(zone) { MimiCalendar(zone) }
    val scope = rememberCoroutineScope()
    val natural = remember { NaturalInputModel() }
    val voiceState = if (voice != null) rememberVoiceState(voice) else null
    var naturalOpen by remember { mutableStateOf(false) }
    var floatingHeight by remember { mutableStateOf<Dp?>(null) }
    val density = LocalDensity.current

    fun openSheet(next: ManualSheet) {
        actions.clearError()
        sheet = next
    }

    /** 表单的「保存」：只有账本确认（true）才关表单；否则保持打开并显示 model.error。 */
    fun submit(block: suspend () -> Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            val ok = try { block() } finally { saving = false }
            if (ok) sheet = null
        }
    }

    fun act(block: suspend () -> Boolean) {
        scope.launch { block() }
    }

    fun fallbackChild(): UUID? = ParentingGrowthInsights.scopedChild(workspace ?: ParentingWorkspace.EMPTY, selectedChild)?.id

    /** 「手动调整」把家长交给现有表单：什么都不预填、不保存（与 iOS 一致）。 */
    fun manualSheetFor(p: ParentingNaturalProposal): ManualSheet {
        val child = p.childID ?: fallbackChild()
        val target = p.targetID?.let { id -> workspace?.arrangements?.firstOrNull { it.id == id } }
        return when {
            p.kind == ParentingIntentKind.Observation && child != null -> ManualSheet.AddObservation(child)
            p.kind == ParentingIntentKind.Reschedule && target != null -> ManualSheet.AdjustArrangement(target)
            child != null -> ManualSheet.AddArrangement(child)
            else -> ManualSheet.AddChild
        }
    }

    LaunchedEffect(reminderOpen?.nonce) {
        if (reminderOpen != null) {
            tab = ParentingTab.Today
            stack.clear()
            showSettings = false
            sheet = null
            pending = null
            onReminderOpenHandled()
        }
    }

    val handlers = PageHandlers(
        onConfirmArrangement = { id -> act { actions.confirmArrangement(id) } },
        onCompleteOneTap = { id -> act { actions.completeOneTap(id) } },
        onCompleteWithNote = { a -> openSheet(ManualSheet.CompleteArrangement(a.id, a.title)) },
        onAdjust = { a -> openSheet(ManualSheet.AdjustArrangement(a)) },
        onCancelArrangement = { a -> pending = PendingConfirm.CancelArrangement(a.id, a.title) },
        onDeleteArrangement = { a -> pending = PendingConfirm.DeleteArrangement(a.id, a.title) },
        onUndoAdjustment = { edit ->
            val conflicts = workspace?.sameTimeConflicts(edit.before.id, edit.before.dueAt).orEmpty()
            if (conflicts.isEmpty()) act { actions.undoAdjustment(edit.id, emptyList()) }
            else pending = PendingConfirm.UndoAdjustment(edit.id, conflicts.map { it.id }, conflicts.map { it.title })
        },
        onOpenRecord = { recordID ->
            tab = ParentingTab.Growth
            stack.clear()
            stack.add(ParentingRoute.GrowthTimeline(recordID))
        },
        onEditRecord = { r -> openSheet(ManualSheet.EditRecord(r.id, r.text)) },
        onDeleteRecord = { r -> pending = PendingConfirm.DeleteRecord(r.id) },
        onAddChild = { openSheet(ManualSheet.AddChild) },
        onRenameChild = { c -> openSheet(ManualSheet.RenameChild(c.id, c.name)) },
        onDeleteChild = { c -> pending = PendingConfirm.DeleteChild(c.id, c.name) },
        onAddGoal = { childID -> openSheet(ManualSheet.AddGoal(childID)) },
        onDeleteGoal = { id -> pending = PendingConfirm.DeleteGoal(id, workspace?.goals?.firstOrNull { it.id == id }?.title ?: "") },
        onAddArrangement = { childID -> openSheet(ManualSheet.AddArrangement(childID)) },
        onAddObservation = { childID, goalID -> openSheet(ManualSheet.AddObservation(childID, goalID)) },
        onOpen = { route -> stack.add(route) },
        onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
        onSelectChild = { selectedChild = it },
        onOpenSettings = { showSettings = true },
    )

    BackHandler(enabled = stack.isNotEmpty() && sheet == null && pending == null && !showSettings) { handlers.onBack() }

    val reminderContext = remember(reminders, reminderActions, now) { reminders?.let { RemindersContext(it, reminderActions, now) } }
    CompositionLocalProvider(LocalReminders provides reminderContext) {
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
                    voiceTier = voiceState?.tier,
                    cloud = cloudUi,
                    onOpenLogin = { cloud?.dismissMessage(); showLogin = true },
                    onOpenEnrollment = { showEnroll = true },
                )
            }
            else -> {
                val child = ParentingGrowthInsights.scopedChild(workspace, selectedChild)
                val childID = child?.id
                val showComposer = voiceState != null && workspace.children.isNotEmpty() &&
                    (tab == ParentingTab.Today || tab == ParentingTab.Growth) && stack.isEmpty()
                val bottomInset = if (showComposer) (floatingHeight ?: 120.dp) + 8.dp else 120.dp
                CompositionLocalProvider(LocalFloatingBottomInset provides bottomInset) {
                Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                    val page = Modifier.fillMaxSize().semantics { testTag = "parenting.page.${tab.id}" }
                    when (val route = stack.lastOrNull()) {
                        ParentingRoute.TodayAll -> TodayAllPage(workspace, childID, zone, handlers, page)
                        is ParentingRoute.GrowthTimeline -> GrowthTimelinePage(workspace, childID, route.highlight, zone, handlers, page)
                        ParentingRoute.Reminders -> RemindersPage(handlers, page)
                        ParentingRoute.GrowthGoals -> GrowthGoalsPage(workspace, childID, handlers, page)
                        is ParentingRoute.GoalReview -> GoalReviewPage(workspace, route.goalID, zone, handlers, page)
                        is ParentingRoute.ChildDetail -> ChildDetailPage(workspace, route.childID, handlers, page)
                        null -> when (tab) {
                            ParentingTab.Today -> {
                                val deck = ParentingTodayDeck.build(workspace, now, calendar, ParentingStartContext(childID = selectedChild))
                                val home = ParentingTodayHome.build(workspace, deck.childID, now, calendar)
                                TodayTabContent(
                                    TodayUi(home, deck, workspace),
                                    handlers = handlers,
                                    undoSeconds = undoSeconds,
                                    onUndo = { act { actions.undoCompletion() } },
                                    zone = zone,
                                    modifier = page,
                                )
                            }
                            ParentingTab.Growth -> {
                                val week = ParentingWeekDensity.build(workspace, childID, now, calendar)
                                val journey = ParentingGrowthJourney.build(workspace, childID, calendar = calendar)
                                GrowthTabContent(GrowthUi(workspace, week, journey, childID), handlers, page)
                            }
                            ParentingTab.Family -> FamilyTabContent(
                                FamilyUi(
                                    ParentingFamilySummary.children(workspace),
                                    FamilyLaunchChecklist.current(workspace, remindersEnabled = reminders?.settings?.enabled == true && reminders.authorization == NotificationAuthorization.Authorized),
                                    workspace.profile.usableFacts(com.heydusk.mimi.domain.parenting.ParentingProfileScope.FAMILY),
                                ),
                                handlers,
                                page,
                            )
                        }
                    }
                    if (error != null && sheet == null) {
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
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .onSizeChanged { floatingHeight = with(density) { it.height.toDp() } },
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    if (voiceState != null && showComposer) {
                        val context = remember(workspace, selectedChild) { ParentingNaturalContext(workspace, selectedChild) }
                        ParentingComposerBar(
                            input = natural,
                            voice = voiceState,
                            context = context,
                            now = now,
                            calendar = calendar,
                            zone = zone,
                            busy = busy || saving,
                            hazeState = hazeState,
                            onSubmit = {
                                if (natural.canSubmit && !busy && !saving) {
                                    natural.submit(context, now, calendar)
                                    naturalOpen = true
                                }
                            },
                            onManualArrangement = { openSheet(childID?.let { ManualSheet.AddArrangement(it) } ?: ManualSheet.AddChild) },
                            onManualObservation = { openSheet(childID?.let { ManualSheet.AddObservation(it) } ?: ManualSheet.AddChild) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    ParentingTabBar(
                        selected = tab,
                        onSelect = { tab = it; stack.clear() },
                        hazeState = hazeState,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        val status = FormStatus(saving = saving, error = error)
        if (workspace != null && loaded) {
            when (val s = sheet) {
                ManualSheet.AddChild -> ChildEntryDialog(
                    onConfirm = { name -> submit { actions.addChild(name) } },
                    onDismiss = { sheet = null },
                    status = status,
                )
                is ManualSheet.RenameChild -> ChildEntryDialog(
                    onConfirm = { name -> submit { actions.renameChild(s.id, name) } },
                    onDismiss = { sheet = null },
                    status = status,
                    title = ui("修改昵称"),
                    initial = s.name,
                )
                is ManualSheet.AddGoal -> GoalEntryDialog(
                    onConfirm = { title -> submit { actions.addGoal(s.childID, title) } },
                    onDismiss = { sheet = null },
                    status = status,
                )
                is ManualSheet.AddArrangement -> {
                    val intake = remember(s, cloudUi?.subjectID, cloudUi?.serverScopeID) {
                        if (cloudUi?.intakeReady == true) cloud.intakeModel() else null
                    }
                    // 关表单 / 换账号：停止等待并清理临时副本（iOS 离开表单同样处理）。
                    androidx.compose.runtime.DisposableEffect(intake) { onDispose { intake?.invalidate() } }
                    ArrangementEntryDialog(
                        onConfirm = { draft, _ -> submit { actions.addArrangement(s.childID, draft) } },
                        onDismiss = { sheet = null },
                        goals = workspace.goals.filter { it.childID == s.childID },
                        zone = zone,
                        now = now,
                        status = status,
                        intake = intake,
                        childID = s.childID,
                    )
                }
                is ManualSheet.AdjustArrangement -> ArrangementEntryDialog(
                    onConfirm = { draft, conflicts -> submit { actions.reviseArrangement(s.arrangement, draft, conflicts) } },
                    onDismiss = { sheet = null },
                    goals = workspace.goals.filter { it.childID == s.arrangement.childID },
                    zone = zone,
                    now = now,
                    status = status,
                    adjusting = s.arrangement,
                    conflictsFor = { workspace.sameTimeConflicts(s.arrangement.id, it) },
                )
                is ManualSheet.AddObservation -> ObservationEntryDialog(
                    onConfirm = { text -> submit { actions.addObservation(s.childID, s.goalID, text) } },
                    onDismiss = { sheet = null },
                    status = status,
                )
                is ManualSheet.EditRecord -> ObservationEntryDialog(
                    onConfirm = { text -> submit { actions.editRecord(s.id, text) } },
                    onDismiss = { sheet = null },
                    status = status,
                    title = ui("修改记录"),
                    initial = s.text,
                )
                is ManualSheet.CompleteArrangement -> CompleteEntryDialog(
                    title = s.title,
                    onConfirm = { note -> submit { actions.completeWithNote(s.arrangementID, note) } },
                    onDismiss = { sheet = null },
                    status = status,
                )
                null -> Unit
            }
        }

        if (naturalOpen && workspace != null && loaded && voiceState != null) {
            val context = remember(workspace, selectedChild) { ParentingNaturalContext(workspace, selectedChild) }
            ParentingNaturalSheet(
                input = natural,
                workspace = workspace,
                actions = actions,
                context = context,
                now = now,
                calendar = calendar,
                zone = zone,
                busy = busy || saving,
                remindersEnabled = remindersEnabled ?: reminders?.settings?.enabled,
                receiptMillis = voiceState.receiptMillis,
                onClose = { natural.reset(); naturalOpen = false },
                onNavigate = { kind ->
                    when (kind) {
                        ParentingIntentKind.ReviewGrowth -> { tab = ParentingTab.Growth; stack.clear() }
                        ParentingIntentKind.ViewToday, ParentingIntentKind.ViewPlan -> { tab = ParentingTab.Today; stack.clear() }
                        else -> Unit
                    }
                },
                onManual = { p -> naturalOpen = false; openSheet(manualSheetFor(p)) },
                onAddChild = { naturalOpen = false; openSheet(ManualSheet.AddChild) },
            )
        }

        pending?.let { p ->
            val close = { pending = null }
            when (p) {
                is PendingConfirm.CancelArrangement -> ConfirmOverlay(
                    ui("取消这个安排？"), p.title, ui("取消这个安排"), "parenting.confirm.dialog.ok",
                    { close(); act { actions.cancelArrangement(p.id) } }, close,
                )
                is PendingConfirm.DeleteArrangement -> ConfirmOverlay(
                    ui("删除这个安排？"), p.title, ui("删除"), "parenting.confirm.dialog.ok",
                    { close(); act { actions.deleteArrangement(p.id) } }, close,
                )
                is PendingConfirm.DeleteRecord -> ConfirmOverlay(
                    ui("删除这条记录？"), null, ui("删除"), "parenting.confirm.dialog.ok",
                    { close(); act { actions.deleteRecord(p.id) } }, close,
                )
                is PendingConfirm.DeleteGoal -> ConfirmOverlay(
                    ui("删除这个目标？"), ui("关联的安排和记录会保留，只是不再属于这个目标。"), ui("删除"), "parenting.confirm.dialog.ok",
                    { close(); scope.launch { if (actions.deleteGoal(p.id)) handlers.onBack() } }, close,
                )
                is PendingConfirm.DeleteChild -> ConfirmOverlay(
                    ui("删除 {0}？", p.name), ui("该孩子的目标、安排和记录都会一并删除，无法恢复。"), ui("删除"), "parenting.confirm.dialog.ok",
                    { close(); scope.launch { if (actions.deleteChild(p.id)) handlers.onBack() } }, close,
                )
                is PendingConfirm.UndoAdjustment -> ConfirmOverlay(
                    ui("撤销这次调整？"), ui("同一时间已有：{0}", p.conflictTitles.joinToString("、")), ui("仍然撤销"), "parenting.confirm.dialog.ok",
                    { close(); act { actions.undoAdjustment(p.editID, p.conflictIDs) } }, close,
                )
            }
        }

        if (!onboardingDone && loaded) {
            OnboardingOverlay(preferences, onPreferencesChange, onCompleteOnboarding)
        }
        if (cloud != null && showLogin) {
            com.heydusk.mimi.feature.parenting.cloud.LoginOverlay(cloud, onOpenAuthUrl) { showLogin = false }
        }
        if (cloud != null && showEnroll) {
            com.heydusk.mimi.feature.parenting.cloud.EnrollmentOverlay(cloud) { showEnroll = false }
        }
        PrivacyShield(privacyShield)
    }
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
        actions = ParentingActions.None,
        onCompleteOnboarding = {},
        onClearData = {},
        onSignOut = {},
        modifier = modifier,
        initialTab = initialTab,
    )
}

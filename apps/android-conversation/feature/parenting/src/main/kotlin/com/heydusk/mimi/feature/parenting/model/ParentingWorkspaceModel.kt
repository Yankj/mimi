package com.heydusk.mimi.feature.parenting.model

import com.heydusk.mimi.data.ledger.ParentingWorkspaceStore
import com.heydusk.mimi.device.reminders.ParentingReminderScheduler
import com.heydusk.mimi.device.reminders.ParentingReminderStatus
import com.heydusk.mimi.device.reminders.ReminderSettingsStore
import com.heydusk.mimi.domain.parenting.ParentingReminderLead
import com.heydusk.mimi.domain.parenting.ParentingReminderSettings
import com.heydusk.mimi.domain.common.DomainError
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.domain.parenting.ParentingCompletionUndo
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一个账号的提醒装配：调度器 + 设置存储，外加「账号激活 / 退出」两个平台钩子（登记当前账号、清空队列与设置）。
 * 没有它（预览、测试、没有提醒能力的壳）模型就完全不碰提醒。
 */
class ReminderWiring(
    val scheduler: ParentingReminderScheduler,
    val settings: ReminderSettingsStore,
    val onActivate: () -> Unit = {},
    val onSignOut: suspend () -> Unit = {},
)

/**
 * 对应 iOS `ParentingWorkspaceModel`：持有一份已加载的账本，所有写入都走 store.apply
 * （确认前零落盘由 store 保证）。账本写成功之后再对账本机提醒——提醒出问题永远不会把已保存的改动变成失败。
 */
class ParentingWorkspaceModel(
    private val store: ParentingWorkspaceStore,
    private val clock: () -> Instant = { Instant.now() },
    val reminders: ReminderWiring? = null,
) {
    data class State(
        val workspace: ParentingWorkspace = ParentingWorkspace.EMPTY,
        val loaded: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val undo: ParentingCompletionUndo? = null,
        val reminderSettings: ParentingReminderSettings = ParentingReminderSettings.DEFAULT,
        val reminderStatus: ParentingReminderStatus? = null,
    )

    private val mutex = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val workspace: ParentingWorkspace get() = _state.value.workspace
    val loaded: Boolean get() = _state.value.loaded
    val isCurrentAccount: Boolean get() = store.isCurrentAccount()
    val remindersAvailable: Boolean get() = reminders != null

    init {
        if (reminders != null) _state.value = _state.value.copy(reminderSettings = reminders.settings.load())
    }

    suspend fun load(): Unit = mutex.withLock {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        try {
            val saved = store.load()
            if (!store.isCurrentAccount()) throw DomainError.Unavailable("账号已变更，请重新打开育儿账本。")
            _state.value = _state.value.copy(workspace = saved, loaded = true, busy = false, error = null, undo = null)
        } catch (e: DomainError) {
            _state.value = _state.value.copy(busy = false, error = e.message)
            return
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, error = e.message ?: e.toString())
            return
        }
        syncReminders()
    }

    /**
     * 把一条命令写入账本。返回 true 表示账本已确认。
     * 调用方必须先在界面里收集完整字段，点了「保存/确认」再调——草稿态只活在 ViewModel 里。
     */
    suspend fun apply(
        command: ParentingCommand,
        operationID: UUID = UUID.randomUUID(),
        expectedRevision: Int? = null,
    ): Boolean = mutex.withLock {
        if (_state.value.busy) return false
        if (!_state.value.loaded) {
            _state.value = _state.value.copy(error = "请先载入育儿账本，再保存修改。")
            return false
        }
        _state.value = _state.value.copy(busy = true, error = null)
        return try {
            val saved = store.apply(command, expectedRevision ?: _state.value.workspace.revision, operationID)
            if (!store.isCurrentAccount()) throw DomainError.Unavailable("账号已变更，请重新打开育儿账本。")
            _state.value = _state.value.copy(workspace = saved, busy = false, undo = null)
            true
        } catch (e: DomainError) {
            _state.value = _state.value.copy(busy = false, error = e.message)
            false
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, error = e.message ?: e.toString())
            false
        }.also { saved ->
            // 账本已经保存；提醒的问题只体现在提醒状态里，不会让这次保存失败。
            if (saved) syncReminders()
        }
    }

    /** 一键完成；账本确认后才返回撤销句柄。 */
    suspend fun completeOneTap(
        arrangementID: UUID,
        operationID: UUID = UUID.randomUUID(),
        recordID: UUID = UUID.randomUUID(),
    ): ParentingCompletionUndo? {
        if (!apply(ParentingCommand.CompleteArrangement(arrangementID, recordID, ""), operationID)) return null
        val undo = ParentingCompletionUndo.make(workspace, arrangementID, recordID, clock())
        _state.value = _state.value.copy(undo = undo)
        return undo
    }

    suspend fun undoCompletion(undo: ParentingCompletionUndo, operationID: UUID = UUID.randomUUID()): Boolean {
        if (!undo.isActive(clock())) {
            _state.value = _state.value.copy(undo = null)
            return false
        }
        val ok = apply(ParentingCommand.ReopenArrangement(undo.arrangementID), operationID)
        if (ok) _state.value = _state.value.copy(undo = null)
        return ok
    }

    fun clearUndo() {
        _state.value = _state.value.copy(undo = null)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // ── 提醒 ──────────────────────────────────────────────────

    /** 回到前台 / 从系统设置回来 / 开机、时区、校时之后：重读系统权限，按已保存的账本重新规划。 */
    suspend fun refreshReminders() {
        if (!_state.value.loaded) return
        syncReminders()
    }

    /** 家长开关提醒。打开时才向系统要权限（只会问一次，且由这个明确的操作触发）。 */
    suspend fun setReminders(enabled: Boolean) {
        val wiring = reminders ?: return
        if (!store.isCurrentAccount()) return
        val next = _state.value.reminderSettings.copy(enabled = enabled)
        if (enabled && wiring.scheduler.authorization() == com.heydusk.mimi.device.reminders.NotificationAuthorization.NotDetermined) {
            wiring.scheduler.requestAuthorization()
        }
        saveReminderSettings(next)
        syncReminders()
    }

    suspend fun setReminderLead(lead: ParentingReminderLead) {
        if (reminders == null || !store.isCurrentAccount()) return
        saveReminderSettings(_state.value.reminderSettings.copy(lead = lead))
        syncReminders()
    }

    suspend fun setReminderContentHidden(hidden: Boolean) {
        if (reminders == null || !store.isCurrentAccount()) return
        saveReminderSettings(_state.value.reminderSettings.copy(hideContent = hidden))
        syncReminders()
    }

    private fun saveReminderSettings(settings: ParentingReminderSettings) {
        _state.value = _state.value.copy(reminderSettings = settings)
        reminders?.settings?.save(settings)
    }

    private suspend fun syncReminders() {
        val wiring = reminders ?: return
        val current = _state.value
        if (!current.loaded || !store.isCurrentAccount()) return
        val status = try {
            wiring.scheduler.reconcile(current.workspace, current.reminderSettings, clock())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return // 系统调用失败：不声称任何事，也不影响账本
        }
        if (!store.isCurrentAccount()) return
        _state.value = _state.value.copy(reminderStatus = status)
    }
}

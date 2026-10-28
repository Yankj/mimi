package com.heydusk.mimi.feature.parenting.model

import com.heydusk.mimi.data.ledger.ParentingWorkspaceStore
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
 * 对应 iOS `ParentingWorkspaceModel`：持有一份已加载的账本，所有写入都走 store.apply
 * （确认前零落盘由 store 保证）。提醒调度留到 M4，这里不接。
 */
class ParentingWorkspaceModel(
    private val store: ParentingWorkspaceStore,
    private val clock: () -> Instant = { Instant.now() },
) {
    data class State(
        val workspace: ParentingWorkspace = ParentingWorkspace.EMPTY,
        val loaded: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val undo: ParentingCompletionUndo? = null,
    )

    private val mutex = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val workspace: ParentingWorkspace get() = _state.value.workspace
    val loaded: Boolean get() = _state.value.loaded
    val isCurrentAccount: Boolean get() = store.isCurrentAccount()

    suspend fun load(): Unit = mutex.withLock {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        try {
            val saved = store.load()
            if (!store.isCurrentAccount()) throw DomainError.Unavailable("账号已变更，请重新打开育儿账本。")
            _state.value = State(workspace = saved, loaded = true, busy = false)
        } catch (e: DomainError) {
            _state.value = _state.value.copy(busy = false, error = e.message)
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, error = e.message ?: e.toString())
        }
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
}

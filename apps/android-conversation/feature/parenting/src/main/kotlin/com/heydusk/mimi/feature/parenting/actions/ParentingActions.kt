package com.heydusk.mimi.feature.parenting.actions

import com.heydusk.mimi.domain.parenting.ParentingArrangement
import com.heydusk.mimi.domain.parenting.ParentingArrangementEditTarget
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.feature.parenting.forms.ArrangementDraft
import com.heydusk.mimi.feature.parenting.model.ParentingWorkspaceModel
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 界面能对账本做的全部写入。每个方法返回 true = 账本已确认；false = 没写成（错误在 model.error，表单保持打开、输入保留）。
 * 界面只在家长点「保存 / 确认」后才调用这些方法——草稿只活在表单里。
 */
interface ParentingActions {
    suspend fun addChild(name: String): Boolean
    suspend fun renameChild(id: UUID, name: String): Boolean
    suspend fun deleteChild(id: UUID): Boolean
    suspend fun addGoal(childID: UUID, title: String): Boolean
    suspend fun deleteGoal(id: UUID): Boolean
    suspend fun addArrangement(childID: UUID, draft: ArrangementDraft): Boolean
    /** 调整一条未完成的安排；`conflictIDs` 是同一分钟里已安排的其他事（家长已看到并确认）。 */
    suspend fun reviseArrangement(before: ParentingArrangement, draft: ArrangementDraft, conflictIDs: List<UUID>): Boolean
    suspend fun undoAdjustment(editID: UUID, conflictIDs: List<UUID>): Boolean
    suspend fun confirmArrangement(id: UUID): Boolean
    suspend fun cancelArrangement(id: UUID): Boolean
    suspend fun deleteArrangement(id: UUID): Boolean
    /** 一键完成（不带备注），随后有短暂撤销。 */
    suspend fun completeOneTap(id: UUID): Boolean
    suspend fun completeWithNote(id: UUID, note: String): Boolean
    suspend fun undoCompletion(): Boolean
    suspend fun addObservation(childID: UUID, goalID: UUID?, text: String): Boolean
    suspend fun editRecord(id: UUID, text: String): Boolean
    suspend fun deleteRecord(id: UUID): Boolean
    fun clearError()

    /**
     * 自然输入确认卡「确认」之后逐条执行的命令（创建→确认安排、观察、改期）。`operationID` 是账本的幂等键：
     * 部分失败后重试同一步不会重复写入。默认不写（预览用）。
     */
    suspend fun applyCommand(command: ParentingCommand, operationID: UUID): Boolean = false

    /** 账本最近一次写失败的文案（没有则 null）；确认卡失败时显示。 */
    fun lastError(): String? = null

    /** 什么都不写：预览与只看界面的测试用。 */
    object None : ParentingActions by NoActions
}

private object NoActions : ParentingActions {
    override suspend fun addChild(name: String) = false
    override suspend fun renameChild(id: UUID, name: String) = false
    override suspend fun deleteChild(id: UUID) = false
    override suspend fun addGoal(childID: UUID, title: String) = false
    override suspend fun deleteGoal(id: UUID) = false
    override suspend fun addArrangement(childID: UUID, draft: ArrangementDraft) = false
    override suspend fun reviseArrangement(before: ParentingArrangement, draft: ArrangementDraft, conflictIDs: List<UUID>) = false
    override suspend fun undoAdjustment(editID: UUID, conflictIDs: List<UUID>) = false
    override suspend fun confirmArrangement(id: UUID) = false
    override suspend fun cancelArrangement(id: UUID) = false
    override suspend fun deleteArrangement(id: UUID) = false
    override suspend fun completeOneTap(id: UUID) = false
    override suspend fun completeWithNote(id: UUID, note: String) = false
    override suspend fun undoCompletion() = false
    override suspend fun addObservation(childID: UUID, goalID: UUID?, text: String) = false
    override suspend fun editRecord(id: UUID, text: String) = false
    override suspend fun deleteRecord(id: UUID) = false
    override fun clearError() = Unit
}

/**
 * 接到真实账本模型上。`model` 每次调用时取（退出/清除会换新模型）；写入用 NonCancellable 包起来，
 * 这样界面离开组合（旋转、切 Tab）不会把写到一半的请求取消掉。
 */
open class ModelParentingActions(
    private val model: () -> ParentingWorkspaceModel?,
    private val newID: () -> UUID = { UUID.randomUUID() },
    private val clock: () -> Instant = { Instant.now() },
) : ParentingActions {
    private suspend fun run(command: ParentingCommand): Boolean = withContext(NonCancellable) {
        model()?.apply(command) ?: false
    }

    override suspend fun addChild(name: String) = run(ParentingCommand.AddChild(newID(), name))
    override suspend fun renameChild(id: UUID, name: String) = run(ParentingCommand.RenameChild(id, name))
    override suspend fun deleteChild(id: UUID) = run(ParentingCommand.DeleteChild(id))
    override suspend fun addGoal(childID: UUID, title: String) = run(ParentingCommand.AddGoal(newID(), childID, title))
    override suspend fun deleteGoal(id: UUID) = run(ParentingCommand.DeleteGoal(id))
    override suspend fun addArrangement(childID: UUID, draft: ArrangementDraft): Boolean {
        val dueAt = draft.dueAt ?: return false
        return run(ParentingCommand.CreateDraft(newID(), childID, draft.title, dueAt, draft.goalID, draft.sourceText))
    }

    override suspend fun reviseArrangement(before: ParentingArrangement, draft: ArrangementDraft, conflictIDs: List<UUID>): Boolean {
        val m = model() ?: return false
        val dueAt = draft.dueAt ?: return false
        return run(
            ParentingCommand.ReviseArrangement(
                newID(), ParentingArrangementEditTarget(before, m.workspace.revision),
                draft.title, dueAt, draft.goalID, draft.sourceText, conflictIDs,
            ),
        )
    }

    override suspend fun undoAdjustment(editID: UUID, conflictIDs: List<UUID>) = run(ParentingCommand.UndoArrangementEdit(editID, conflictIDs))
    override suspend fun confirmArrangement(id: UUID) = run(ParentingCommand.ConfirmArrangement(id))
    override suspend fun cancelArrangement(id: UUID) = run(ParentingCommand.CancelArrangement(id))
    override suspend fun deleteArrangement(id: UUID) = run(ParentingCommand.DeleteArrangement(id))
    override suspend fun completeOneTap(id: UUID): Boolean = withContext(NonCancellable) { model()?.completeOneTap(id) != null }
    override suspend fun completeWithNote(id: UUID, note: String) = run(ParentingCommand.CompleteArrangement(id, newID(), note))
    override suspend fun undoCompletion(): Boolean = withContext(NonCancellable) {
        val m = model() ?: return@withContext false
        val undo = m.state.value.undo ?: return@withContext false
        m.undoCompletion(undo)
    }

    override suspend fun addObservation(childID: UUID, goalID: UUID?, text: String) =
        run(ParentingCommand.AddObservation(newID(), childID, goalID, text))

    override suspend fun editRecord(id: UUID, text: String) = run(ParentingCommand.EditRecord(id, text))
    override suspend fun deleteRecord(id: UUID) = run(ParentingCommand.DeleteRecord(id))
    override fun clearError() { model()?.clearError() }

    override suspend fun applyCommand(command: ParentingCommand, operationID: UUID): Boolean =
        withContext(NonCancellable) { model()?.apply(command, operationID) ?: false }

    override fun lastError(): String? = model()?.state?.value?.error
}

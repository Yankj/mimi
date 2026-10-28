package com.heydusk.mimi.feature.parenting.forms

import com.heydusk.mimi.domain.parenting.ParentingArrangement
import java.time.Instant
import java.util.UUID

/** 手动录入 / 编辑浮层。字段只活在内存里；点保存才交给 [com.heydusk.mimi.feature.parenting.actions.ParentingActions]。放弃 = 关掉、零写入。 */
sealed interface ManualSheet {
    data object AddChild : ManualSheet
    data class RenameChild(val id: UUID, val name: String) : ManualSheet
    data class AddGoal(val childID: UUID) : ManualSheet
    data class AddArrangement(val childID: UUID) : ManualSheet
    data class AdjustArrangement(val arrangement: ParentingArrangement) : ManualSheet
    data class AddObservation(val childID: UUID, val goalID: UUID? = null) : ManualSheet
    data class EditRecord(val id: UUID, val text: String) : ManualSheet
    data class CompleteArrangement(val arrangementID: UUID, val title: String) : ManualSheet
}

/** 需要二次确认的破坏性操作（取消安排、删除、撤销调整）。 */
sealed interface PendingConfirm {
    data class CancelArrangement(val id: UUID, val title: String) : PendingConfirm
    data class DeleteArrangement(val id: UUID, val title: String) : PendingConfirm
    data class DeleteRecord(val id: UUID) : PendingConfirm
    data class DeleteGoal(val id: UUID, val title: String) : PendingConfirm
    data class DeleteChild(val id: UUID, val name: String) : PendingConfirm
    data class UndoAdjustment(val editID: UUID, val conflictIDs: List<UUID>, val conflictTitles: List<String>) : PendingConfirm
}

data class ChildDraft(val name: String = "")
data class GoalDraft(val title: String = "")

/** `dueAt == null` 表示家长还没明确选时间——保存按钮保持禁用（与 iOS 一致：没有时间的候选不能变成安排）。 */
data class ArrangementDraft(
    val title: String = "",
    val sourceText: String = "",
    val dueAt: Instant? = null,
    val goalID: UUID? = null,
)
data class ObservationDraft(val text: String = "")
data class CompleteDraft(val note: String = "")

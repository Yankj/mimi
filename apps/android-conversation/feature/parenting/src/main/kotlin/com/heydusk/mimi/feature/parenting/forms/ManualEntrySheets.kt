package com.heydusk.mimi.feature.parenting.forms

import java.time.Instant
import java.util.UUID

/** 手动录入 sheet。字段只活在内存里；点保存才交给 ViewModel → 账本。放弃 = 关掉、零写入。 */
sealed interface ManualSheet {
    data object AddChild : ManualSheet
    data class AddGoal(val childID: UUID) : ManualSheet
    data class AddArrangement(val childID: UUID) : ManualSheet
    data class AddObservation(val childID: UUID) : ManualSheet
    data class CompleteArrangement(val arrangementID: UUID, val title: String) : ManualSheet
}

data class ChildDraft(val name: String = "")
data class GoalDraft(val title: String = "")
data class ArrangementDraft(
    val title: String = "",
    val sourceText: String = "",
    val dueAt: Instant = Instant.now().plusSeconds(2 * 3600),
    val goalID: UUID? = null,
)
data class ObservationDraft(val text: String = "")
data class CompleteDraft(val note: String = "")

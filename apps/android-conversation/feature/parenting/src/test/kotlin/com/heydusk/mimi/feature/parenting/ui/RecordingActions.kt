package com.heydusk.mimi.feature.parenting.ui

import com.heydusk.mimi.domain.parenting.ParentingArrangement
import com.heydusk.mimi.feature.parenting.actions.ParentingActions
import com.heydusk.mimi.feature.parenting.forms.ArrangementDraft
import java.util.UUID

/** 只记录调用、都报告成功的假动作：测「点哪里会回调什么」，不碰账本。 */
class RecordingActions : ParentingActions {
    val children = mutableListOf<String>()
    val goals = mutableListOf<Pair<UUID, String>>()
    val arrangements = mutableListOf<Pair<UUID, ArrangementDraft>>()
    val observations = mutableListOf<Triple<UUID, UUID?, String>>()
    val confirmed = mutableListOf<UUID>()
    val completedOneTap = mutableListOf<UUID>()
    val completedWithNote = mutableListOf<Pair<UUID, String>>()
    var undoCompletions = 0
    val commands = mutableListOf<com.heydusk.mimi.domain.parenting.ParentingCommand>()
    override suspend fun applyCommand(command: com.heydusk.mimi.domain.parenting.ParentingCommand, operationID: UUID) = commands.add(command)

    override suspend fun addChild(name: String) = children.add(name)
    override suspend fun renameChild(id: UUID, name: String) = true
    override suspend fun deleteChild(id: UUID) = true
    override suspend fun addGoal(childID: UUID, title: String) = goals.add(childID to title)
    override suspend fun deleteGoal(id: UUID) = true
    override suspend fun addArrangement(childID: UUID, draft: ArrangementDraft) = arrangements.add(childID to draft)
    override suspend fun reviseArrangement(before: ParentingArrangement, draft: ArrangementDraft, conflictIDs: List<UUID>) = true
    override suspend fun undoAdjustment(editID: UUID, conflictIDs: List<UUID>) = true
    override suspend fun confirmArrangement(id: UUID) = confirmed.add(id)
    override suspend fun cancelArrangement(id: UUID) = true
    override suspend fun deleteArrangement(id: UUID) = true
    override suspend fun completeOneTap(id: UUID) = completedOneTap.add(id)
    override suspend fun completeWithNote(id: UUID, note: String) = completedWithNote.add(id to note)
    override suspend fun undoCompletion(): Boolean { undoCompletions += 1; return true }
    override suspend fun addObservation(childID: UUID, goalID: UUID?, text: String) = observations.add(Triple(childID, goalID, text))
    override suspend fun editRecord(id: UUID, text: String) = true
    override suspend fun deleteRecord(id: UUID) = true
    override fun clearError() = Unit
}

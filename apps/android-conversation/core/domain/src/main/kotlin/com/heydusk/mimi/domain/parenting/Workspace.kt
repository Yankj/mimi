@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.DomainError
import com.heydusk.mimi.domain.common.InstantSerializer
import com.heydusk.mimi.domain.common.UuidSerializer
import com.heydusk.mimi.domain.common.containsNul
import com.heydusk.mimi.domain.common.graphemeCount
import com.heydusk.mimi.domain.common.trimSwift
import com.heydusk.mimi.domain.common.uuidString
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * 家长的本机账本（与 iOS `ParentingWorkspace` 同一套规则）。这些安排和记录不代表任何智能体任务或外部副作用。
 * 纯值类型：`applying` 返回新值，调用方先原子持久化再展示。
 */
@Serializable
data class ParentingWorkspace(
    val schemaVersion: Int = 1,
    val revision: Int = 0,
    val children: List<ParentingChild> = emptyList(),
    val goals: List<ParentingGoal> = emptyList(),
    val arrangements: List<ParentingArrangement> = emptyList(),
    val records: List<ParentingRecord> = emptyList(),
    /** 旧账本没有这个字段，缺省为空（与 iOS 的宽松解码一致）。 */
    val editHistory: List<ParentingArrangementEdit> = emptyList(),
    val suggestionsEnabled: Boolean = false,
) {
    @Throws(DomainError::class)
    fun validate() {
        if (schemaVersion != 1 || revision < 0) throw DomainError.Corrupt("育儿账本版本不支持，已保留原数据。")
        val allIDs = children.map { it.id } + goals.map { it.id } + arrangements.map { it.id } + records.map { it.id } + editHistory.map { it.id }
        if (allIDs.toSet().size != allIDs.size) throw DomainError.Corrupt("育儿账本标识重复，已停止写入。")
        val childIDs = children.map { it.id }.toSet()
        val goalsByID = goals.associateBy { it.id }
        val arrangementsByID = arrangements.associateBy { it.id }
        for (child in children) { text(child.name, 40) }
        for (goal in goals) {
            if (goal.childID !in childIDs) throw DomainError.Corrupt("育儿目标的孩子关联无效。")
            text(goal.title, 120)
        }
        for (a in arrangements) {
            references(a.childID, a.goalID, childIDs, goalsByID)
            text(a.title, 160); text(a.sourceText, 8000, allowEmpty = true)
            dates(a.createdAt, a.updatedAt)
        }
        val completionArrangementIDs = HashSet<UUID>()
        for (record in records) {
            references(record.childID, record.goalID, childIDs, goalsByID)
            text(record.text, 8000); text(record.sourceText, 8000, allowEmpty = true)
            dates(record.createdAt, record.updatedAt)
            val id = record.arrangementID
            if (id != null) {
                val arrangement = arrangementsByID[id]
                if (record.kind != ParentingRecordKind.Completion || arrangement == null || arrangement.childID != record.childID ||
                    arrangement.state != ParentingArrangementState.Completed || !completionArrangementIDs.add(id)
                ) throw DomainError.Corrupt("育儿记录的安排关联无效。")
            }
        }
        for (edit in editHistory) {
            val before = edit.before
            val after = edit.after
            val ok = arrangementsByID[before.id]?.childID == before.childID && before.id == after.id &&
                before.childID == after.childID && before.state == after.state &&
                (before.state == ParentingArrangementState.Draft || before.state == ParentingArrangementState.Planned) &&
                before.sourceText == after.sourceText && before.createdAt == after.createdAt &&
                after.updatedAt == edit.createdAt && edit.createdAt >= before.updatedAt &&
                (before.title != after.title || before.dueAt != after.dueAt || before.goalID != after.goalID)
            if (!ok) throw DomainError.Corrupt("育儿调整历史关联无效，已保留原数据。")
            for (a in listOf(before, after)) {
                references(a.childID, a.goalID, childIDs, goalsByID)
                text(a.title, 160); text(a.sourceText, 8000, allowEmpty = true)
                dates(a.createdAt, a.updatedAt)
            }
            text(edit.sourceText, 8000, allowEmpty = true)
            edit.revertedAt?.let { dates(edit.createdAt, it) }
            edit.supersededAt?.let { dates(edit.createdAt, it) }
        }
    }

    /** 纯转移：先校验、再应用、再校验；任何一步失败都不产生新值。 */
    @Throws(DomainError::class)
    fun applying(command: ParentingCommand, now: Instant): ParentingWorkspace {
        validate()
        if (revision >= Int.MAX_VALUE) throw DomainError.Invalid("育儿账本版本已达上限。")
        val n = Mutable(this)
        when (command) {
            is ParentingCommand.AddChild -> {
                n.unused(command.id); text(command.name, 40)
                n.children.add(ParentingChild(command.id, command.name.trimSwift(), now))
            }
            is ParentingCommand.RenameChild -> {
                val i = n.childIndex(command.id); text(command.name, 40)
                n.children[i] = n.children[i].copy(name = command.name.trimSwift())
            }
            is ParentingCommand.DeleteChild -> {
                n.childIndex(command.id)
                n.children.removeAll { it.id == command.id }; n.goals.removeAll { it.childID == command.id }
                n.arrangements.removeAll { it.childID == command.id }; n.records.removeAll { it.childID == command.id }
                n.editHistory.removeAll { it.before.childID == command.id }
            }
            is ParentingCommand.AddGoal -> {
                n.unused(command.id); n.childIndex(command.childID); text(command.title, 120)
                n.goals.add(ParentingGoal(command.id, command.childID, command.title.trimSwift(), now))
            }
            is ParentingCommand.DeleteGoal -> {
                if (n.goals.none { it.id == command.id }) throw DomainError.Missing
                n.goals.removeAll { it.id == command.id }
                for (i in n.arrangements.indices) if (n.arrangements[i].goalID == command.id) n.arrangements[i] = n.arrangements[i].copy(goalID = null)
                for (i in n.records.indices) if (n.records[i].goalID == command.id) n.records[i] = n.records[i].copy(goalID = null)
                n.editHistory.removeAll { it.before.goalID == command.id || it.after.goalID == command.id }
            }
            is ParentingCommand.CreateDraft -> {
                n.unused(command.id); n.checkReferences(command.childID, command.goalID)
                text(command.title, 160); text(command.sourceText, 8000, allowEmpty = true)
                n.arrangements.add(
                    ParentingArrangement(command.id, command.childID, command.title.trimSwift(), command.dueAt, command.goalID,
                        ParentingArrangementState.Draft, command.sourceText, now, now),
                )
            }
            is ParentingCommand.EditArrangement -> {
                val i = n.arrangementIndex(command.id)
                if (!n.arrangements[i].state.isOpen) throw DomainError.Invalid("仅待确认或已安排的内容可以修改。")
                n.checkReferences(n.arrangements[i].childID, command.goalID)
                text(command.title, 160)
                n.arrangements[i] = n.arrangements[i].copy(title = command.title.trimSwift(), dueAt = command.dueAt, goalID = command.goalID, updatedAt = now)
                n.supersedeEdits(command.id, now)
            }
            is ParentingCommand.ReviseArrangement -> {
                if (!command.target.matches(this)) throw DomainError.Conflict
                n.unused(command.id)
                val i = n.arrangementIndex(command.target.arrangement.id)
                val before = n.arrangements[i]
                if (!before.state.isOpen) throw DomainError.Invalid("仅待确认或已安排的内容可以修改。")
                n.checkReferences(before.childID, command.goalID)
                text(command.title, 160); text(command.sourceText, 8000, allowEmpty = true)
                val trimmedTitle = command.title.trimSwift()
                if (!(before.title != trimmedTitle || before.dueAt != command.dueAt || before.goalID != command.goalID)) {
                    throw DomainError.Invalid("请先修改安排，再确认调整。")
                }
                n.checkSameTimeAcknowledgement(command.acknowledgedConflictIDs, before.id, command.dueAt)
                val after = before.copy(title = trimmedTitle, dueAt = command.dueAt, goalID = command.goalID, updatedAt = now)
                n.arrangements[i] = after
                n.supersedeEdits(before.id, now)
                n.editHistory.add(ParentingArrangementEdit(command.id, before, after, command.sourceText, now))
            }
            is ParentingCommand.UndoArrangementEdit -> {
                val historyIndex = n.editHistory.indexOfFirst { it.id == command.id }
                if (historyIndex < 0) throw DomainError.Missing
                val edit = n.editHistory[historyIndex]
                val i = n.arrangementIndex(edit.after.id)
                if (!edit.canUndo(n.snapshot())) throw DomainError.Invalid("安排已发生其他修改，不能撤销这次调整。")
                n.checkReferences(edit.before.childID, edit.before.goalID)
                n.checkSameTimeAcknowledgement(command.acknowledgedConflictIDs, edit.before.id, edit.before.dueAt)
                n.arrangements[i] = n.arrangements[i].copy(title = edit.before.title, dueAt = edit.before.dueAt, goalID = edit.before.goalID, updatedAt = now)
                n.editHistory[historyIndex] = edit.copy(revertedAt = now)
            }
            is ParentingCommand.ConfirmArrangement -> {
                val i = n.arrangementIndex(command.id)
                if (!n.arrangements[i].state.isOpen) throw DomainError.Invalid("已取消或已完成的安排不能确认。")
                if (n.arrangements[i].state == ParentingArrangementState.Draft) {
                    n.arrangements[i] = n.arrangements[i].copy(state = ParentingArrangementState.Planned, updatedAt = now)
                    n.supersedeEdits(command.id, now)
                }
            }
            is ParentingCommand.CancelArrangement -> {
                val i = n.arrangementIndex(command.id)
                if (n.arrangements[i].state == ParentingArrangementState.Completed) throw DomainError.Invalid("已完成的安排不能取消。")
                if (n.arrangements[i].state != ParentingArrangementState.Cancelled) {
                    n.arrangements[i] = n.arrangements[i].copy(state = ParentingArrangementState.Cancelled, updatedAt = now)
                    n.supersedeEdits(command.id, now)
                }
            }
            is ParentingCommand.CompleteArrangement -> {
                val i = n.arrangementIndex(command.id)
                text(command.note, 8000, allowEmpty = true)
                val state = n.arrangements[i].state
                if (state == ParentingArrangementState.Cancelled) throw DomainError.Invalid("已取消的安排不能完成。")
                if (state != ParentingArrangementState.Completed) {
                    if (state != ParentingArrangementState.Planned) throw DomainError.Invalid("请先确认安排，再记录完成。")
                    n.unused(command.recordID)
                    val a = n.arrangements[i]
                    val trimmed = command.note.trimSwift()
                    n.records.add(
                        ParentingRecord(command.recordID, a.childID, a.goalID, command.id, ParentingRecordKind.Completion,
                            trimmed.ifEmpty { a.title }, command.note, now, now),
                    )
                    n.arrangements[i] = a.copy(state = ParentingArrangementState.Completed, updatedAt = now)
                    n.supersedeEdits(command.id, now)
                }
            }
            is ParentingCommand.ReopenArrangement -> {
                // 撤销「完成」。已经是已安排则什么都不做，双击或重试既不会失败也不会重复。
                val i = n.arrangementIndex(command.id)
                when (n.arrangements[i].state) {
                    ParentingArrangementState.Planned -> Unit
                    ParentingArrangementState.Draft -> throw DomainError.Invalid("尚未确认的安排不需要重新打开。")
                    ParentingArrangementState.Cancelled -> throw DomainError.Invalid("已取消的安排不能重新打开。")
                    ParentingArrangementState.Completed -> {
                        for (r in n.records.indices.reversed()) {
                            if (n.records[r].arrangementID != command.id) continue
                            val record = n.records[r]
                            // 自动写入且没被动过的完成记录直接消失；家长写过或改过的字保留成一条观察。
                            val parentWrote = record.sourceText.trimSwift().isNotEmpty() || record.updatedAt != record.createdAt
                            if (parentWrote) {
                                n.records[r] = record.copy(kind = ParentingRecordKind.Observation, arrangementID = null, updatedAt = now)
                            } else {
                                n.records.removeAt(r)
                            }
                        }
                        n.arrangements[i] = n.arrangements[i].copy(state = ParentingArrangementState.Planned, updatedAt = now)
                    }
                }
            }
            is ParentingCommand.DeleteArrangement -> {
                n.arrangementIndex(command.id)
                n.arrangements.removeAll { it.id == command.id }
                for (i in n.records.indices) if (n.records[i].arrangementID == command.id) n.records[i] = n.records[i].copy(arrangementID = null)
                n.editHistory.removeAll { it.before.id == command.id }
            }
            is ParentingCommand.AddObservation -> {
                n.unused(command.id); n.checkReferences(command.childID, command.goalID); text(command.text, 8000)
                n.records.add(
                    ParentingRecord(command.id, command.childID, command.goalID, null, ParentingRecordKind.Observation,
                        command.text.trimSwift(), command.text, now, now),
                )
            }
            is ParentingCommand.EditRecord -> {
                val i = n.records.indexOfFirst { it.id == command.id }
                if (i < 0) throw DomainError.Missing
                text(command.text, 8000)
                n.records[i] = n.records[i].copy(text = command.text.trimSwift(), updatedAt = now)
            }
            is ParentingCommand.DeleteRecord -> {
                if (n.records.none { it.id == command.id }) throw DomainError.Missing
                n.records.removeAll { it.id == command.id }
            }
            is ParentingCommand.SetSuggestionsEnabled -> n.suggestionsEnabled = command.enabled
            ParentingCommand.DeleteAllRecords -> {
                // 家长自己的"清空"：所有孩子以及挂在孩子下面的一切；复盘设置保留。
                n.children.clear(); n.goals.clear(); n.arrangements.clear(); n.records.clear(); n.editHistory.clear()
            }
        }
        val result = n.snapshot().copy(revision = revision + 1)
        result.validate()
        return result
    }

    /** 只比较已知的开始分钟（不推断时长是否重叠）。 */
    fun sameTimeConflicts(arrangementID: UUID, dueAt: Instant): List<ParentingArrangement> {
        val minute = Math.floorDiv(dueAt.epochSecond, 60L)
        return arrangements.filter {
            it.id != arrangementID && it.state == ParentingArrangementState.Planned &&
                Math.floorDiv(it.dueAt.epochSecond, 60L) == minute
        }.sortedWith(compareBy<ParentingArrangement> { it.dueAt }.thenBy { it.id.uuidString })
    }

    private class Mutable(w: ParentingWorkspace) {
        val base = w
        val children = w.children.toMutableList()
        val goals = w.goals.toMutableList()
        val arrangements = w.arrangements.toMutableList()
        val records = w.records.toMutableList()
        val editHistory = w.editHistory.toMutableList()
        var suggestionsEnabled = w.suggestionsEnabled

        fun snapshot() = base.copy(
            children = children.toList(), goals = goals.toList(), arrangements = arrangements.toList(),
            records = records.toList(), editHistory = editHistory.toList(), suggestionsEnabled = suggestionsEnabled,
        )

        fun unused(id: UUID) {
            if (children.any { it.id == id } || goals.any { it.id == id } || arrangements.any { it.id == id } ||
                records.any { it.id == id } || editHistory.any { it.id == id }
            ) throw DomainError.Conflict
        }

        fun supersedeEdits(arrangementID: UUID, now: Instant) {
            for (i in editHistory.indices) {
                val e = editHistory[i]
                if (e.after.id == arrangementID && e.revertedAt == null && e.supersededAt == null) editHistory[i] = e.copy(supersededAt = now)
            }
        }

        fun checkSameTimeAcknowledgement(acknowledged: List<UUID>, arrangementID: UUID, dueAt: Instant) {
            val conflicts = snapshot().sameTimeConflicts(arrangementID, dueAt).map { it.id }.toSet()
            if (acknowledged.toSet().size != acknowledged.size || acknowledged.toSet() != conflicts) {
                throw DomainError.Invalid("同一时间的安排已变化，请重新核对可能冲突的事项。")
            }
        }

        fun childIndex(id: UUID): Int = children.indexOfFirst { it.id == id }.also { if (it < 0) throw DomainError.Missing }
        fun arrangementIndex(id: UUID): Int = arrangements.indexOfFirst { it.id == id }.also { if (it < 0) throw DomainError.Missing }
        fun checkReferences(childID: UUID, goalID: UUID?) =
            references(childID, goalID, children.map { it.id }.toSet(), goals.associateBy { it.id })
    }

    companion object {
        val EMPTY = ParentingWorkspace()

        private fun references(childID: UUID, goalID: UUID?, children: Set<UUID>, goals: Map<UUID, ParentingGoal>) {
            if (childID !in children) throw DomainError.Invalid("请先选择当前账本中的孩子。")
            if (goalID != null && goals[goalID]?.childID != childID) throw DomainError.Invalid("目标必须属于当前孩子。")
        }

        private fun text(value: String, limit: Int, allowEmpty: Boolean = false) {
            val ok = value.graphemeCount() <= limit && (allowEmpty || value.trimSwift().isNotEmpty()) && !value.containsNul()
            if (!ok) throw DomainError.Invalid("内容不能为空，且不能超过${limit}字。")
        }

        private fun dates(created: Instant, updated: Instant) {
            if (updated < created) throw DomainError.Corrupt("育儿账本时间无效，已保留原数据。")
        }
    }
}

@Serializable
data class ParentingChild(val id: UUID, val name: String, val createdAt: Instant)

@Serializable
data class ParentingGoal(val id: UUID, val childID: UUID, val title: String, val createdAt: Instant)

@Serializable
enum class ParentingArrangementState {
    @SerialName("draft") Draft,
    @SerialName("planned") Planned,
    @SerialName("cancelled") Cancelled,
    @SerialName("completed") Completed,
    ;

    /** 草稿或已安排：还可以改、可以确认。 */
    val isOpen: Boolean get() = this == Draft || this == Planned
}

@Serializable
data class ParentingArrangement(
    val id: UUID,
    val childID: UUID,
    val title: String,
    val dueAt: Instant,
    val goalID: UUID?,
    val state: ParentingArrangementState,
    val sourceText: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
enum class ParentingRecordKind {
    @SerialName("observation") Observation,
    @SerialName("completion") Completion,
}

@Serializable
data class ParentingRecord(
    val id: UUID,
    val childID: UUID,
    val goalID: UUID?,
    val arrangementID: UUID?,
    val kind: ParentingRecordKind,
    val text: String,
    val sourceText: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 编辑前冻结的本地选择（模型输出永远不提供这个身份）。 */
@Serializable
data class ParentingArrangementEditTarget(val arrangement: ParentingArrangement, val workspaceRevision: Int) {
    fun matches(workspace: ParentingWorkspace): Boolean =
        workspaceRevision == workspace.revision && workspace.arrangements.firstOrNull { it.id == arrangement.id } == arrangement
}

/** 已保存的精确转移（和安排、操作回执一起加密存放）。 */
@Serializable
data class ParentingArrangementEdit(
    val id: UUID,
    val before: ParentingArrangement,
    val after: ParentingArrangement,
    val sourceText: String,
    val createdAt: Instant,
    val revertedAt: Instant? = null,
    val supersededAt: Instant? = null,
) {
    fun canUndo(workspace: ParentingWorkspace): Boolean {
        if (revertedAt != null || supersededAt != null) return false
        if (workspace.arrangements.firstOrNull { it.id == after.id } != after) return false
        if (workspace.children.none { it.id == before.childID }) return false
        val goalID = before.goalID ?: return true
        return workspace.goals.any { it.id == goalID && it.childID == before.childID }
    }
}

/** 19 条命令（iOS `ParentingCommand` 的逐条对应）。账本的 CAS + operationID 去重由数据层负责。 */
@Serializable
sealed interface ParentingCommand {
    @Serializable @SerialName("addChild") data class AddChild(val id: UUID, val name: String) : ParentingCommand
    @Serializable @SerialName("renameChild") data class RenameChild(val id: UUID, val name: String) : ParentingCommand
    @Serializable @SerialName("deleteChild") data class DeleteChild(val id: UUID) : ParentingCommand
    @Serializable @SerialName("addGoal") data class AddGoal(val id: UUID, val childID: UUID, val title: String) : ParentingCommand
    @Serializable @SerialName("deleteGoal") data class DeleteGoal(val id: UUID) : ParentingCommand
    @Serializable @SerialName("createDraft")
    data class CreateDraft(val id: UUID, val childID: UUID, val title: String, val dueAt: Instant, val goalID: UUID?, val sourceText: String) : ParentingCommand
    @Serializable @SerialName("editArrangement")
    data class EditArrangement(val id: UUID, val title: String, val dueAt: Instant, val goalID: UUID?) : ParentingCommand
    @Serializable @SerialName("reviseArrangement")
    data class ReviseArrangement(
        val id: UUID, val target: ParentingArrangementEditTarget, val title: String, val dueAt: Instant,
        val goalID: UUID?, val sourceText: String, val acknowledgedConflictIDs: List<UUID>,
    ) : ParentingCommand
    @Serializable @SerialName("undoArrangementEdit")
    data class UndoArrangementEdit(val id: UUID, val acknowledgedConflictIDs: List<UUID>) : ParentingCommand
    @Serializable @SerialName("confirmArrangement") data class ConfirmArrangement(val id: UUID) : ParentingCommand
    @Serializable @SerialName("cancelArrangement") data class CancelArrangement(val id: UUID) : ParentingCommand
    @Serializable @SerialName("completeArrangement")
    data class CompleteArrangement(val id: UUID, val recordID: UUID, val note: String) : ParentingCommand
    @Serializable @SerialName("reopenArrangement") data class ReopenArrangement(val id: UUID) : ParentingCommand
    @Serializable @SerialName("deleteArrangement") data class DeleteArrangement(val id: UUID) : ParentingCommand
    @Serializable @SerialName("addObservation")
    data class AddObservation(val id: UUID, val childID: UUID, val goalID: UUID?, val text: String) : ParentingCommand
    @Serializable @SerialName("editRecord") data class EditRecord(val id: UUID, val text: String) : ParentingCommand
    @Serializable @SerialName("deleteRecord") data class DeleteRecord(val id: UUID) : ParentingCommand
    @Serializable @SerialName("setSuggestionsEnabled") data class SetSuggestionsEnabled(val enabled: Boolean) : ParentingCommand
    @Serializable @SerialName("deleteAllRecords") data object DeleteAllRecords : ParentingCommand
}

package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.MimiTintedButton
import com.heydusk.mimi.domain.parenting.ParentingArrangementState
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.i18n.ui
import java.time.ZoneId
import java.util.UUID

/** 今天 › 全部安排：未完成的安排、手动记一件、已完成 / 已取消的历史，以及本机存储说明。 */
@Composable
fun TodayAllPage(
    workspace: ParentingWorkspace,
    childID: UUID?,
    zone: ZoneId,
    handlers: PageHandlers,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    val mine = workspace.arrangements.filter { childID == null || it.childID == childID }
    val open = mine.filter { it.state.isOpen }.sortedWith(compareBy({ it.dueAt }, { it.id.toString() }))
    val completed = mine.filter { it.state == ParentingArrangementState.Completed }.sortedByDescending { it.updatedAt }
    val cancelled = mine.filter { it.state == ParentingArrangementState.Cancelled }.sortedByDescending { it.updatedAt }
    TitledPage(ui("全部安排"), modifier.semantics { testTag = "parenting.today.allPage" }, onBack = handlers.onBack) {
        ChildFilter(workspace.children, childID, handlers.onSelectChild)
        if (childID != null) {
            MimiTintedButton(onClick = { handlers.onAddArrangement(childID) }, modifier = Modifier.semantics { testTag = "parenting.today.addArrangement" }) {
                Text(ui("记一件安排"))
            }
        }
        if (open.isEmpty()) {
            Text(ui("没有未完成的安排。"), color = colors.ink2, modifier = Modifier.semantics { testTag = "parenting.all.empty" })
        }
        open.forEach { ArrangementCard(it, workspace, zone, handlers) }
        if (completed.isNotEmpty()) {
            Disclosure(ui("已完成（{0}）", completed.size.toString()), "parenting.completedHistory") {
                completed.forEach { ArrangementCard(it, workspace, zone, handlers) }
            }
        }
        if (cancelled.isNotEmpty()) {
            Disclosure(ui("已取消（{0}）", cancelled.size.toString()), "parenting.cancelledHistory") {
                cancelled.forEach { ArrangementCard(it, workspace, zone, handlers) }
            }
        }
        Text(
            ui("本机安排不会发送提醒；它们只保存在这台手机上。"),
            color = colors.ink2,
            modifier = Modifier.semantics { testTag = "parenting.storageBoundary" },
        )
    }
}

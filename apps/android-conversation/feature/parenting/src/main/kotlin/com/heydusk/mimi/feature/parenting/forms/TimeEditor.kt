package com.heydusk.mimi.feature.parenting.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.MimiTintedButton
import com.heydusk.mimi.feature.parenting.ui.formatWhen
import com.heydusk.mimi.i18n.ui
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 当前整点之后 2 小时：新安排的起点（只是起点，要家长点「确定」才算选了时间）。 */
internal fun suggestedStart(now: Instant, zone: ZoneId): Instant =
    now.atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(2).toInstant()

/**
 * 时间编辑：一行按钮显示当前选择（没选过显示「选择时间」），点开后用 ± 步进日 / 时 / 分（15 分钟），点「确定」才采用。
 * 不用系统滚轮：Robolectric 里滚轮的动画收敛不了，真机观感留给设备验收。
 */
@Composable
internal fun TimeField(
    value: Instant?,
    zone: ZoneId,
    now: Instant,
    onConfirm: (Instant) -> Unit,
) {
    val colors = LocalMimiColors.current
    var open by remember { mutableStateOf(false) }
    var working by remember(value) { mutableStateOf(value ?: suggestedStart(now, zone)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ui("时间"), color = colors.ink2)
        MimiTintedButton(
            onClick = { working = value ?: suggestedStart(now, zone); open = !open },
            modifier = Modifier.semantics { testTag = "parenting.arrangement.time" },
        ) { Text(if (value != null) formatWhen(value, zone) else ui("选择时间")) }
        if (open) {
            Column(
                Modifier.fillMaxWidth().semantics { testTag = "parenting.timePicker" },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(formatWhen(working, zone), color = colors.ink, modifier = Modifier.semantics { testTag = "parenting.time.value" })
                Stepper("parenting.time.day", ui("日期")) { working = working.atZone(zone).plusDays(it.toLong()).toInstant() }
                Stepper("parenting.time.hour", ui("小时")) { working = working.atZone(zone).plusHours(it.toLong()).toInstant() }
                Stepper("parenting.time.minute", ui("分钟")) { working = working.atZone(zone).plusMinutes(15L * it).toInstant() }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { onConfirm(working); open = false },
                        modifier = Modifier.semantics { testTag = "parenting.time.confirm" },
                    ) { Text(ui("确定")) }
                }
            }
        }
    }
}

@Composable
private fun Stepper(tag: String, label: String, step: (Int) -> Unit) {
    val colors = LocalMimiColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = colors.ink2)
        Row {
            TextButton(onClick = { step(-1) }, modifier = Modifier.semantics { testTag = "$tag.minus" }) { Text("−") }
            TextButton(onClick = { step(1) }, modifier = Modifier.semantics { testTag = "$tag.plus" }) { Text("+") }
        }
    }
}

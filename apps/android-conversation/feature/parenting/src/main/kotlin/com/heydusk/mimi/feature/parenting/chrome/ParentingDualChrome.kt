package com.heydusk.mimi.feature.parenting.chrome

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.feature.parenting.ParentingTab
import com.heydusk.mimi.feature.parenting.ParentingTabBar
import dev.chrisbanes.haze.HazeState

/** 让同一轮点击里的连续写入读到最新态（sheet 先关再跳转）。 */
class ParentingChromeController(initial: ParentingChromeState<ParentingTab>) {
    var state by mutableStateOf(initial)
        private set

    fun replace(next: ParentingChromeState<ParentingTab>) {
        state = next
    }
}

/**
 * 同一槽里的一行：输入卡（含叶标）或三 tab，我们家只有三 tab。
 * 过渡只按预算上锁并切换槽内容；叶标与 tab 芯片的形变以后再补。
 */
@Composable
fun ParentingDualChrome(
    state: ParentingChromeState<ParentingTab>,
    showsComposer: Boolean,
    hazeState: HazeState,
    onState: (ParentingChromeState<ParentingTab>) -> Unit,
    modifier: Modifier = Modifier,
    composer: @Composable (
        typeChip: ParentingTab?,
        chipEnabled: Boolean,
        resignFocusToken: Int,
        onFocusChange: (Boolean) -> Unit,
    ) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.resignFocusToken) {
        if (state.resignFocusToken == 0) return@LaunchedEffect
        focusManager.clearFocus()
    }
    val reduceMotion = rememberChromeReduceMotion()
    SideEffect {
        if (state.reduceMotion != reduceMotion) onState(state.copy(reduceMotion = reduceMotion))
    }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(state.transitionId, state.step, state.stepMs) {
        val snapshot = state
        if (!snapshot.animating || snapshot.stepMs <= 0L) return@LaunchedEffect
        // 一帧提交一步。壁钟 300/280ms 由状态机的 stepMs 表达，界面不靠 delay：
        // Compose 测试时钟不会被 waitForIdle 推过 delay，真机手感留到 DeviceTrial 之后。
        withFrameNanos { }
        val current = latest
        if (current.transitionId != snapshot.transitionId || current.step != snapshot.step) return@LaunchedEffect
        onState(current.advance(snapshot.stepMs))
    }

    val inputEnabled = !state.animating
    Box(modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)) {
        when {
            showsComposer && state.showPickTabSlot -> ParentingTabBar(
                selected = state.tab,
                onSelect = { onState(state.selectFromPick(it, it != ParentingTab.Family)) },
                hazeState = hazeState,
                modifier = Modifier.fillMaxWidth(),
                enabled = inputEnabled,
                onDismiss = { onState(state.dismissPickTab()) },
            )
            showsComposer && state.showComposerSlot -> composer(
                if (state.typeChipVisible(true)) state.tab else null,
                inputEnabled,
                state.resignFocusToken,
                { focused -> if (focused) onState(state.onComposerFocused()) },
            )
            else -> ParentingTabBar(
                selected = state.tab,
                onSelect = { onState(state.switchSteadyTab(it)) },
                hazeState = hazeState,
                modifier = Modifier.fillMaxWidth(),
                enabled = inputEnabled,
            )
        }
    }
}

@Composable
private fun rememberChromeReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

package com.heydusk.mimi.feature.parenting.composer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import com.heydusk.mimi.voice.DictationController
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 点一下 = 开关；按住 ≥ 0.3 秒 = 按住说话（上滑发送、左滑取消）。与 iOS 同一组阈值（见 [DictationController]）。
 * 被修饰的视图在听的时候保持挂载，所以手指不会丢。TalkBack 不需要按住：语义里有点击与「取消 / 发送」自定义动作（在调用处）。
 */
@Composable
fun Modifier.holdToTalk(
    isDictating: () -> Boolean,
    /** 点一下：在听就停，不在听就开始（可能先出说明 / 权限）。 */
    onTap: () -> Unit,
    /** 按住满 0.3 秒：开始按住说话；false = 没有开始（出了说明 / 要权限 / 不可用）。 */
    onHoldStart: () -> Boolean,
    onHoldMove: (dxDp: Float, dyDp: Float) -> Unit,
    onHoldEnd: (dxDp: Float, dyDp: Float) -> Unit,
): Modifier {
    val tap = rememberUpdatedState(onTap)
    val start = rememberUpdatedState(onHoldStart)
    val move = rememberUpdatedState(onHoldMove)
    val end = rememberUpdatedState(onHoldEnd)
    val dictating = rememberUpdatedState(isDictating)
    val slop = LocalViewConfiguration.current.touchSlop
    return this.pointerInput(Unit) {
        val dp = density
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            val alreadyDictating = dictating.value()
            // 阶段一：0.3 秒内松手 = 点；移动超过阈值 = 滑动（不是点也不是按住）。
            var outcome = Outcome.Pending
            withTimeoutOrNull(DictationController.HOLD_DELAY_MILLIS) {
                while (outcome == Outcome.Pending) {
                    val event = awaitPointer(down.id) ?: run { outcome = Outcome.Cancelled; return@withTimeoutOrNull }
                    if (!event.pressed) { outcome = Outcome.Released; return@withTimeoutOrNull }
                    val delta = event.position - down.position
                    if (abs(delta.x) > slop || abs(delta.y) > slop) { outcome = Outcome.Moved }
                }
            }
            when (outcome) {
                Outcome.Released -> { tap.value(); return@awaitEachGesture }
                Outcome.Cancelled -> return@awaitEachGesture
                Outcome.Moved -> { drain(down.id); return@awaitEachGesture }
                Outcome.Pending -> Unit
            }
            // 阶段二：已经按满 0.3 秒还没松手。
            if (alreadyDictating) {
                // 在听写时按住 = 想结束：松手时当作一次点击。
                if (drain(down.id)) tap.value()
                return@awaitEachGesture
            }
            val holding = start.value()
            if (!holding) { drain(down.id); return@awaitEachGesture }
            var dx = 0f
            var dy = 0f
            while (true) {
                val event = awaitPointer(down.id) ?: break
                dx = with(dp) { (event.position.x - down.position.x).toDp().value }
                dy = with(dp) { (event.position.y - down.position.y).toDp().value }
                event.consume()
                if (!event.pressed) break
                move.value(dx, dy)
            }
            end.value(dx, dy)
        }
    }
}

private enum class Outcome { Pending, Released, Moved, Cancelled }

/** 等下一个这根手指的事件；手指没了（被系统取消）返回 null。 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitPointer(id: PointerId): androidx.compose.ui.input.pointer.PointerInputChange? {
    val event = awaitPointerEvent(PointerEventPass.Main)
    return event.changes.firstOrNull { it.id == id }
}

/** 等手指抬起；返回 true = 正常抬起，false = 被取消。 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.drain(id: PointerId): Boolean {
    while (true) {
        val change = awaitPointer(id) ?: return false
        change.consume()
        if (!change.pressed) return true
    }
}

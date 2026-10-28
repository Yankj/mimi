package com.heydusk.mimi.feature.parenting.composer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 点击目标。不用 `clickable`：它会把内部语义合并成一个带 onClick 的节点，把里面带测试标签的子节点吞掉（M2 的教训）；
 * 这里只收点击，语义由调用处自己声明。
 */
fun Modifier.tapTarget(enabled: Boolean = true, onTap: () -> Unit): Modifier =
    if (!enabled) this else this.pointerInput(onTap) { detectTapGestures { onTap() } }

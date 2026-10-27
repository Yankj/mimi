package com.heydusk.mimi.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 毛绒猫吉祥物（iOS `MascotFront/Idle/Note`；M0 先带三张，其余随功能引入）。 */
enum class MascotPose(@DrawableRes val drawable: Int) {
    Front(R.drawable.mascot_front),
    Idle(R.drawable.mascot_idle),
    Note(R.drawable.mascot_note),
}

@Composable
fun MimiMascot(pose: MascotPose, modifier: Modifier = Modifier, size: Dp = 120.dp) {
    // 装饰图：不进入读屏。
    Image(
        painter = painterResource(pose.drawable),
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}

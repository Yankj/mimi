package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag

/** 进入后台时盖住界面，避免多任务预览里露出孩子昵称与安排（对标 iOS catPrivacyShield）。 */
@Composable
fun PrivacyShield(visible: Boolean) {
    if (!visible) return
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .semantics { testTag = "parenting.privacyShield" },
    )
}

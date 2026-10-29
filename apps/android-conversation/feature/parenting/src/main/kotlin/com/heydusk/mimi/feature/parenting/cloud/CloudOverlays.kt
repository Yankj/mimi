package com.heydusk.mimi.feature.parenting.cloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.feature.parenting.forms.LabeledField
import com.heydusk.mimi.i18n.ui
import com.heydusk.mimi.network.auth.OAuthProvider

/** 同窗口浮层外壳（与表单浮层一致：不用 Dialog 窗口）。 */
@Composable
private fun OverlayCard(tag: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(24.dp).fillMaxWidth().imePadding()
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { testTag = tag },
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                content()
            }
        }
    }
}

/** 登录 / 注册 / Google / GitHub / 体验账号。登录成功自动关闭。 */
@Composable
fun LoginOverlay(account: CloudAccount, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalMimiColors.current
    val s by account.state.collectAsStateCompat()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(s.signedIn) { if (s.signedIn) onDismiss() }
    val formOK = email.trim().contains('@') && password.length >= 6 && !s.busy
    OverlayCard("parenting.login", onDismiss) {
        Text(ui("登录"), style = MaterialTheme.typography.titleLarge, color = colors.ink)
        LabeledField(ui("邮箱"), email, { if (it.length <= 254) email = it }, "parenting.login.email", singleLine = true)
        LabeledField(ui("密码"), password, { if (it.length <= 128) password = it }, "parenting.login.password", singleLine = true, secret = true)
        s.message?.let { Text(ui(it), color = if (s.messageIsError) colors.danger else colors.ink2, modifier = Modifier.semantics(mergeDescendants = true) { testTag = "parenting.login.message" }) }
        if (s.busy) Text(ui("正在登录…"), color = colors.ink2, modifier = Modifier.semantics { testTag = "parenting.login.busy" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { account.signIn(email, password) }, enabled = formOK, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { testTag = "parenting.login.signIn" }) { Text(ui("登录")) }
            OutlinedButton(onClick = { account.signUp(email, password) }, enabled = formOK, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { testTag = "parenting.login.signUp" }) { Text(ui("注册")) }
        }
        OutlinedButton(onClick = { account.beginOAuth(OAuthProvider.Google)?.let(onOpenUrl) }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { testTag = "parenting.login.google" }) { Text(ui("使用 Google 继续")) }
        OutlinedButton(onClick = { account.beginOAuth(OAuthProvider.GitHub)?.let(onOpenUrl) }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { testTag = "parenting.login.github" }) { Text(ui("使用 GitHub 继续")) }
        TextButton(onClick = account::startExperience, enabled = !s.busy, modifier = Modifier.semantics { testTag = "parenting.login.experience" }) { Text(ui("无需注册，即刻开始")) }
        Text(ui("登录后，本机的体验记录会带进这个账号；换成另一个账号时，本机记录会被清除。"), style = MaterialTheme.typography.bodySmall, color = colors.ink2)
        TextButton(onClick = onDismiss, modifier = Modifier.semantics { testTag = "parenting.login.close" }) { Text(ui("取消")) }
    }
}

/** 邀请码开通。 */
@Composable
fun EnrollmentOverlay(account: CloudAccount, onDismiss: () -> Unit) {
    val colors = LocalMimiColors.current
    val s by account.state.collectAsStateCompat()
    var code by remember { mutableStateOf("") }
    LaunchedEffect(s.enrollment) { if (s.enrollment == EnrollmentPhase.Done) onDismiss() }
    OverlayCard("parenting.enroll", { account.resetEnrollmentPhase(); onDismiss() }) {
        Text(ui("输入邀请码"), style = MaterialTheme.typography.titleLarge, color = colors.ink)
        LabeledField(ui("邀请码"), code, { if (it.length <= 64) code = it }, "parenting.enroll.code", singleLine = true)
        val note = when (val p = s.enrollment) {
            EnrollmentPhase.Invalid -> ui("邀请码无效，请检查后重试。")
            is EnrollmentPhase.RateLimited -> ui("尝试次数过多，请稍后再试。")
            EnrollmentPhase.SignedOut -> ui("登录已过期，请重新登录。")
            EnrollmentPhase.Failed -> ui("暂时无法连接，请稍后重试。")
            EnrollmentPhase.Submitting -> ui("正在提交…")
            else -> null
        }
        note?.let { Text(it, color = if (s.enrollment == EnrollmentPhase.Submitting) colors.ink2 else colors.danger, modifier = Modifier.semantics(mergeDescendants = true) { testTag = "parenting.enroll.message" }) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = { account.resetEnrollmentPhase(); onDismiss() }, modifier = Modifier.semantics { testTag = "parenting.enroll.cancel" }) { Text(ui("取消")) }
            TextButton(
                onClick = { account.submitInvite(code.trim()) },
                enabled = code.isNotBlank() && s.enrollment != EnrollmentPhase.Submitting,
                modifier = Modifier.semantics { testTag = "parenting.enroll.submit" },
            ) { Text(ui("开通")) }
        }
    }
}

@Composable
private fun kotlinx.coroutines.flow.StateFlow<CloudUi>.collectAsStateCompat() = this.collectAsState()

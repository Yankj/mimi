package com.heydusk.mimi.feature.parenting.composer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.heydusk.mimi.voice.MicrophonePermission

/** 真机的麦克风权限：系统运行时权限对话框（Activity Result）。已授予时同步回调 true；被永久拒绝时系统直接回 false，界面给「去设置」。 */
@Composable
fun rememberSystemMicrophonePermission(): MicrophonePermission {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val callback = pending.value
        pending.value = null
        callback?.invoke(granted)
    }
    return remember(context) {
        object : MicrophonePermission {
            override fun isGranted(): Boolean =
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            override fun request(onResult: (Boolean) -> Unit) {
                if (isGranted()) return onResult(true)
                pending.value = onResult
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

/** 打开本应用的系统设置页（权限被拒后的「去设置」）。 */
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** 输入卡的语音状态（随 [VoiceDeps] 重建）。权限默认用系统对话框，「去设置」默认跳本应用的系统设置页。 */
@Composable
fun rememberVoiceState(deps: VoiceDeps): ParentingVoiceState {
    val context = LocalContext.current
    val permission = deps.permission ?: rememberSystemMicrophonePermission()
    val language = com.heydusk.mimi.i18n.currentAppLanguage()
    val latest = androidx.compose.runtime.rememberUpdatedState(language)
    return remember(deps, permission) {
        val wired = VoiceDeps(
            engine = deps.engine,
            prefs = deps.prefs,
            permission = permission,
            openAppSettings = if (deps.openAppSettings === NO_OP) ({ openAppSettings(context) }) else deps.openAppSettings,
            haptic = deps.haptic,
            language = deps.language,
            noticeMillis = deps.noticeMillis,
            receiptMillis = deps.receiptMillis,
        )
        ParentingVoiceState(wired, permission) { latest.value }
    }
}

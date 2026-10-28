package com.heydusk.mimi

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heydusk.mimi.designsystem.MimiTheme
import com.heydusk.mimi.device.reminders.NotificationPermissionBridge
import com.heydusk.mimi.device.reminders.ReminderOpen
import com.heydusk.mimi.feature.parenting.ParentingHomeScreen
import com.heydusk.mimi.feature.parenting.composer.VoiceDeps
import com.heydusk.mimi.voice.AndroidSpeechSegmentSource
import com.heydusk.mimi.voice.ContinuousDictationEngine
import com.heydusk.mimi.voice.DictationController
import com.heydusk.mimi.voice.MainThreadScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var voicePrefs: DataStoreVoicePrefs
    @Inject lateinit var notificationPermission: NotificationPermissionBridge
    private val theme: ThemeViewModel by viewModels()
    private val shell: ParentingShellViewModel by viewModels()

    /** 语音输入卡的依赖：系统识别器（本机优先，没有则系统语音服务）+ 停顿续听状态机；权限用系统对话框，由界面申请。 */
    private val voiceDeps: VoiceDeps by lazy {
        VoiceDeps(
            engine = ContinuousDictationEngine(AndroidSpeechSegmentSource(applicationContext), MainThreadScheduler()),
            prefs = voicePrefs,
            haptic = { zone ->
                val view = window.decorView
                if (zone == DictationController.HoldZone.None) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                else view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            },
        )
    }

    /** 系统通知权限对话框（Android 13+）。结果交给桥，桥把它还给正在等的「打开提醒」。 */
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationPermission.onResult(granted)
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 不调用 setIntent：这里直接解析新 Intent，不需要把它换成 Activity 的启动 Intent（旋转重建也不应重复处理）。
        ReminderOpen.parse(intent)?.let(shell::openFromReminder)
    }

    override fun onDestroy() {
        notificationPermission.detach()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition {
            theme.state.value is ThemeUiState.Loading || shell.state.value.loading
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.attach { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        // 冷启动时点的通知；屏幕旋转等重建不重复处理同一个 Intent。
        if (savedInstanceState == null) ReminderOpen.parse(intent)?.let(shell::openFromReminder)
        setContent {
            val themeState by theme.state.collectAsStateWithLifecycle()
            val shellState by shell.state.collectAsStateWithLifecycle()
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> shell.setPrivacyShield(true)
                        Lifecycle.Event.ON_RESUME -> {
                            shell.setPrivacyShield(false)
                            shell.refreshReminders()
                        }
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            (themeState as? ThemeUiState.Ready)?.preferences?.let { preferences ->
                MimiTheme(palette = preferences.palette, appearance = preferences.appearance) {
                    ParentingHomeScreen(
                        preferences = preferences,
                        onPreferencesChange = theme::update,
                        workspace = shellState.workspace.takeIf { shellState.loaded },
                        loaded = shellState.loaded,
                        error = shellState.error,
                        onboardingDone = shellState.onboardingDone,
                        privacyShield = shellState.privacyShield,
                        undoSeconds = shellState.undoSeconds,
                        versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
                        versionCode = BuildConfig.VERSION_CODE,
                        actions = shell.actions,
                        onCompleteOnboarding = shell::completeOnboarding,
                        onClearData = shell::clearData,
                        onSignOut = shell::signOut,
                        voice = voiceDeps,
                        busy = shellState.busy,
                        reminders = shellState.reminders,
                        reminderActions = shell.reminderActions,
                        reminderOpen = shellState.reminderOpen,
                        onReminderOpenHandled = shell::reminderOpenHandled,
                    )
                }
            }
        }
    }
}

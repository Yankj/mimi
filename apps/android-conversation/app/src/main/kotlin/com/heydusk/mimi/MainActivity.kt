package com.heydusk.mimi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heydusk.mimi.designsystem.MimiTheme
import com.heydusk.mimi.feature.parenting.ParentingHomeScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val theme: ThemeViewModel by viewModels()
    private val shell: ParentingShellViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition {
            theme.state.value is ThemeUiState.Loading || shell.state.value.loading
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeState by theme.state.collectAsStateWithLifecycle()
            val shellState by shell.state.collectAsStateWithLifecycle()
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> shell.setPrivacyShield(true)
                        Lifecycle.Event.ON_RESUME -> shell.setPrivacyShield(false)
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
                    )
                }
            }
        }
    }
}

package com.heydusk.mimi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heydusk.mimi.designsystem.MimiTheme
import com.heydusk.mimi.feature.parenting.ParentingHomeScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val theme: ThemeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 偏好读到之前一直停在启动页：用户保存过的配色不会先闪一下默认再换。
        installSplashScreen().setKeepOnScreenCondition { theme.state.value is ThemeUiState.Loading }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by theme.state.collectAsStateWithLifecycle()
            (state as? ThemeUiState.Ready)?.preferences?.let { preferences ->
                MimiTheme(palette = preferences.palette, appearance = preferences.appearance) {
                    ParentingHomeScreen(preferences = preferences, onPreferencesChange = theme::update)
                }
            }
        }
    }
}

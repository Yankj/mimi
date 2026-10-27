package com.heydusk.mimi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.heydusk.mimi.designsystem.MimiTheme
import com.heydusk.mimi.domain.theme.ThemePreferences
import com.heydusk.mimi.feature.parenting.ParentingHomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // M0：偏好只存在内存里（旋转保留）；M1 起落到 DataStore（键 "palette" / "theme"，与 iOS 同名）。
            var preferences by rememberSaveable(stateSaver = ThemePreferencesSaver) { mutableStateOf(ThemePreferences()) }
            MimiTheme(palette = preferences.palette, appearance = preferences.appearance) {
                ParentingHomeScreen(
                    preferences = preferences,
                    onPreferencesChange = { preferences = it },
                )
            }
        }
    }
}

private val ThemePreferencesSaver = listSaver<ThemePreferences, String>(
    save = { listOf(it.palette.rawValue, it.appearance.rawValue) },
    restore = { ThemePreferences.fromRaw(it[0], it[1]) },
)

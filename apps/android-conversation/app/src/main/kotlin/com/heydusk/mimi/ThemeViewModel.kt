package com.heydusk.mimi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heydusk.mimi.data.prefs.ThemePreferencesRepository
import com.heydusk.mimi.domain.theme.ThemePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 主题界面的状态：偏好还没从 DataStore 读到时是 [Loading]（启动页保持显示，避免默认主题闪一下再换）。 */
sealed interface ThemeUiState {
    data object Loading : ThemeUiState
    data class Ready(val preferences: ThemePreferences) : ThemeUiState
}

/** 单向数据流：界面只读 [state]、只发 [update]。持久化与默认值都在仓库里。 */
@HiltViewModel
class ThemeViewModel @Inject constructor(private val repository: ThemePreferencesRepository) : ViewModel() {
    val state: StateFlow<ThemeUiState> = repository.preferences
        .map<ThemePreferences, ThemeUiState> { ThemeUiState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeUiState.Loading)

    fun update(preferences: ThemePreferences) {
        viewModelScope.launch { repository.update(preferences) }
    }
}

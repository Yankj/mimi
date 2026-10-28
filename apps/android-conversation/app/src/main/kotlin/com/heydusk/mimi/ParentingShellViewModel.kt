package com.heydusk.mimi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heydusk.mimi.domain.parenting.ParentingCompletionUndo
import com.heydusk.mimi.domain.parenting.ParentingOneTapGate
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.feature.parenting.actions.ModelParentingActions
import com.heydusk.mimi.feature.parenting.actions.ParentingActions
import com.heydusk.mimi.feature.parenting.model.ParentingSession
import com.heydusk.mimi.feature.parenting.model.ParentingWorkspaceModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ParentingShellUi(
    val loading: Boolean = true,
    val workspace: ParentingWorkspace = ParentingWorkspace.EMPTY,
    val loaded: Boolean = false,
    val error: String? = null,
    val onboardingDone: Boolean = false,
    val subjectID: UUID? = null,
    val undoSeconds: Int? = null,
    val privacyShield: Boolean = false,
    val busy: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ParentingShellViewModel @Inject constructor(
    private val session: ParentingSession,
) : ViewModel() {
    private val privacy = MutableStateFlow(false)
    private val undoTick = MutableStateFlow<Int?>(null)
    private var undoJob: Job? = null
    private val oneTap = ParentingOneTapGate()
    private val clock: () -> Instant = { Instant.now() }

    /** 账本模型的状态随 phase 切换（退出/清除会换一个新 model），所以用 flatMapLatest 跟随。 */
    private val sessionState = session.phase.flatMapLatest { phase ->
        if (phase is ParentingSession.Phase.Ready) phase.model.state.map { phase to it } else flowOf(phase to null)
    }

    val state: StateFlow<ParentingShellUi> = combine(sessionState, privacy, undoTick) { (phase, model), shield, undo ->
        when (phase) {
            is ParentingSession.Phase.Loading -> ParentingShellUi(loading = true, privacyShield = shield)
            is ParentingSession.Phase.Failed -> ParentingShellUi(loading = false, error = phase.message, privacyShield = shield)
            is ParentingSession.Phase.Ready -> ParentingShellUi(
                loading = false,
                workspace = model?.workspace ?: ParentingWorkspace.EMPTY,
                loaded = model?.loaded ?: false,
                error = model?.error,
                onboardingDone = phase.onboardingDone,
                subjectID = phase.subjectID,
                undoSeconds = undo,
                privacyShield = shield,
                busy = model?.busy ?: false,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ParentingShellUi())

    init {
        session.start()
    }

    private fun model(): ParentingWorkspaceModel? =
        (session.phase.value as? ParentingSession.Phase.Ready)?.model

    fun setPrivacyShield(visible: Boolean) {
        privacy.value = visible
    }

    fun completeOnboarding() = viewModelScope.launch { session.completeOnboarding() }

    /**
     * 界面的全部写入。一键完成带 800ms 防连点闸门与 3 秒撤销倒计时；其余直接透传到当前账本模型
     * （退出/清除会换新模型，所以每次调用都现取）。
     */
    val actions: ParentingActions = object : ModelParentingActions({ model() }) {
        override suspend fun completeOneTap(id: UUID): Boolean {
            val now = clock()
            if (!oneTap.begin(now)) return false
            val ok = super.completeOneTap(id)
            oneTap.finish(if (ok) now else null)
            if (ok) model()?.state?.value?.undo?.let { startUndoCountdown(it) }
            return ok
        }

        override suspend fun undoCompletion(): Boolean {
            val ok = super.undoCompletion()
            if (ok) {
                undoJob?.cancel()
                undoTick.value = null
            }
            return ok
        }
    }

    fun clearData() = viewModelScope.launch { session.clearCurrentLedger() }
    fun signOut() = viewModelScope.launch { session.signOutAndStartFresh() }

    private fun startUndoCountdown(undo: ParentingCompletionUndo) {
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            while (true) {
                val left = undo.remainingSeconds(clock())
                undoTick.value = left
                if (left <= 0) {
                    model()?.clearUndo()
                    undoTick.value = null
                    break
                }
                delay(200)
            }
        }
    }
}

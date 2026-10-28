package com.heydusk.mimi

import com.heydusk.mimi.data.prefs.VoicePreferencesRepository
import com.heydusk.mimi.feature.parenting.composer.VoicePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 界面同步读的「已讲过首次说明」开关，由 DataStore 支撑：启动时把存的值读进内存，写时先改内存（立刻生效）再异步落盘。
 * 它只决定第一次按麦克风时要不要先讲一句话；真正的权限永远以系统状态为准。
 */
class DataStoreVoicePrefs(
    private val repository: VoicePreferencesRepository,
    private val scope: CoroutineScope,
) : VoicePrefs {
    @Volatile private var explained = false

    init {
        scope.launch { repository.permissionExplained.collect { if (it) explained = true } }
    }

    override fun isExplained(): Boolean = explained

    override fun setExplained() {
        explained = true
        scope.launch { repository.setPermissionExplained() }
    }
}

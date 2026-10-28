package com.heydusk.mimi.device.reminders

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred

/**
 * 把「Activity 里的权限对话框」变成挂起函数。Activity 在 onCreate 里 `attach`（用 registerForActivityResult 的 launcher），
 * 结果回来时调 [onResult]。没有 Activity 在前台就返回 null（调用方不得把它当成「被拒绝」）。
 */
class NotificationPermissionBridge {
    @Volatile private var launch: (() -> Unit)? = null
    @Volatile private var waiting: CompletableDeferred<Boolean>? = null

    fun attach(launch: () -> Unit) { this.launch = launch }

    fun detach() {
        launch = null
        waiting?.complete(false)
        waiting = null
    }

    fun onResult(granted: Boolean) {
        waiting?.complete(granted)
        waiting = null
    }

    /** 弹出系统对话框并等结果；没有可用的界面时返回 null。 */
    suspend fun request(): Boolean? {
        val launcher = launch ?: return null
        val deferred = CompletableDeferred<Boolean>()
        waiting?.complete(false)
        waiting = deferred
        Handler(Looper.getMainLooper()).post { runCatching { launcher() }.onFailure { deferred.complete(false) } }
        return deferred.await()
    }
}

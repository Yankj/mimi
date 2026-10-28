package com.heydusk.mimi.device.reminders

import com.heydusk.mimi.domain.parenting.ParentingReminderPayload

/**
 * 内存里的系统通知中心（测试与预览用，生产不引用）。记录每一次调用，用来证明"写了什么、没写什么"；它**不**是系统真会发出通知的证据。
 */
class FakeReminderCenter(
    var status: NotificationAuthorization,
    private val grantOnRequest: Boolean = true,
    var exact: Boolean = true,
) : ReminderNotificationCenter {
    private val store = linkedMapOf<String, NotificationRequest>()
    var addCount = 0; private set
    var authorizationRequests = 0; private set
    val removedIDs = mutableListOf<String>()
    var failAdds = false
    /** 接受 add 调用但不保留，像拒绝的系统。 */
    var silentlyDropAdds = false
    /** add 进行中执行，比如写到一半退出登录。 */
    var onAdd: (() -> Unit)? = null

    val requests: List<NotificationRequest> get() = store.values.sortedBy { it.fireAt }

    fun seed(request: NotificationRequest) { store[request.id] = request }

    override suspend fun authorization() = status
    override suspend fun requestAuthorization(): Boolean {
        authorizationRequests += 1
        if (status == NotificationAuthorization.NotDetermined) {
            status = if (grantOnRequest) NotificationAuthorization.Authorized else NotificationAuthorization.Denied
        }
        return status == NotificationAuthorization.Authorized
    }
    override suspend fun pending() = store.values.map {
        PendingNotification(it.id, it.extras[ParentingReminderPayload.SIGNATURE_KEY], it.fireAt)
    }
    override suspend fun add(request: NotificationRequest) {
        addCount += 1
        onAdd?.invoke()
        if (failAdds) throw IllegalStateException("Synthetic notification refusal")
        if (!silentlyDropAdds) store[request.id] = request
    }
    override suspend fun remove(ids: List<String>) {
        removedIDs += ids
        ids.forEach { store.remove(it) }
    }
    override fun exactAlarms() = exact
}

class InMemoryReminderMemory : ReminderMemory {
    private var value: Map<java.util.UUID, java.time.Instant> = emptyMap()
    override fun load() = value
    override fun save(value: Map<java.util.UUID, java.time.Instant>) { this.value = value }
}

class InMemoryReminderSettingsStore(initial: com.heydusk.mimi.domain.parenting.ParentingReminderSettings = com.heydusk.mimi.domain.parenting.ParentingReminderSettings.DEFAULT) : ReminderSettingsStore {
    private var value = initial
    override fun load() = value
    override fun save(settings: com.heydusk.mimi.domain.parenting.ParentingReminderSettings) { value = settings }
    override fun erase() { value = com.heydusk.mimi.domain.parenting.ParentingReminderSettings.DEFAULT }
}

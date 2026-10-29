package com.heydusk.mimi.feature.parenting.intake

import com.heydusk.mimi.domain.parenting.ParentingIntakeRequest
import com.heydusk.mimi.network.intake.IntakeCancelReceipt
import com.heydusk.mimi.network.intake.IntakeConsentRequest
import com.heydusk.mimi.network.intake.IntakeConversation
import com.heydusk.mimi.network.intake.IntakeOutcome
import com.heydusk.mimi.network.intake.ParentingIntakeClient
import java.util.UUID

/** 整理模型看到的云端：已经绑定了账号授权与服务端作用域。测试里换成假的。 */
interface IntakeService {
    val serverScopeID: UUID
    suspend fun open(conversationID: UUID): IntakeConversation
    suspend fun consent(conversationID: UUID): IntakeConsentRequest?
    suspend fun allow(conversationID: UUID, consent: IntakeConsentRequest)
    suspend fun start(request: ParentingIntakeRequest, conversation: IntakeConversation): IntakeOutcome
    suspend fun observe(request: ParentingIntakeRequest): IntakeOutcome
    suspend fun cancel(request: ParentingIntakeRequest): IntakeCancelReceipt
    suspend fun delete(serverScopeID: UUID, conversationID: UUID)
}

class CloudIntakeService(
    private val client: ParentingIntakeClient,
    override val serverScopeID: UUID,
    private val authorization: suspend () -> String,
) : IntakeService {
    override suspend fun open(conversationID: UUID) = client.openConversation(authorization(), serverScopeID, conversationID)
    override suspend fun consent(conversationID: UUID) = client.consent(authorization(), serverScopeID, conversationID)
    override suspend fun allow(conversationID: UUID, consent: IntakeConsentRequest) = client.allowConsent(authorization(), serverScopeID, conversationID, consent)
    override suspend fun start(request: ParentingIntakeRequest, conversation: IntakeConversation) = client.start(authorization(), serverScopeID, request, conversation)
    override suspend fun observe(request: ParentingIntakeRequest) = client.observe(authorization(), serverScopeID, request)
    override suspend fun cancel(request: ParentingIntakeRequest) = client.cancel(authorization(), serverScopeID, request)
    override suspend fun delete(serverScopeID: UUID, conversationID: UUID) = client.delete(authorization(), serverScopeID, conversationID)
}

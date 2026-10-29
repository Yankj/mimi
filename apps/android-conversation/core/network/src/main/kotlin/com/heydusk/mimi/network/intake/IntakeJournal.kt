package com.heydusk.mimi.network.intake

import com.heydusk.mimi.data.ledger.LedgerBlobStore
import com.heydusk.mimi.data.ledger.LedgerCipher
import com.heydusk.mimi.domain.json.JsonValue
import com.heydusk.mimi.network.JsonIn
import com.heydusk.mimi.network.JsonOut
import com.heydusk.mimi.network.arr
import com.heydusk.mimi.network.int
import com.heydusk.mimi.network.str
import com.heydusk.mimi.network.uuidOrNull
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** 冷恢复用的本机引用（iOS `ParentingIntakeRecoveryReference`）：不含任何令牌。超过 24 小时不再恢复。 */
data class IntakeRecoveryReference(
    val requestID: UUID,
    val subjectID: UUID,
    val serverScopeID: UUID,
    val conversationID: UUID,
    val sourceText: String,
    val childID: UUID,
    val goalID: UUID?,
    val startedAt: Instant,
    val timeZoneID: String,
) {
    companion object { const val MAX_AGE_SECONDS = 24L * 60 * 60 }
}

/** 待清理的云端临时会话（不含原话）。 */
data class IntakeCleanupReference(val serverScopeID: UUID, val conversationID: UUID)

interface IntakeJournal {
    fun readRecovery(): IntakeRecoveryReference?
    fun saveRecovery(reference: IntakeRecoveryReference)
    fun deleteRecovery()
    fun readCleanup(): List<IntakeCleanupReference>
    fun saveCleanup(items: List<IntakeCleanupReference>)
}

/**
 * 每个账号一份，用该账号的账本密钥（AES-GCM，AAD 绑定主体）密封；主体被清除（退出/清数据）时密钥被删，记录随之不可读。
 * 读不出来（损坏/密钥已删）一律当作没有，并删掉坏记录。
 */
class SealedIntakeJournal(
    private val blobs: LedgerBlobStore,
    private val cipher: LedgerCipher,
    private val subjectID: UUID,
) : IntakeJournal {
    private val recoveryKey = "parenting_intake_recovery_v1_" + subjectID.toString().lowercase(Locale.ROOT).replace("-", "")
    private val cleanupKey = "parenting_intake_cleanup_v1_" + subjectID.toString().lowercase(Locale.ROOT).replace("-", "")

    override fun readRecovery(): IntakeRecoveryReference? {
        val o = open(recoveryKey) ?: return null
        return runCatching {
            IntakeRecoveryReference(
                o.str("request_id")!!.uuidOrNull()!!, o.str("subject_id")!!.uuidOrNull()!!, o.str("scope_id")!!.uuidOrNull()!!,
                o.str("conversation_id")!!.uuidOrNull()!!, o.str("source_text")!!, o.str("child_id")!!.uuidOrNull()!!,
                o.str("goal_id")?.uuidOrNull(), Instant.ofEpochSecond(o.int("started_at")!!), o.str("time_zone")!!,
            )
        }.getOrNull()?.takeIf { it.subjectID == subjectID } ?: run { runCatching { blobs.delete(recoveryKey) }; null }
    }

    override fun saveRecovery(reference: IntakeRecoveryReference) {
        seal(
            recoveryKey,
            JsonOut.obj(
                "v" to 1, "request_id" to reference.requestID.toString(), "subject_id" to reference.subjectID.toString(),
                "scope_id" to reference.serverScopeID.toString(), "conversation_id" to reference.conversationID.toString(),
                "source_text" to reference.sourceText, "child_id" to reference.childID.toString(), "goal_id" to reference.goalID?.toString(),
                "started_at" to reference.startedAt.epochSecond, "time_zone" to reference.timeZoneID,
            ),
        )
    }

    override fun deleteRecovery() = blobs.delete(recoveryKey)

    override fun readCleanup(): List<IntakeCleanupReference> {
        val o = open(cleanupKey) ?: return emptyList()
        return o.arr("items").orEmpty().mapNotNull { item ->
            val r = item as? JsonValue.Obj ?: return@mapNotNull null
            IntakeCleanupReference(r.str("scope_id")?.uuidOrNull() ?: return@mapNotNull null, r.str("conversation_id")?.uuidOrNull() ?: return@mapNotNull null)
        }
    }

    override fun saveCleanup(items: List<IntakeCleanupReference>) {
        if (items.isEmpty()) { blobs.delete(cleanupKey); return }
        seal(cleanupKey, JsonOut.obj("v" to 1, "items" to items.map { linkedMapOf("scope_id" to it.serverScopeID.toString(), "conversation_id" to it.conversationID.toString()) }))
    }

    private fun open(key: String): JsonValue.Obj? {
        val blob = runCatching { blobs.read(key) }.getOrNull() ?: return null
        val plain = runCatching { cipher.open(blob.bytes, subjectID) }.getOrNull() ?: run { runCatching { blobs.delete(key) }; return null }
        return JsonIn.parse(plain, 65_536)?.takeIf { it.int("v") == 1L }
    }

    private fun seal(key: String, json: String) {
        val sealed = cipher.seal(json.toByteArray(Charsets.UTF_8), subjectID)
        val current = blobs.read(key)
        blobs.writeIfUnchanged(key, current?.version, sealed)
    }
}

/** 测试与预览用。 */
class MemoryIntakeJournal : IntakeJournal {
    var recovery: IntakeRecoveryReference? = null
    var cleanup: List<IntakeCleanupReference> = emptyList()
    override fun readRecovery() = recovery
    override fun saveRecovery(reference: IntakeRecoveryReference) { recovery = reference }
    override fun deleteRecovery() { recovery = null }
    override fun readCleanup() = cleanup
    override fun saveCleanup(items: List<IntakeCleanupReference>) { cleanup = items }
}

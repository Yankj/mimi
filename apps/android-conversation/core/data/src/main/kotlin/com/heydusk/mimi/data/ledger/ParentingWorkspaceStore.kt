@file:UseSerializers(UuidSerializer::class)

package com.heydusk.mimi.data.ledger

import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.account.AccountToken
import com.heydusk.mimi.domain.common.DomainError
import com.heydusk.mimi.domain.common.UuidSerializer
import com.heydusk.mimi.domain.json.CanonicalJson
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json

/** iOS `DataScope` 里与账本分区相关的两个标识。 */
@Serializable
data class LedgerScope(val profileID: UUID, val workspaceID: UUID)

/**
 * 加密的育儿账本（iOS `ParentingWorkspaceStore`）。语义逐条对应：
 *
 * - 账本字段与操作回执（operationID → 命令指纹）封在**同一份**密文里，一起落盘；
 * - 写入前零落盘：只有 `apply` 的整条命令通过 reducer、封好、通过账号屏障之后才写文件；任何失败都不改磁盘；
 * - 工作区 `revision` 做乐观并发（`expectedRevision` 不符 → [DomainError.Conflict]）；
 * - 相同 operationID + 相同指纹 = 重试，原样返回当前账本，不再写入；指纹不同 → [DomainError.Invalid]；
 * - 两个 store 同时提交同一个操作：GCM 每次密文都不同，所以比较并替换败的那个读回最新账本，按指纹对账而不是比密文；
 * - 没有这条记录才是合法的空账本；读不出、解不开、版本或分区不符一律 [DomainError.Corrupt]，原数据保留、写入停止；
 * - 账号屏障：构造时捕获 token；每次读写前都校验；封存和落盘各自在 `epoch.commit(token)` 里完成，账号变了就拒绝。
 */
class ParentingWorkspaceStore(
    private val blobs: LedgerBlobStore,
    private val scope: LedgerScope,
    private val subjectID: UUID?,
    private val epoch: AccountEpoch,
    private val cipher: LedgerCipher,
    private val clock: () -> Instant = { Instant.now() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Serializable
    private data class Envelope(
        val schemaVersion: Int = 1,
        val scope: LedgerScope,
        val subjectID: UUID,
        val workspace: ParentingWorkspace,
        val operations: Map<UUID, String> = emptyMap(),
    )

    private class ReadState(val envelope: Envelope, val blob: LedgerBlob?)

    private val token: AccountToken? = epoch.capture()
    private val mutex = Mutex() // actor：同一个 store 里的读写串行

    fun isCurrentAccount(): Boolean {
        val s = subjectID ?: return false
        val t = token ?: return false
        return runCatching { UUID.fromString(t.account) }.getOrNull() == s && epoch.isValid(t)
    }

    suspend fun load(): ParentingWorkspace = mutex.withLock {
        withContext(io) {
            val subject = authorizedSubject()
            val state = read(subject)
            authorizedSubject()
            state.envelope.workspace
        }
    }

    suspend fun apply(command: ParentingCommand, expectedRevision: Int, operationID: UUID = UUID.randomUUID()): ParentingWorkspace = mutex.withLock {
        withContext(io) {
            val subject = authorizedSubject()
            val state = read(subject)
            authorizedSubject()
            val fingerprint = fingerprint(command)
            state.envelope.operations[operationID]?.let { prior ->
                if (prior != fingerprint) throw DomainError.Invalid("重复操作的内容不一致。")
                return@withContext state.envelope.workspace
            }
            if (state.envelope.workspace.revision != expectedRevision) throw DomainError.Conflict
            val next = state.envelope.workspace.applying(command, clock())
            val envelope = state.envelope.copy(workspace = next, operations = state.envelope.operations + (operationID to fingerprint))
            val plain = json.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
            // 创建密钥与封存也是跟账号绑定的持久化工作。
            val sealed = epoch.commit(token) { sealOrCorrupt(plain, subject) } ?: throw staleAccount()
            val key = preferenceKey(scope, subject)
            // 最后一道屏障与同步落盘在同一个临界区里：校验与写入之间不会插进任何挂起点。
            val outcome = epoch.commit(token) { runCatching { blobs.writeIfUnchanged(key, state.blob?.version, sealed) } }
                ?: throw DomainError.Unavailable("账号已变更，育儿账本未写入。")
            outcome.onFailure { failure ->
                // 两个 store 可能同时提交同一个操作。AES-GCM 每次都产生不同字节，所以对账加密的命令回执，而不是比较密文。
                val latest = runCatching { read(subject) }.getOrNull()
                if (latest != null && latest.envelope.operations[operationID] == fingerprint) return@withContext latest.envelope.workspace
                throw if (failure is IOException) DomainError.Unavailable("育儿账本暂时无法保存，已保留原数据。") else failure
            }
            authorizedSubject()
            next
        }
    }

    private fun sealOrCorrupt(plain: ByteArray, subject: UUID): ByteArray = try {
        cipher.seal(plain, subject)
    } catch (e: LedgerCryptoException) {
        throw DomainError.Unavailable("育儿账本暂时无法加密保存，已保留原数据。")
    }

    private fun read(subject: UUID): ReadState {
        val blob = try {
            blobs.read(preferenceKey(scope, subject))
        } catch (e: IOException) {
            throw DomainError.Corrupt("育儿账本存储格式损坏，已保留原数据并停止写入。")
        }
        authorizedSubject()
        // 只有这条记录不存在时空账本才合法。读不出来的字节永远不走这个分支。
        if (blob == null) return ReadState(Envelope(scope = scope, subjectID = subject, workspace = ParentingWorkspace.EMPTY), null)
        if (blob.bytes.isEmpty()) throw DomainError.Corrupt("育儿账本存储格式损坏，已保留原数据并停止写入。")
        val plain = (epoch.commit(token) {
            try {
                cipher.open(blob.bytes, subject)
            } catch (e: LedgerCryptoException) {
                throw DomainError.Corrupt("育儿账本暂时无法解密，已保留原数据并停止写入。")
            }
        }) ?: throw staleAccount()
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), plain.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw DomainError.Corrupt("育儿账本内容损坏，已保留原数据并停止写入。")
        } catch (e: IllegalArgumentException) {
            throw DomainError.Corrupt("育儿账本内容损坏，已保留原数据并停止写入。")
        }
        if (envelope.schemaVersion != 1 || envelope.scope != scope || envelope.subjectID != subject ||
            !envelope.operations.values.all { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
        ) throw DomainError.Corrupt("育儿账本版本或账号分区不符，已停止写入。")
        try {
            envelope.workspace.validate()
        } catch (e: DomainError) {
            throw DomainError.Corrupt("育儿账本内容损坏，已保留原数据并停止写入。")
        }
        return ReadState(envelope, blob)
    }

    private fun authorizedSubject(): UUID {
        val s = subjectID
        if (s == null || !isCurrentAccount()) throw staleAccount()
        return s
    }

    private fun staleAccount() = DomainError.Unavailable("当前账号不可用，请重新打开育儿账本。")

    companion object {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "type" }

        /** 命令的稳定指纹：规范 JSON（键按 UTF-16 排序、数字按 ECMAScript）的 SHA-256。同一命令永远得到同一个值。 */
        fun fingerprint(command: ParentingCommand): String {
            val element = json.encodeToJsonElement(ParentingCommand.serializer(), command)
            return sha256Hex(CanonicalJson.stringify(element).toByteArray(Charsets.UTF_8))
        }

        /** 与 iOS 同形："parenting_workspace:v1:" + 分区与主体的哈希。 */
        fun preferenceKey(scope: LedgerScope, subjectID: UUID): String =
            "parenting_workspace:v1:" + sha256Hex(
                "${scope.profileID.toString().lowercase(Locale.ROOT)}:${scope.workspaceID.toString().lowercase(Locale.ROOT)}:${subjectID.toString().lowercase(Locale.ROOT)}"
                    .toByteArray(Charsets.UTF_8),
            )
    }
}

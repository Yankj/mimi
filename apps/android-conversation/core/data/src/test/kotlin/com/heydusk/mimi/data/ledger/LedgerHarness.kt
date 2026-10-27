package com.heydusk.mimi.data.ledger

import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.common.DomainError
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers

/** 单测用的密钥库：JCA 内存 PKCS12 + 普通 KeyGenerator，跑与真机同一套 [KeystoreLedgerKeyProvider] 存取逻辑。 */
internal object TestKeys {
    private val password = "test".toCharArray()

    fun provider(): KeystoreLedgerKeyProvider {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        return KeystoreLedgerKeyProvider(
            ks,
            generate = { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() },
            store = { alias, key -> ks.setEntry(alias, KeyStore.SecretKeyEntry(key), KeyStore.PasswordProtection(password)) },
            keyPassword = password,
        )
    }
}

/** 让下一次写入失败（对应 iOS 的 `ParentingFailingPersistence.failNextSave`），并可在两个并发 store 的读之间设栅栏。 */
internal class FaultyBlobStore(private val delegate: LedgerBlobStore) : LedgerBlobStore {
    @Volatile var failNextWrite = false
    val writes = AtomicInteger()
    @Volatile var readBarrier: CountDownLatch? = null

    override fun read(key: String): LedgerBlob? {
        val result = delegate.read(key)
        readBarrier?.let { it.countDown(); check(it.await(10, TimeUnit.SECONDS)) { "barrier timed out" } }
        return result
    }

    override fun writeIfUnchanged(key: String, expectedVersion: String?, bytes: ByteArray): String {
        if (failNextWrite) { failNextWrite = false; throw IOException("Synthetic disk write failure") }
        return delegate.writeIfUnchanged(key, expectedVersion, bytes).also { writes.incrementAndGet() }
    }
}

/** 封存期间让账号失效，模拟"通过准入之后、真正落盘之前账号变了"（`ParentingEpochChangingCipher`）。 */
internal class EpochChangingCipher(private val inner: LedgerCipher, private val epoch: AccountEpoch) : LedgerCipher {
    override fun seal(plain: ByteArray, subjectID: UUID): ByteArray = inner.seal(plain, subjectID).also { epoch.bump() }
    override fun open(sealed: ByteArray, subjectID: UUID): ByteArray = inner.open(sealed, subjectID)
}

internal class LedgerHarness(
    val directory: File,
    val subject: UUID = LedgerFixtures.subject,
    val scope: LedgerScope = LedgerFixtures.scope,
    val epoch: AccountEpoch = AccountEpoch(subject.toString()),
    val keys: KeystoreLedgerKeyProvider = TestKeys.provider(),
) {
    val cipher: LedgerCipher = AesGcmLedgerCipher(keys)
    val file = FileLedgerBlobStore(directory)
    val blobs = FaultyBlobStore(file)
    val now: Instant = LedgerFixtures.now

    fun store(
        blobs: LedgerBlobStore = this.blobs, cipher: LedgerCipher = this.cipher, subject: UUID? = this.subject,
        epoch: AccountEpoch = this.epoch, scope: LedgerScope = this.scope,
    ) = ParentingWorkspaceStore(blobs, scope, subject, epoch, cipher, { now }, Dispatchers.IO)

    val store = store()

    /** 全新的 store + 全新的文件对象：只能从磁盘上的密文读，借不到旧对象的内存。 */
    fun reopen(): ParentingWorkspaceStore = store(blobs = FileLedgerBlobStore(directory))

    fun ledgerFiles(): List<File> = directory.listFiles()?.filter { it.name.endsWith(".sealed") }.orEmpty()
    fun onlyLedgerFile(): File = ledgerFiles().single()
    fun diskBytes(): ByteArray = onlyLedgerFile().readBytes()

    suspend fun apply(command: ParentingCommand, operationID: UUID = UUID.randomUUID()): ParentingWorkspace {
        val current = store.load()
        return store.apply(command, current.revision, operationID)
    }

    suspend fun seedDraft(linkedGoal: Boolean = false): ParentingWorkspace {
        apply(ParentingCommand.AddChild(LedgerFixtures.childID, "合成小星"))
        if (linkedGoal) apply(ParentingCommand.AddGoal(LedgerFixtures.goalID, LedgerFixtures.childID, "合成阅读目标"))
        return apply(ParentingCommand.CreateDraft(
            LedgerFixtures.itemID, LedgerFixtures.childID, "合成绘本时间", now.plusSeconds(3600),
            if (linkedGoal) LedgerFixtures.goalID else null, "合成原始输入，尚未确认",
        ))
    }
}

internal object LedgerFixtures {
    val now: Instant = Instant.ofEpochSecond(1_792_756_800) // 2026-10-23T12:00:00Z
    val subject: UUID = UUID.fromString("C1000000-0000-4000-8000-000000000001")
    val childID: UUID = UUID.fromString("C2000000-0000-4000-8000-000000000001")
    val itemID: UUID = UUID.fromString("C3000000-0000-4000-8000-000000000001")
    val goalID: UUID = UUID.fromString("C4000000-0000-4000-8000-000000000001")
    val recordID: UUID = UUID.fromString("C5000000-0000-4000-8000-000000000001")
    val scope = LedgerScope(UUID.fromString("0F000000-0000-4000-8000-000000000001"), UUID.fromString("0F000000-0000-4000-8000-000000000002"))
    fun stable(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())
}

/** 期望被拒绝；账本边界拒绝的失败都是 [DomainError]，返回它让调用方断言具体类型。 */
internal suspend fun rejects(action: suspend () -> Unit): DomainError {
    val failure = runCatching { action() }.exceptionOrNull() ?: kotlin.test.fail("Expected the production boundary to reject this action")
    return failure as? DomainError ?: kotlin.test.fail("Expected a DomainError but got $failure")
}

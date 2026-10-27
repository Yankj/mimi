package com.heydusk.mimi.data.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith

/**
 * 真正的 Android Keystore（`AndroidKeyStore` 提供者）。这些用例只能在设备或模拟器上跑
 * （`./gradlew :core:data:connectedDebugAndroidTest`）；JVM 单测里没有这个提供者，所以那边用 JCA 的 PKCS12 跑同一套逻辑。
 * 在没有设备的构建箱上只编译（`assembleDebugAndroidTest`），**没有在设备上执行过**。
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreInstrumentedTest {
    private val subjects = mutableListOf<UUID>()
    private val provider = AndroidKeystore.ledgerKeyProvider()
    private fun subject() = UUID.randomUUID().also(subjects::add)

    @After fun cleanUp() { subjects.forEach { provider.delete(it) } }

    @Test fun createsAKeyOnceAndItIsNotExtractable() {
        val s = subject()
        assertNull(provider.existingKey(s))
        val first = provider.loadOrCreateKey(s)
        val second = provider.loadOrCreateKey(s)
        assertEquals("AES", first.algorithm)
        assertNull(first.encoded, "Keystore keys never leave the secure hardware / TEE")
        assertNotNull(provider.existingKey(s))
        assertEquals(first.algorithm, second.algorithm)
    }

    @Test fun sealOpenRoundTripAndTamperDetection() {
        val s = subject()
        val cipher = AesGcmLedgerCipher(provider)
        val sealed = cipher.seal("合成账本".toByteArray(), s)
        assertEquals("合成账本", cipher.open(sealed, s).toString(Charsets.UTF_8))
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertTrue(runCatching { cipher.open(tampered, s) }.exceptionOrNull() is LedgerCryptoException)
        assertTrue(runCatching { cipher.open(sealed, subject()) }.exceptionOrNull() is LedgerCryptoException)
    }

    @Test fun ledgerSurvivesAColdProviderAndStore() {
        val s = subject()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ledger-" + UUID.randomUUID()).also { it.mkdirs() }
        try {
            val scope = LedgerScope(UUID.randomUUID(), UUID.randomUUID())
            val epoch = AccountEpoch(s.toString())
            val child = UUID.randomUUID()
            fun store() = ParentingWorkspaceStore(FileLedgerBlobStore(dir), scope, s, epoch, AesGcmLedgerCipher(AndroidKeystore.ledgerKeyProvider()))
            val saved = runBlocking { store().apply(ParentingCommand.AddChild(child, "合成"), 0) }
            val cold = runBlocking { store().load() }
            assertEquals(saved, cold)
            assertTrue(cold != ParentingWorkspace.EMPTY)
        } finally {
            dir.deleteRecursively()
        }
    }
}

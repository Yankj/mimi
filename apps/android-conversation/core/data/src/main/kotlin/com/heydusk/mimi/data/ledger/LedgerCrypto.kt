package com.heydusk.mimi.data.ledger

import java.security.GeneralSecurityException
import java.security.Key
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** 加解密失败（密钥丢失/失效、密文被改、属于别的主体）。上层一律当作"账本损坏"处理：保留原数据并停止写入。 */
class LedgerCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 与 iOS `ParentingWorkspaceCipher` 对应：可注入，让测试能走真实的仓库边界又不依赖设备密钥库。 */
interface LedgerCipher {
    @Throws(LedgerCryptoException::class)
    fun seal(plain: ByteArray, subjectID: UUID): ByteArray

    @Throws(LedgerCryptoException::class)
    fun open(sealed: ByteArray, subjectID: UUID): ByteArray
}

/** 每个主体一把密钥。账本密钥命名空间独立，普通登出清理动不到它（与 iOS 的 `catpaw.parenting.v1` 服务一样）。 */
interface LedgerKeyProvider {
    /** 取已有密钥；不存在就创建（只在写入时调用）。 */
    @Throws(LedgerCryptoException::class)
    fun loadOrCreateKey(subjectID: UUID): Key

    /** 只取已有密钥，不创建。读取一个本不存在密钥的账本不该凭空造出新密钥。 */
    @Throws(LedgerCryptoException::class)
    fun existingKey(subjectID: UUID): Key?
}

/**
 * AES-256-GCM。格式：`版本字节 0x01 | 12 字节随机 IV | 密文+16 字节标签`。
 * AAD 绑定主体：别的主体的密文即使密钥相同也打不开。IV 每次随机（与 GCM 的随机 IV 要求一致；
 * Android Keystore 的 `setRandomizedEncryptionRequired(true)` 也强制由密钥库生成 IV，所以 seal 取 `cipher.iv`）。
 */
class AesGcmLedgerCipher(private val keys: LedgerKeyProvider) : LedgerCipher {
    override fun seal(plain: ByteArray, subjectID: UUID): ByteArray {
        try {
            val key = keys.loadOrCreateKey(subjectID)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key) // IV 由提供者生成（密钥库要求）
            cipher.updateAAD(aad(subjectID))
            val iv = cipher.iv
            if (iv == null || iv.size != IV_BYTES) throw LedgerCryptoException("unexpected IV length")
            val body = cipher.doFinal(plain)
            return ByteArray(1 + IV_BYTES + body.size).also {
                it[0] = VERSION
                System.arraycopy(iv, 0, it, 1, IV_BYTES)
                System.arraycopy(body, 0, it, 1 + IV_BYTES, body.size)
            }
        } catch (e: LedgerCryptoException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw LedgerCryptoException("seal failed", e)
        } catch (e: RuntimeException) {
            throw LedgerCryptoException("seal failed", e) // 例如密钥库在某些机型上抛 ProviderException
        }
    }

    override fun open(sealed: ByteArray, subjectID: UUID): ByteArray {
        if (sealed.size < 1 + IV_BYTES + TAG_BYTES || sealed[0] != VERSION) throw LedgerCryptoException("unrecognised sealed format")
        try {
            val key = keys.existingKey(subjectID) ?: throw LedgerCryptoException("ledger key is missing")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, sealed, 1, IV_BYTES))
            cipher.updateAAD(aad(subjectID))
            return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
        } catch (e: LedgerCryptoException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw LedgerCryptoException("open failed", e)
        } catch (e: RuntimeException) {
            throw LedgerCryptoException("open failed", e)
        }
    }

    private fun aad(subjectID: UUID): ByteArray = (AAD_PREFIX + subjectID.toString().lowercase(Locale.ROOT)).toByteArray(Charsets.UTF_8)

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val AAD_PREFIX = "mimi.parenting.ledger.v1:"
    }
}

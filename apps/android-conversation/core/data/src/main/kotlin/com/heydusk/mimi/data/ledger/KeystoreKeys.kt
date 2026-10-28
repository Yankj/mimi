package com.heydusk.mimi.data.ledger

import java.security.Key
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKey

/**
 * 在一个 `KeyStore` 里按主体存取 AES 密钥。真机上 `keyStore` 是 `AndroidKeyStore`，生成器是带 `KeyGenParameterSpec` 的
 * `KeyGenerator`（见 [AndroidKeystore]）；单测里用一个 JCA 的内存 PKCS12 与普通 `KeyGenerator` 跑同一套存取逻辑。
 *
 * - 别名 = 前缀 + 小写主体 UUID：账本密钥自成命名空间；
 * - 并发创建同一把密钥时只会有一把胜出（同步 + 创建后再读回）；
 * - 密钥条目不是 `SecretKey`（被篡改或类型不对）→ 当作损坏，不覆盖。
 */
class KeystoreLedgerKeyProvider(
    private val keyStore: KeyStore,
    private val generate: (alias: String) -> SecretKey,
    private val store: (alias: String, key: SecretKey) -> Unit = { _, _ -> },
    private val aliasPrefix: String = ALIAS_PREFIX,
    /** AndroidKeyStore 的条目没有口令（null）；JCA 的 PKCS12/JCEKS 要口令，单测用。 */
    private val keyPassword: CharArray? = null,
) : LedgerKeyProvider {
    private val lock = Any()

    override fun existingKey(subjectID: UUID): Key? = synchronized(lock) { read(alias(subjectID)) }

    override fun loadOrCreateKey(subjectID: UUID): Key = synchronized(lock) {
        val alias = alias(subjectID)
        read(alias)?.let { return it }
        try {
            val created = generate(alias)
            store(alias, created) // AndroidKeyStore 的 KeyGenerator 自己落盘，store 是空操作
        } catch (e: java.security.GeneralSecurityException) {
            throw LedgerCryptoException("could not create ledger key", e)
        } catch (e: RuntimeException) {
            throw LedgerCryptoException("could not create ledger key", e)
        }
        read(alias) ?: throw LedgerCryptoException("ledger key vanished after creation")
    }

    /** 删除一个主体的账本密钥：不可恢复地让它的账本失效。普通登出不得调用；只给"永久删除全部数据"用。 */
    override fun deleteKey(subjectID: UUID) = delete(subjectID)

    fun delete(subjectID: UUID) = synchronized(lock) {
        val alias = alias(subjectID)
        try {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        } catch (e: java.security.KeyStoreException) {
            throw LedgerCryptoException("could not delete ledger key", e)
        }
    }

    private fun alias(subjectID: UUID) = aliasPrefix + subjectID.toString().lowercase(Locale.ROOT)

    private fun read(alias: String): Key? = try {
        if (!keyStore.containsAlias(alias)) null
        else (keyStore.getKey(alias, keyPassword) as? SecretKey) ?: throw LedgerCryptoException("ledger key entry is unusable")
    } catch (e: java.security.GeneralSecurityException) {
        throw LedgerCryptoException("could not read ledger key", e)
    }

    companion object {
        /** 与 iOS 的 `catpaw.parenting.v1` 服务相当：账本密钥自己的命名空间。 */
        const val ALIAS_PREFIX = "mimi.parenting.ledger.v1."
    }
}

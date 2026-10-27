package com.heydusk.mimi.data.ledger

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 真正的 Android Keystore 装配：AES-256-GCM，密钥不可导出，不要求用户验证（家长打开应用就能用，
 * 与 iOS 的 `AfterFirstUnlockThisDeviceOnly` 同一取向：只在本机、不进云备份）。
 * 优先放进 StrongBox（有专用安全芯片的机型），没有就用 TEE 密钥库。
 */
object AndroidKeystore {
    const val PROVIDER = "AndroidKeyStore"

    fun ledgerKeyProvider(): KeystoreLedgerKeyProvider {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return KeystoreLedgerKeyProvider(keyStore, generate = ::generate)
    }

    private fun generate(alias: String): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }
}

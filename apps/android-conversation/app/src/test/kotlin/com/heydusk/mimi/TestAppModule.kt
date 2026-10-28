package com.heydusk.mimi

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.heydusk.mimi.data.ledger.AesGcmLedgerCipher
import com.heydusk.mimi.data.ledger.FileLedgerBlobStore
import com.heydusk.mimi.data.ledger.KeystoreLedgerKeyProvider
import com.heydusk.mimi.data.ledger.LedgerBlobStore
import com.heydusk.mimi.data.ledger.LedgerCipher
import com.heydusk.mimi.data.ledger.LedgerKeyProvider
import com.heydusk.mimi.data.prefs.MimiDataStore
import com.heydusk.mimi.data.prefs.ThemePreferencesRepository
import com.heydusk.mimi.di.ApplicationScope
import com.heydusk.mimi.di.LedgerModule
import com.heydusk.mimi.di.PersistenceModule
import com.heydusk.mimi.domain.account.AccountEpoch
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.nio.file.Files
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 测试图：DataStore 与账本落在临时目录；Keystore 换成 JCA 内存 PKCS12。 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [PersistenceModule::class, LedgerModule::class])
object TestAppModule {
    @Provides @Singleton @ApplicationScope
    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides @Singleton
    fun dataStore(@ApplicationScope scope: CoroutineScope): DataStore<Preferences> =
        MimiDataStore.create(Files.createTempDirectory("mimi-app-test").resolve(MimiDataStore.FILE_NAME).toFile(), scope)

    @Provides @Singleton
    fun theme(store: DataStore<Preferences>) = ThemePreferencesRepository(store)

    @Provides @Singleton
    fun blobs(): LedgerBlobStore = FileLedgerBlobStore(Files.createTempDirectory("mimi-ledger-test").toFile())

    @Provides @Singleton
    fun keys(): LedgerKeyProvider {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val pw = "test".toCharArray()
        return KeystoreLedgerKeyProvider(
            ks,
            generate = { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() },
            store = { alias, key -> ks.setEntry(alias, KeyStore.SecretKeyEntry(key), KeyStore.PasswordProtection(pw)) },
            keyPassword = pw,
        )
    }

    @Provides @Singleton
    fun cipher(keys: LedgerKeyProvider): LedgerCipher = AesGcmLedgerCipher(keys)

    @Provides @Singleton
    fun epoch(): AccountEpoch = AccountEpoch()
}

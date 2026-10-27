package com.heydusk.mimi.di

import android.content.Context
import com.heydusk.mimi.data.ledger.AesGcmLedgerCipher
import com.heydusk.mimi.data.ledger.AndroidKeystore
import com.heydusk.mimi.data.ledger.FileLedgerBlobStore
import com.heydusk.mimi.data.ledger.LedgerBlobStore
import com.heydusk.mimi.data.ledger.LedgerCipher
import com.heydusk.mimi.data.ledger.LedgerKeyProvider
import com.heydusk.mimi.data.ledger.LedgerScope
import com.heydusk.mimi.data.ledger.ParentingWorkspaceStore
import com.heydusk.mimi.domain.account.AccountEpoch
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 加密账本的装配。密文放 `noBackupFilesDir`（系统备份与设备迁移都不会带走；Keystore 密钥本来也不会跟着走，
 * 所以带走密文只会得到打不开的数据）。Keystore 提供者按需创建，没人用账本时不碰 `AndroidKeyStore`。
 */
@Module
@InstallIn(SingletonComponent::class)
object LedgerModule {
    @Provides @Singleton
    fun ledgerKeys(): LedgerKeyProvider = AndroidKeystore.ledgerKeyProvider()

    @Provides @Singleton
    fun ledgerCipher(keys: Provider<LedgerKeyProvider>): LedgerCipher = AesGcmLedgerCipher(object : LedgerKeyProvider {
        override fun loadOrCreateKey(subjectID: UUID) = keys.get().loadOrCreateKey(subjectID)
        override fun existingKey(subjectID: UUID) = keys.get().existingKey(subjectID)
    })

    @Provides @Singleton
    fun ledgerBlobs(@ApplicationContext context: Context): LedgerBlobStore = FileLedgerBlobStore(File(context.noBackupFilesDir, "parenting-ledger"))

    /** 账号代际屏障：整个进程只有一个。M2 起由登录/登出驱动 `activate` / `signOut`。 */
    @Provides @Singleton
    fun accountEpoch(): AccountEpoch = AccountEpoch()
}

/** 为某个登录主体打开他的账本。分区（profile/workspace）与主体来自 M2 的登录结果。 */
@Singleton
class ParentingLedgerFactory @Inject constructor(
    private val blobs: LedgerBlobStore,
    private val cipher: LedgerCipher,
    private val epoch: AccountEpoch,
) {
    fun open(scope: LedgerScope, subjectID: UUID?): ParentingWorkspaceStore = ParentingWorkspaceStore(blobs, scope, subjectID, epoch, cipher)
}

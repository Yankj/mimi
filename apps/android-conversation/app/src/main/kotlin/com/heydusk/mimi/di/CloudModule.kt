package com.heydusk.mimi.di

import android.content.Context
import com.heydusk.mimi.BuildConfig
import com.heydusk.mimi.data.ledger.AesGcmLedgerCipher
import com.heydusk.mimi.data.ledger.AndroidKeystore
import com.heydusk.mimi.data.ledger.FileLedgerBlobStore
import com.heydusk.mimi.data.ledger.LedgerCipher
import com.heydusk.mimi.data.ledger.LedgerKeyProvider
import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.feature.parenting.cloud.CloudAccount
import com.heydusk.mimi.feature.parenting.model.ParentingSession
import com.heydusk.mimi.network.CloudConfig
import com.heydusk.mimi.network.UrlConnectionTransport
import com.heydusk.mimi.network.auth.AuthSessionManager
import com.heydusk.mimi.network.auth.SealedAuthVault
import com.heydusk.mimi.network.auth.SupabaseAuthRemote
import com.heydusk.mimi.network.enrollment.FeatureEnrollmentClient
import com.heydusk.mimi.network.intake.ParentingIntakeClient
import com.heydusk.mimi.network.intake.SealedIntakeJournal
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.UUID
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * M5 云端装配。地址/密钥来自 BuildConfig（local.properties）；缺任何一项 → 只有本机路径（[CloudAccount] 全部空操作）。
 * 令牌密文放 noBackupFilesDir/auth，用单独别名前缀的 AndroidKeyStore 密钥封存。
 */
@Module
@InstallIn(SingletonComponent::class)
object CloudModule {
    @Provides @Singleton
    fun cloudAccount(
        @ApplicationContext context: Context,
        session: ParentingSession,
        epoch: AccountEpoch,
        ledgerCipher: LedgerCipher,
        @ApplicationScope scope: CoroutineScope,
    ): CloudAccount {
        val config = CloudConfig.from(BuildConfig.MIMI_SUPABASE_URL, BuildConfig.MIMI_SUPABASE_ANON_KEY, BuildConfig.MIMI_BACKEND_ORIGIN)
        if (config == null) {
            return CloudAccount(null, null, null, session, null, null, epoch, { error("offline") }, scope)
        }
        val transport = UrlConnectionTransport()
        val remote = SupabaseAuthRemote(config, transport)
        val authKeys by lazy { AndroidKeystore.keyProvider("mimi.auth.v1.") }
        val authCipher = AesGcmLedgerCipher(object : LedgerKeyProvider {
            override fun loadOrCreateKey(subjectID: UUID) = authKeys.loadOrCreateKey(subjectID)
            override fun existingKey(subjectID: UUID) = authKeys.existingKey(subjectID)
        })
        val vault = SealedAuthVault(FileLedgerBlobStore(File(context.noBackupFilesDir, "auth")), authCipher)
        val auth = AuthSessionManager(config.issuer, remote, vault, vault)
        val intakeBlobs = FileLedgerBlobStore(File(context.noBackupFilesDir, "parenting-intake"))
        return CloudAccount(
            config = config,
            auth = auth,
            remote = remote,
            session = session,
            enrollmentClient = FeatureEnrollmentClient(config, transport),
            intakeClient = ParentingIntakeClient(config, transport),
            epoch = epoch,
            journalFor = { subject -> SealedIntakeJournal(intakeBlobs, ledgerCipher, subject) },
            scope = scope,
        ).also { it.start() }
    }
}

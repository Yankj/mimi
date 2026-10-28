package com.heydusk.mimi.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.heydusk.mimi.data.account.LocalIdentityStore
import com.heydusk.mimi.data.ledger.LedgerBlobStore
import com.heydusk.mimi.data.ledger.LedgerCipher
import com.heydusk.mimi.data.ledger.LedgerKeyProvider
import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.feature.parenting.model.ParentingSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(SingletonComponent::class)
object AccountModule {
    @Provides @Singleton
    fun localIdentity(store: DataStore<Preferences>) = LocalIdentityStore(store)

    @Provides @Singleton
    fun parentingSession(
        identity: LocalIdentityStore,
        epoch: AccountEpoch,
        blobs: LedgerBlobStore,
        cipher: LedgerCipher,
        keys: LedgerKeyProvider,
        @ApplicationScope scope: CoroutineScope,
        reminders: ParentingSession.ReminderFactory,
    ) = ParentingSession(identity, epoch, blobs, cipher, keys, scope, reminders = reminders)
}

package com.heydusk.mimi.data.account

import com.heydusk.mimi.data.prefs.MimiDataStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalIdentityStoreTest {
    @Test fun ensureSubjectIsStableUntilCleared(): Unit = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val store = MimiDataStore.create(Files.createTempDirectory("id").resolve(MimiDataStore.FILE_NAME).toFile(), scope)
            val identity = LocalIdentityStore(store)
            val a = identity.ensureSubject()
            val b = identity.ensureSubject()
            assertEquals(a, b)
            identity.clearSubject()
            assertNull(identity.snapshot.first().subjectID)
            val c = identity.ensureSubject()
            assertNotEquals(a, c)
            assertNotNull(c)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}

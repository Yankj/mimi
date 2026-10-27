package com.heydusk.mimi.domain.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** `AccountEpoch` 的屏障规则（iOS 在 ParentingWorkspaceTests 与各账号生命周期用例里间接使用）。 */
class AccountEpochTest {
    @Test fun signedOutHasNoTokenAndCannotCommit() {
        val epoch = AccountEpoch()
        assertNull(epoch.capture())
        var ran = false
        assertNull(epoch.commit(null) { ran = true })
        assertFalse(ran)
    }

    @Test fun tokenStaysValidForTheSameAccountAndDiesOnSignOutOrSwitch() {
        val epoch = AccountEpoch("a")
        val token = assertNotNull(epoch.capture())
        epoch.activate("a")
        assertTrue(epoch.isValid(token), "re-activating the same account keeps tokens valid")
        assertEquals("ok", epoch.commit(token) { "ok" })
        epoch.activate("b")
        assertFalse(epoch.isValid(token))
        val bToken = assertNotNull(epoch.capture())
        epoch.signOut()
        assertFalse(epoch.isValid(bToken))
        assertNull(epoch.currentAccount)
        epoch.activate("b")
        assertFalse(epoch.isValid(bToken), "a new sign-in is a new epoch, even for the same account")
    }

    @Test fun bumpInvalidatesWithoutChangingAccountAndCommitRunsNothingWhenStale() {
        val epoch = AccountEpoch("a")
        val token = assertNotNull(epoch.capture())
        epoch.bump()
        var wrote = false
        assertNull(epoch.commit(token) { wrote = true })
        assertFalse(wrote)
        assertEquals("a", epoch.currentAccount)
        assertTrue(epoch.isValid(epoch.capture()))
    }

    @Test fun commitIsMutuallyExclusiveWithSignOut() {
        val epoch = AccountEpoch("a")
        val token = assertNotNull(epoch.capture())
        val started = java.util.concurrent.CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val writer = Thread { epoch.commit(token) { started.countDown(); Thread.sleep(150); order.add("write") } }
        writer.start(); started.await()
        epoch.signOut(); order.add("signOut")
        writer.join()
        assertEquals(listOf("write", "signOut"), order, "sign-out waits for an in-flight commit; it never interleaves")
    }

    @Test fun observersRunInKeyOrderWithPreviousAndCurrent() = runTest {
        val observers = AccountChangeObservers()
        val seen = mutableListOf<String>()
        observers.register("z-last") { p, c -> seen.add("z:$p>$c") }
        observers.register("a-first") { p, c -> seen.add("a:$p>$c") }
        assertEquals(listOf("a-first", "z-last"), observers.registeredKeys)
        observers.notify("old", null)
        assertEquals(listOf("a:old>null", "z:old>null"), seen)
        observers.unregister("a-first")
        observers.notify("x", "y")
        assertEquals("z:x>y", seen.last())
    }
}

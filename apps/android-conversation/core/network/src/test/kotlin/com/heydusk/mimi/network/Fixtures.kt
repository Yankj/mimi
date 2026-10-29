package com.heydusk.mimi.network

import com.heydusk.mimi.data.ledger.LedgerBlob
import com.heydusk.mimi.data.ledger.LedgerBlobStore
import com.heydusk.mimi.data.ledger.LedgerCipher
import com.heydusk.mimi.network.auth.AuthCredential
import com.heydusk.mimi.network.auth.AuthLogoutBarrier
import com.heydusk.mimi.network.auth.AuthUser
import com.heydusk.mimi.network.auth.AuthVault
import java.time.Instant
import java.util.UUID
import okhttp3.mockwebserver.MockWebServer

internal fun MockWebServer.config(serverScopeID: UUID? = null): CloudConfig {
    val base = url("/").toString().trimEnd('/')
    return CloudConfig.from(base, "test-anon-key", base, allowLoopbackHttp = true)!!.copy(serverScopeID = serverScopeID)
}

internal fun tokenJson(user: UUID, anonymous: Boolean = false, access: String = "access-1", refresh: String = "refresh-1", expiresIn: Long = 3600) =
    """{"access_token":"$access","refresh_token":"$refresh","token_type":"bearer","expires_in":$expiresIn,"user":{"id":"$user","email":${if (anonymous) "null" else "\"a@example.com\""},"is_anonymous":$anonymous}}"""

internal fun userJson(user: UUID, anonymous: Boolean = false) =
    """{"id":"$user","email":${if (anonymous) "null" else "\"a@example.com\""},"is_anonymous":$anonymous}"""

internal fun credential(user: UUID, expiresAt: Instant, issuer: String = "https://p.example/auth/v1", access: String = "h.p.s", refresh: String = "r", anonymous: Boolean = false) =
    AuthCredential(issuer, AuthUser(user, if (anonymous) null else "a@example.com", anonymous), access, refresh, expiresAt)

internal class MemoryVault : AuthVault, AuthLogoutBarrier {
    var stored: AuthCredential? = null
    var pending: AuthCredential? = null
    var fence = false
    var failSave = false
    override fun load() = stored
    override fun save(credential: AuthCredential) { if (failSave) error("disk"); stored = credential }
    override fun clear() { stored = null }
    override fun loadPending() = pending
    override fun savePending(credential: AuthCredential) { pending = credential }
    override fun clearPending() { pending = null }
    override fun isSignedOut() = fence
    override fun setSignedOut(value: Boolean) { fence = value }
}

internal class MemoryBlobs : LedgerBlobStore {
    private val map = HashMap<String, Pair<ByteArray, String>>()
    private var n = 0
    override fun read(key: String): LedgerBlob? = map[key]?.let { LedgerBlob(it.first, it.second) }
    override fun delete(key: String) { map.remove(key) }
    override fun writeIfUnchanged(key: String, expectedVersion: String?, bytes: ByteArray): String {
        check(map[key]?.second == expectedVersion) { "conflict" }
        val v = "v${++n}"; map[key] = bytes to v; return v
    }
    fun raw(key: String) = map[key]?.first
    fun keys() = map.keys
}

/** 可逆但非明文的测试封存：XOR + subject 前缀，subject 不对就打不开。 */
internal class XorCipher : LedgerCipher {
    override fun seal(plain: ByteArray, subjectID: UUID): ByteArray = subjectID.toString().toByteArray() + plain.map { (it.toInt() xor 0x5a).toByte() }
    override fun open(sealed: ByteArray, subjectID: UUID): ByteArray {
        val prefix = subjectID.toString().toByteArray()
        check(sealed.size >= prefix.size && sealed.copyOfRange(0, prefix.size).contentEquals(prefix)) { "wrong key" }
        return sealed.copyOfRange(prefix.size, sealed.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}

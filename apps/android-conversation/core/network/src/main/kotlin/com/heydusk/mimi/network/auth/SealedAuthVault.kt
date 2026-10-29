package com.heydusk.mimi.network.auth

import com.heydusk.mimi.data.ledger.LedgerBlobStore
import com.heydusk.mimi.data.ledger.LedgerCipher
import com.heydusk.mimi.network.JsonIn
import com.heydusk.mimi.network.JsonOut
import com.heydusk.mimi.network.bool
import com.heydusk.mimi.network.int
import com.heydusk.mimi.network.obj
import com.heydusk.mimi.network.str
import com.heydusk.mimi.network.uuidOrNull
import java.time.Instant
import java.util.UUID

/**
 * 令牌保管箱：凭证与轮换日志用 Keystore 包裹的 AES-256-GCM 密封后落在 `noBackupFilesDir`（不进云备份/迁移）。
 * 密钥别名命名空间独立（`mimi.auth.v1.`），AAD 绑定固定的保管箱主体；账本的清除/退出动不到它，反之亦然。
 * 「已退出」屏障是明文的一个字节（不含秘密），写入同步落盘。
 */
class SealedAuthVault(
    private val blobs: LedgerBlobStore,
    private val cipher: LedgerCipher,
) : AuthVault, AuthLogoutBarrier {
    override fun load(): AuthCredential? = read(CREDENTIAL)
    override fun save(credential: AuthCredential) = write(CREDENTIAL, credential)
    override fun clear() = blobs.delete(CREDENTIAL)
    override fun loadPending(): AuthCredential? = read(PENDING)
    override fun savePending(credential: AuthCredential) = write(PENDING, credential)
    override fun clearPending() = blobs.delete(PENDING)

    override fun isSignedOut(): Boolean = blobs.read(BARRIER)?.bytes?.contentEquals(byteArrayOf(1)) == true
    override fun setSignedOut(value: Boolean) {
        val current = blobs.read(BARRIER)
        blobs.writeIfUnchanged(BARRIER, current?.version, byteArrayOf(if (value) 1 else 0))
    }

    private fun read(key: String): AuthCredential? {
        val blob = blobs.read(key) ?: return null
        val plain = cipher.open(blob.bytes, VAULT_SUBJECT)
        val o = JsonIn.parse(plain, 65_536) ?: throw IllegalStateException("vault corrupt")
        if (o.int("v") != 1L) throw IllegalStateException("vault version")
        val u = o.obj("user") ?: throw IllegalStateException("vault corrupt")
        val user = AuthUser(
            u.str("id")?.uuidOrNull() ?: throw IllegalStateException("vault corrupt"),
            u.str("email"),
            u.bool("anonymous") ?: throw IllegalStateException("vault corrupt"),
        )
        return AuthCredential(
            o.str("issuer") ?: throw IllegalStateException("vault corrupt"), user,
            o.str("access") ?: throw IllegalStateException("vault corrupt"),
            o.str("refresh") ?: throw IllegalStateException("vault corrupt"),
            Instant.ofEpochSecond(o.int("expires") ?: throw IllegalStateException("vault corrupt")),
        )
    }

    private fun write(key: String, c: AuthCredential) {
        val json = JsonOut.obj(
            "v" to 1, "issuer" to c.issuer,
            "user" to linkedMapOf("id" to c.user.id.toString(), "email" to c.user.email, "anonymous" to c.user.isAnonymous),
            "access" to c.accessToken, "refresh" to c.refreshToken, "expires" to c.expiresAt.epochSecond,
        )
        val sealed = cipher.seal(json.toByteArray(Charsets.UTF_8), VAULT_SUBJECT)
        val current = blobs.read(key)
        blobs.writeIfUnchanged(key, current?.version, sealed)
    }

    companion object {
        const val KEY_ALIAS_PREFIX = "mimi.auth.v1."
        val VAULT_SUBJECT: UUID = UUID.fromString("6d696d69-6175-7468-2d76-310000000001")
        private const val CREDENTIAL = "auth_credential_v1"
        private const val PENDING = "auth_pending_v1"
        private const val BARRIER = "auth_barrier_v1"
    }
}

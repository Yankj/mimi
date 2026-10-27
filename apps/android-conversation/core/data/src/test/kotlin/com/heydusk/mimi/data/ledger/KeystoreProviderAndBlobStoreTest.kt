package com.heydusk.mimi.data.ledger

import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** 密钥提供者的存取逻辑（真实 Keystore 的实机用例在 androidTest 里）与文件密文存放的原子/CAS 行为。 */
class KeystoreProviderAndBlobStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- 密钥提供者 ----
    @Test fun keyIsCreatedOnceAndStaysTheSameKey() {
        val p = TestKeys.provider(); val s = UUID.randomUUID()
        assertNull(p.existingKey(s), "reading never creates")
        val first = p.loadOrCreateKey(s)
        assertContentEquals(first.encoded, p.loadOrCreateKey(s).encoded)
        assertContentEquals(first.encoded, p.existingKey(s)!!.encoded)
        assertEquals("AES", first.algorithm); assertEquals(32, first.encoded.size)
    }

    @Test fun eachSubjectGetsItsOwnKeyAndDeleteIsPerSubject() {
        val p = TestKeys.provider(); val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val ka = p.loadOrCreateKey(a); val kb = p.loadOrCreateKey(b)
        assertNotEquals(ka.encoded.toList(), kb.encoded.toList())
        p.delete(a)
        assertNull(p.existingKey(a)); assertNotNull(p.existingKey(b))
        p.delete(a) // 再删一次无害
    }

    @Test fun aliasUsesTheLedgersOwnNamespaceAndLowercaseSubject() {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val pw = "x".toCharArray()
        val p = KeystoreLedgerKeyProvider(ks, { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() },
            { alias, key -> ks.setEntry(alias, KeyStore.SecretKeyEntry(key), KeyStore.PasswordProtection(pw)) }, keyPassword = pw)
        val s = UUID.fromString("ABCDEF00-0000-4000-8000-000000000001")
        p.loadOrCreateKey(s)
        assertEquals(listOf(("mimi.parenting.ledger.v1." + s.toString().lowercase())), ks.aliases().toList())
        assertEquals("mimi.parenting.ledger.v1.", KeystoreLedgerKeyProvider.ALIAS_PREFIX)
    }

    @Test fun unusableEntryOrGeneratorFailureSurfacesAsLedgerCryptoAndDoesNotOverwrite() {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val failing = KeystoreLedgerKeyProvider(ks, { throw java.security.ProviderException("hardware busy") })
        val e = assertFailsWith<LedgerCryptoException> { failing.loadOrCreateKey(UUID.randomUUID()) }
        assertTrue(e.cause is java.security.ProviderException)
        // 条目存在但读不出 SecretKey（口令不对）：读取报错，不会被当作"没有"而重建
        val pw = "x".toCharArray()
        val p = KeystoreLedgerKeyProvider(ks, { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() },
            { alias, key -> ks.setEntry(alias, KeyStore.SecretKeyEntry(key), KeyStore.PasswordProtection(pw)) }, keyPassword = "wrong".toCharArray())
        assertFailsWith<LedgerCryptoException> { p.loadOrCreateKey(UUID.randomUUID()) }
    }

    @Test fun concurrentCreationYieldsOneKey() {
        val p = TestKeys.provider(); val s = UUID.randomUUID()
        val results = java.util.Collections.synchronizedList(mutableListOf<List<Byte>>())
        val threads = List(8) { Thread { results.add(p.loadOrCreateKey(s).encoded.toList()) } }
        threads.forEach(Thread::start); threads.forEach(Thread::join)
        assertEquals(1, results.toSet().size)
    }

    // ---- 文件存放 ----
    @Test fun missingKeyIsNullNotEmpty() {
        assertNull(FileLedgerBlobStore(tmp.newFolder()).read("parenting_workspace:v1:abc"))
    }

    @Test fun writeIsCompareAndReplaceOnTheContentVersion() {
        val store = FileLedgerBlobStore(tmp.newFolder()); val key = "parenting_workspace:v1:abc"
        val v1 = store.writeIfUnchanged(key, null, "one".toByteArray())
        assertEquals(v1, store.read(key)!!.version)
        assertFailsWith<LedgerBlobConflictException>("expected-absent but present") { store.writeIfUnchanged(key, null, "x".toByteArray()) }
        assertFailsWith<LedgerBlobConflictException>("wrong version") { store.writeIfUnchanged(key, "deadbeef", "x".toByteArray()) }
        assertEquals("one", store.read(key)!!.bytes.toString(Charsets.UTF_8))
        val v2 = store.writeIfUnchanged(key, v1, "two".toByteArray())
        assertNotEquals(v1, v2); assertEquals("two", store.read(key)!!.bytes.toString(Charsets.UTF_8))
    }

    @Test fun failedWriteLeavesOldFileAndNoTempFiles() {
        val dir = tmp.newFolder(); val store = FileLedgerBlobStore(dir); val key = "k"
        val v1 = store.writeIfUnchanged(key, null, "stable".toByteArray())
        assertFailsWith<LedgerBlobConflictException> { store.writeIfUnchanged(key, "stale", "never".toByteArray()) }
        assertEquals("stable", store.read(key)!!.bytes.toString(Charsets.UTF_8))
        assertEquals(v1, store.read(key)!!.version)
        assertTrue(dir.listFiles()!!.none { ".tmp-" in it.name })
    }

    @Test fun unwritableDirectoryIsAnIoErrorNotASilentSuccess() {
        val blocker = tmp.newFile("not-a-dir")
        assertFailsWith<IOException> { FileLedgerBlobStore(java.io.File(blocker, "sub")).writeIfUnchanged("k", null, byteArrayOf(1)) }
    }

    @Test fun keysMapToSafeFileNamesAndStayDistinct() {
        val dir = tmp.newFolder(); val store = FileLedgerBlobStore(dir)
        store.writeIfUnchanged("parenting_workspace:v1:../../x", null, byteArrayOf(1))
        store.writeIfUnchanged("parenting_workspace:v1:y", null, byteArrayOf(2))
        assertEquals(2, dir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.all { it.parentFile == dir && '/' !in it.name && ':' !in it.name })
        assertSame(null, FileLedgerBlobStore(dir).read("parenting_workspace:v1:absent"))
    }
}

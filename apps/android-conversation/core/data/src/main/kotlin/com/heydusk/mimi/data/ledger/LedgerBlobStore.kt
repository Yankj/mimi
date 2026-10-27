package com.heydusk.mimi.data.ledger

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 存下来的一份密文，加上它的版本标记（内容的 SHA-256）。写入时用它做比较并替换。 */
class LedgerBlob(val bytes: ByteArray, val version: String)

/** 另一个写入者在读与写之间改了这份文件。 */
class LedgerBlobConflictException : IOException("ledger blob changed since it was read")

/**
 * 账本密文的存放处（iOS 里是 Repository 的一条偏好记录，版本号做 CAS）。
 * 两条要求：写入要么整体落盘要么完全不改（不留半截文件）；`writeIfUnchanged` 对同一个 key 是串行的比较并替换。
 */
interface LedgerBlobStore {
    /** 没有这条记录时返回 null（这才是"空账本"）。读不出来的字节由调用方判为损坏，不在这里吞掉。 */
    @Throws(IOException::class)
    fun read(key: String): LedgerBlob?

    /** `expectedVersion` 为 null 表示"期待它还不存在"。版本不符抛 [LedgerBlobConflictException]。 */
    @Throws(IOException::class)
    fun writeIfUnchanged(key: String, expectedVersion: String?, bytes: ByteArray): String
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * 文件实现：同目录临时文件 → fsync → `ATOMIC_MOVE` 覆盖 → 目录 fsync（尽力）。崩溃或断电后只会看到旧文件或新文件。
 * 同一进程内按 key 加锁做比较并替换；应用只有一个进程，所以不做跨进程文件锁。
 */
class FileLedgerBlobStore(private val directory: File) : LedgerBlobStore {
    private val locks = ConcurrentHashMap<String, Any>()

    override fun read(key: String): LedgerBlob? = synchronized(lockFor(key)) { readLocked(key) }

    override fun writeIfUnchanged(key: String, expectedVersion: String?, bytes: ByteArray): String = synchronized(lockFor(key)) {
        val current = readLocked(key)
        if (current?.version != expectedVersion) throw LedgerBlobConflictException()
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("cannot create ledger directory")
        val target = fileFor(key)
        val temp = File(directory, target.name + ".tmp-" + java.util.UUID.randomUUID())
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                throw IOException("filesystem cannot replace the ledger atomically", e)
            }
            syncDirectory()
        } finally {
            if (temp.exists()) temp.delete()
        }
        sha256Hex(bytes)
    }

    private fun readLocked(key: String): LedgerBlob? {
        val file = fileFor(key)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        return LedgerBlob(bytes, sha256Hex(bytes))
    }

    private fun lockFor(key: String) = locks.computeIfAbsent(key) { Any() }

    private fun fileFor(key: String): File {
        // key 来自 "parenting_workspace:v1:<hash>"；文件名只保留安全字符。
        val safe = key.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return File(directory, "$safe.sealed")
    }

    private fun syncDirectory() {
        try {
            java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // 某些文件系统不允许打开目录；ATOMIC_MOVE 本身已经保证原子性，目录同步只是尽力。
        } catch (_: UnsupportedOperationException) {
        }
    }
}

package dev.njr.zync.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BackupManager(
    private val dbFile: File,
    private val attachmentRoot: File,
    private val crypto: BackupCrypto = BackupCrypto(),
    private val now: () -> Long = System::currentTimeMillis,
    private val beforeSnapshot: () -> Unit = {},
) {
    fun createEncryptedBackup(passphrase: CharArray): ByteArray {
        val archive = createArchive()
        return crypto.encrypt(archive, passphrase)
    }

    fun restoreEncryptedBackup(encrypted: ByteArray, passphrase: CharArray) {
        val archive = crypto.decrypt(encrypted, passphrase)
        restoreArchive(archive)
    }

    fun createArchive(): ByteArray {
        require(dbFile.isFile) { "database file missing: ${dbFile.path}" }
        beforeSnapshot()
        val dbSnapshot = snapshotDbFiles()
        val attachments = listAttachmentFiles()
        val manifest = BackupManifest(
            version = 1,
            createdAt = now(),
            databasePath = DB_ENTRY,
            attachments = attachments.map {
                BackupAttachment(
                    relativePath = it.relativeTo(attachmentRoot).invariantSeparatorsPath,
                    size = it.length(),
                )
            },
        )

        return ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putBytes("manifest.json", Json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
                zip.putBytes(DB_ENTRY, dbSnapshot)
                for (file in attachments) {
                    val relativePath = file.relativeTo(attachmentRoot).invariantSeparatorsPath
                    zip.putFile("attachments/$relativePath", file)
                }
            }
            bytes.toByteArray()
        }
    }

    fun restoreArchive(archive: ByteArray) {
        val dbBytes = mutableMapOf<String, ByteArray>()
        val attachmentBytes = mutableMapOf<String, ByteArray>()
        var manifest: BackupManifest? = null

        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zip.readBytes()
                    when {
                        entry.name == "manifest.json" ->
                            manifest = Json.decodeFromString(BackupManifest.serializer(), bytes.toString(Charsets.UTF_8))
                        entry.name == DB_ENTRY -> dbBytes[entry.name] = bytes
                        entry.name == "$DB_ENTRY-wal" -> dbBytes[entry.name] = bytes
                        entry.name == "$DB_ENTRY-shm" -> dbBytes[entry.name] = bytes
                        entry.name.startsWith("attachments/") ->
                            attachmentBytes[entry.name.removePrefix("attachments/")] = bytes
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val m = requireNotNull(manifest) { "backup manifest missing" }
        require(m.databasePath == DB_ENTRY) { "unsupported database path ${m.databasePath}" }
        val restoredDb = requireNotNull(dbBytes[DB_ENTRY]) { "database snapshot missing" }

        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(restoredDb)
        restoreDbSidecar("-wal", dbBytes["$DB_ENTRY-wal"])
        restoreDbSidecar("-shm", dbBytes["$DB_ENTRY-shm"])
        attachmentRoot.deleteRecursively()
        attachmentRoot.mkdirs()
        for (attachment in m.attachments) {
            val bytes = requireNotNull(attachmentBytes[attachment.relativePath]) {
                "attachment missing: ${attachment.relativePath}"
            }
            val target = resolveAttachment(attachment.relativePath)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
    }

    private fun listAttachmentFiles(): List<File> {
        if (!attachmentRoot.exists()) return emptyList()
        return attachmentRoot.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(attachmentRoot).invariantSeparatorsPath }
            .toList()
    }

    private fun resolveAttachment(relativePath: String): File {
        require(relativePath.isNotBlank()) { "attachment path required" }
        require(!File(relativePath).isAbsolute) { "attachment path must be relative" }
        val root = attachmentRoot.canonicalFile
        val target = File(root, relativePath).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "attachment path escapes root" }
        return target
    }

    // [beforeSnapshot] is expected to have run a wal_checkpoint(TRUNCATE) on the
    // live database connection (see DbSnapshot.checkpointForBackup / the
    // BackupWorker factory), folding the WAL into the main .db file. We copy ONLY
    // the main file: it is now self-consistent, and in WAL mode later writes land
    // in a fresh -wal, never the main file. We deliberately do NOT archive the
    // live -wal/-shm — copying those live would reintroduce a torn read.
    private fun snapshotDbFiles(): ByteArray {
        val snapshotDir = Files.createTempDirectory("zync-backup-db-").toFile()
        return try {
            val snapshotDb = File(snapshotDir, dbFile.name)
            dbFile.copyTo(snapshotDb, overwrite = true)
            snapshotDb.readBytes()
        } finally {
            snapshotDir.deleteRecursively()
        }
    }

    private fun restoreDbSidecar(suffix: String, bytes: ByteArray?) {
        val file = File(dbFile.path + suffix)
        if (bytes == null) {
            file.delete()
        } else {
            file.writeBytes(bytes)
        }
    }

    companion object {
        private const val DB_ENTRY = "zync.db"
    }
}

@Serializable
data class BackupManifest(
    val version: Int,
    val createdAt: Long,
    val databasePath: String,
    val attachments: List<BackupAttachment>,
)

@Serializable
data class BackupAttachment(
    val relativePath: String,
    val size: Long,
)

private fun ZipOutputStream.putBytes(name: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(name))
    write(bytes)
    closeEntry()
}

private fun ZipOutputStream.putFile(name: String, file: File) {
    putNextEntry(ZipEntry(name))
    file.inputStream().use { it.copyTo(this) }
    closeEntry()
}

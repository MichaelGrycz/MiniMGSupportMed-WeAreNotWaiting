package pl.mgsupport.mglycemia

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

internal data class BackupDocument(val id: String, val name: String)

/** Narrow SAF-independent seam for exercising write/close/read-back failures without Android. */
internal interface BackupFolder {
    fun list(): List<BackupDocument>
    fun create(name: String): BackupDocument
    fun read(document: BackupDocument): InputStream
    fun write(document: BackupDocument): OutputStream
}

internal data class VerifiedBackup(val document: BackupDocument, val info: BackupCsvInfo)

internal object BackupSlots {
    val names = listOf("MGlikemia-backup-1.csv", "MGlikemia-backup-2.csv")
    const val LEGACY_NAME = "minimed_backup.csv"

    fun candidates(folder: BackupFolder): List<VerifiedBackup> {
        val files = folder.list().filter { it.name in names || it.name == LEGACY_NAME }
        requireUniqueNames(files)
        return files.mapNotNull { document ->
            val info = try { folder.read(document).use { BackupCsv.read(it.reader(Charsets.UTF_8)) } }
                catch (_: IOException) { null }
            info?.takeIf { it.errors == 0 && (document.name == LEGACY_NAME || it.version == 2) }
                ?.let { VerifiedBackup(document, it) }
        }.sortedWith(compareByDescending<VerifiedBackup> { it.info.version }
            .thenByDescending { it.info.createdMs ?: Long.MIN_VALUE })
    }

    /**
     * Never truncate the newest verified copy. Only exact document IDs explicitly owned by this
     * installation/restoration may rotate. A failed/truncated slot can be reused, but not at the
     * expense of the last good copy. Both close and read-back verification precede success.
     */
    fun write(
        folder: BackupFolder,
        ownedIds: Set<String>,
        hadSuccess: Boolean,
        nowMs: Long,
        claim: (BackupDocument) -> Unit,
        export: (OutputStream, Long) -> Unit,
    ): Long {
        val files = folder.list().filter { it.name in names }
        requireUniqueNames(files)
        if (files.any { it.id !in ownedIds }) throw IOException("Backup slot is not owned by this installation; restore the folder first")
        val valid = candidates(folder).filter { it.document.name in names }
        if (hadSuccess && valid.isEmpty()) throw IOException("No verified backup remains; select another folder or restore a valid copy")
        val good = valid.firstOrNull()
        val targetName = names.first { it != good?.document?.name }
        val target = files.firstOrNull { it.name == targetName } ?: folder.create(targetName).also {
            if (it.name != targetName) throw IOException("Provider changed the backup filename")
            claim(it) // Persist ownership before the first byte is written, including on write failure.
        }
        val newestMs = valid.maxOfOrNull { it.info.createdMs ?: 0L }
        val createdMs = if (newestMs != null && newestMs >= nowMs) Math.addExact(newestMs, 1L) else nowMs
        val expected = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(folder.write(target), expected).use { output -> export(output, createdMs) }
        val expectedBytes = expected.digest()
        val actual = MessageDigest.getInstance("SHA-256")
        val info = DigestInputStream(folder.read(target), actual).use { BackupCsv.read(it.reader(Charsets.UTF_8)) }
        if (info.version != 2 || info.createdMs != createdMs || info.errors != 0 ||
            !MessageDigest.isEqual(expectedBytes, actual.digest())
        ) throw IOException("Backup read-back verification failed")
        return createdMs
    }

    private fun requireUniqueNames(files: List<BackupDocument>) {
        if (files.map { it.name }.distinct().size != files.size) throw IOException("Duplicate backup filenames in folder")
    }
}

/** Leading attempt plus a bounded trailing attempt: repeated requests never postpone the deadline. */
internal class BackupThrottle(private val intervalMs: Long = 5 * 60_000L) {
    private var lastAttempt: Long? = null
    @Synchronized fun attempted(now: Long) { lastAttempt = now }
    @Synchronized fun delayMs(now: Long): Long = lastAttempt?.let { (intervalMs - (now - it)).coerceIn(0, intervalMs) } ?: 0
}

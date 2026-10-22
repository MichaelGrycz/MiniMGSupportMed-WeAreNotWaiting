package pl.mgsupport.mglycemia

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class BackupSlotsTest {
    @Test fun rotatesExactlyTwoSlotsAndNeverOverwritesUnownedData() {
        val folder = MemoryFolder()
        val owned = HashSet<String>()
        save(folder, owned, false, 100)
        assertEquals(1, folder.files.size)
        assertThrows(IOException::class.java) { save(folder, HashSet(), false, 200) }
        save(folder, owned, true, 200)
        save(folder, owned, true, 300)
        assertEquals(2, folder.files.size)
        assertEquals(listOf(300L, 200L), BackupSlots.candidates(folder).map { it.info.createdMs })
    }

    @Test fun failedWriteKeepsLastGoodCopyAndRetrySavesTrailingUpdate() {
        val folder = MemoryFolder()
        val owned = HashSet<String>()
        save(folder, owned, false, 100)
        val good = folder.files.getValue(BackupSlots.names[0]).copyOf()
        assertThrows(IOException::class.java) {
            BackupSlots.write(folder, owned, true, 200, { owned += it.id }) { output, _ ->
                output.write("partial".toByteArray())
                throw IOException("full disk")
            }
        }
        assertArrayEquals(good, folder.files.getValue(BackupSlots.names[0]))
        assertEquals(100L, BackupSlots.candidates(folder).first().info.createdMs)
        save(folder, owned, true, 300)
        assertEquals(300L, BackupSlots.candidates(folder).first().info.createdMs)
    }

    @Test fun closeFailureIsNotSuccessAndPriorCopyRemains() {
        val folder = MemoryFolder()
        val owned = HashSet<String>()
        save(folder, owned, false, 100)
        val good = folder.files.getValue(BackupSlots.names[0]).copyOf()
        folder.failClose = true
        assertThrows(IOException::class.java) { save(folder, owned, true, 200) }
        assertArrayEquals(good, folder.files.getValue(BackupSlots.names[0]))
    }

    @Test fun alteredReadBackFailsAndCorruptLatestFallsBackToOlderCopy() {
        val folder = MemoryFolder()
        val owned = HashSet<String>()
        save(folder, owned, false, 100)
        folder.corruptOnClose = true
        assertThrows(IOException::class.java) { save(folder, owned, true, 200) }
        assertEquals(100L, BackupSlots.candidates(folder).single().info.createdMs)
        folder.corruptOnClose = false
        save(folder, owned, true, 300)
        folder.files[BackupSlots.names[1]] = "truncated".toByteArray()
        assertEquals(100L, BackupSlots.candidates(folder).single().info.createdMs)
    }

    @Test fun refusesToOverwriteWhenAllPreviouslyGoodCopiesDisappear() {
        val folder = MemoryFolder()
        val owned = HashSet<String>()
        save(folder, owned, false, 100)
        folder.files[BackupSlots.names[0]] = "partial".toByteArray()
        assertThrows(IOException::class.java) { save(folder, owned, true, 200) }
    }

    @Test fun clockRollbackStillProducesMonotonicallyOrderedCopies() {
        val folder = MemoryFolder()
        val owned = HashSet<String>()
        save(folder, owned, false, 500)
        assertEquals(501, save(folder, owned, true, 100))
    }

    @Test fun legacyBackupIsRestorableButNeverAnOwnedRotationSlot() {
        val folder = MemoryFolder()
        val legacy = "epoch_ms,glucose_mgdl\n123,100\n".toByteArray()
        folder.files[BackupSlots.LEGACY_NAME] = legacy
        assertEquals(1, BackupSlots.candidates(folder).single().info.version)
        save(folder, HashSet(), false, 100)
        assertArrayEquals(legacy, folder.files[BackupSlots.LEGACY_NAME])
    }

    @Test fun throttleHasLeadingAndBoundedTrailingAttemptNotDebounceStarvation() {
        val throttle = BackupThrottle(300_000)
        assertEquals(0, throttle.delayMs(10))
        throttle.attempted(10)
        assertEquals(299_990, throttle.delayMs(20))
        assertEquals(1, throttle.delayMs(300_009))
        assertEquals(0, throttle.delayMs(300_010))
    }

    private fun save(folder: MemoryFolder, owned: MutableSet<String>, hadSuccess: Boolean, now: Long): Long =
        BackupSlots.write(folder, owned, hadSuccess, now, { owned += it.id }) { output, created ->
            BackupCsv.Encoder(output.writer(Charsets.UTF_8), created).finish()
        }

    private class MemoryFolder : BackupFolder {
        val files = LinkedHashMap<String, ByteArray>()
        var failClose = false
        var corruptOnClose = false
        override fun list() = files.keys.map { BackupDocument(it, it) }
        override fun create(name: String): BackupDocument {
            check(name !in files)
            files[name] = byteArrayOf()
            return BackupDocument(name, name)
        }
        override fun read(document: BackupDocument) = ByteArrayInputStream(files.getValue(document.id))
        override fun write(document: BackupDocument): OutputStream {
            files[document.id] = byteArrayOf()
            return object : ByteArrayOutputStream() {
                override fun close() {
                    files[document.id] = if (corruptOnClose) toByteArray().copyOf(5) else toByteArray()
                    if (failClose) throw IOException("provider close failed")
                }
            }
        }
    }
}

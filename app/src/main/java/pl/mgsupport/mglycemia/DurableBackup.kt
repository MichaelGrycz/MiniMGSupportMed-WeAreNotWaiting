package pl.mgsupport.mglycemia

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class BackupStatus(
    val folderLabel: String? = null,
    val lastSuccessMs: Long? = null,
    val error: String? = null,
    val enabled: Boolean = false,
)

/** Actionable refusal: restore this folder explicitly, or choose an empty one. */
class ExistingBackupFolderException : IOException("Folder is not empty. Restore its backup or choose an empty folder.")

/**
 * Opt-in, unencrypted CSV in a user-visible SAF folder (recommend Documents/MGlikemia).
 * One application-scoped instance per ReadingStore. The caller takes persistable read/write access
 * BEFORE selectFolder/restoreFromFolder. No broad storage permission and no app-private backup path.
 * Files survive uninstall, but the SAF grant/preferences do not: the user must choose and restore.
 * Automatic work runs only while this process is alive; construction performs startup catch-up.
 */
class DurableBackup(context: Context, private val store: ReadingStore) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("durable_csv_backup", Context.MODE_PRIVATE)
    private val control = Any()
    private val io = Mutex()
    private var generation = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val throttle = BackupThrottle()
    private val mutableState = MutableStateFlow(BackupStatus(
        folderLabel = preferences.getString("label", null),
        lastSuccessMs = if (preferences.contains("success")) preferences.getLong("success", 0) else null,
        enabled = preferences.getBoolean("enabled", false) && preferences.getString("tree", null) != null,
    ))
    val state: StateFlow<BackupStatus> = mutableState.asStateFlow()

    init {
        scope.launch {
            for (ignored in requests) {
                if (!state.value.enabled) continue
                delay(throttle.delayMs(SystemClock.elapsedRealtime()))
                // Drain only BEFORE the snapshot. An update arriving during the write stays pending.
                while (requests.tryReceive().isSuccess) Unit
                if (!state.value.enabled) continue
                try { backupNow() } catch (_: CancellationException) {
                    // disable() invalidates an operation, not the lifetime of this application worker.
                    currentCoroutineContext().ensureActive()
                } catch (_: Exception) {
                    // Keep a trailing retry even if no new glucose sample arrives after a failure.
                    if (state.value.enabled) requests.trySend(Unit)
                }
            }
        }
        if (state.value.enabled) requestBackup()
    }

    /** Selects only an empty directory; throws ExistingBackupFolderException without overwriting it. */
    suspend fun selectFolder(uri: Uri) = operation { token ->
        val folder = SafFolder(uri)
        val label = folder.label()
        if (folder.list().isNotEmpty()) throw ExistingBackupFolderException()
        configure(uri, label, emptySet(), token)
        requestBackup()
    }

    /** Explicit/manual save bypasses throttling. Failure updates state.error AND is thrown to caller. */
    suspend fun backupNow() = operation { token ->
        throttle.attempted(SystemClock.elapsedRealtime())
        val tree = synchronized(control) {
            if (!state.value.enabled) return@operation
            preferences.getString("tree", null) ?: throw IOException("No backup folder selected")
        }
        val folder = SafFolder(Uri.parse(tree))
        val owned = preferences.getStringSet("owned", emptySet()).orEmpty().toMutableSet()
        val success = BackupSlots.write(folder, owned,
            state.value.lastSuccessMs != null || preferences.getBoolean("restored_slots", false),
            System.currentTimeMillis(), claim = { document ->
            synchronized(control) {
                checkGeneration(token)
                owned += document.id
                if (!preferences.edit().putStringSet("owned", owned.toSet()).commit()) throw IOException("Cannot save backup ownership")
            }
        }) { output, createdMs ->
            synchronized(control) { checkGeneration(token) }
            store.exportBackupCsv(output, createdMs)
        }
        synchronized(control) {
            checkGeneration(token)
            if (!preferences.edit().putLong("success", success).commit()) throw IOException("Cannot save backup status")
            mutableState.value = state.value.copy(lastSuccessMs = success, error = null)
        }
    }

    /**
     * Verifies before import, falls back to the older good slot if the newest is corrupt, and enables
     * future rotation only after the merge commits. An existing legacy minimed_backup.csv can also
     * be restored and is never overwritten. Invalid legacy rows prevent automatic enablement.
     */
    suspend fun restoreFromFolder(uri: Uri): ImportResult = operation { token ->
        val folder = SafFolder(uri)
        val label = folder.label()
        val candidate = BackupSlots.candidates(folder).firstOrNull() ?: throw IOException("No complete CSV backup in this folder")
        synchronized(control) { checkGeneration(token) }
        val result = folder.read(candidate.document).use { store.importBackupCsv(it) }
        if (result.errors != 0) throw IOException("Backup contains invalid rows; automatic backup was not enabled")
        val owned = folder.list().filter { it.name in BackupSlots.names }.map { it.id }.toSet()
        configure(uri, label, owned, token, restoredSlots = candidate.document.name in BackupSlots.names)
        // Restoration is not a successful new backup. Preserve the files until a new copy verifies.
        requestBackup()
        result
    }

    fun requestBackup() { if (state.value.enabled) requests.trySend(Unit) }

    /** Leaves all CSV files and the user's SAF grant intact. An already-open write may finish. */
    fun disable() = synchronized(control) {
        generation++
        val saved = preferences.edit().putBoolean("enabled", false).commit()
        mutableState.value = state.value.copy(enabled = false,
            error = if (saved) null else context.getString(R.string.backup_backend_preferences_error))
    }

    private fun configure(uri: Uri, label: String, owned: Set<String>, token: Long, restoredSlots: Boolean = false) = synchronized(control) {
        checkGeneration(token)
        if (!preferences.edit().putString("tree", uri.toString()).putString("label", label)
                .putStringSet("owned", owned).putBoolean("enabled", true)
                .putBoolean("restored_slots", restoredSlots).remove("success").commit()
        ) throw IOException("Cannot save backup folder")
        mutableState.value = BackupStatus(folderLabel = label, enabled = true)
    }

    private fun checkGeneration(token: Long) {
        if (generation != token) throw CancellationException("Backup configuration was disabled")
    }

    private suspend fun <T> operation(block: suspend (Long) -> T): T {
        val token = synchronized(control) { generation }
        return withContext(Dispatchers.IO) {
            io.withLock {
                try {
                    synchronized(control) { checkGeneration(token) }
                    block(token)
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    synchronized(control) {
                        if (generation == token) mutableState.value = state.value.copy(error = when (e) {
                            is ExistingBackupFolderException -> context.getString(R.string.backup_backend_existing_folder)
                            is SecurityException -> context.getString(R.string.backup_backend_permission_error)
                            else -> context.getString(R.string.backup_backend_io_error)
                        })
                    }
                    throw e
                }
            }
        }
    }

    private inner class SafFolder(private val tree: Uri) : BackupFolder {
        private val resolver = context.contentResolver
        private val directory: Uri
        init {
            require(DocumentsContract.isTreeUri(tree)) { "Choose a document tree" }
            directory = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }
        fun label(): String = resolver.query(directory,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null,
        )?.use { c ->
            if (!c.moveToFirst() || c.getString(1) != DocumentsContract.Document.MIME_TYPE_DIR) throw IOException("Not a directory")
            c.getString(0) ?: "MGlikemia"
        } ?: throw IOException("Cannot read folder")

        override fun list(): List<BackupDocument> {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            return resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) throw IOException("Folder is still loading")
                buildList { while (c.moveToNext()) add(BackupDocument(c.getString(0), c.getString(1))) }
            } ?: throw IOException("Cannot enumerate folder")
        }
        override fun create(name: String): BackupDocument {
            val uri = DocumentsContract.createDocument(resolver, directory, "text/csv", name)
                ?: throw IOException("Cannot create backup")
            val id = DocumentsContract.getDocumentId(uri)
            return list().singleOrNull { it.id == id } ?: throw IOException("Created backup is not visible")
        }
        private fun uri(document: BackupDocument) = DocumentsContract.buildDocumentUriUsingTree(tree, document.id)
        override fun read(document: BackupDocument): InputStream = resolver.openInputStream(uri(document))
            ?: throw IOException("Cannot read backup")
        override fun write(document: BackupDocument): OutputStream = resolver.openOutputStream(uri(document), "wt")
            ?: throw IOException("Cannot open backup for writing")
    }
}

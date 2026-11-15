package io.valroot.android

import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/** Storage Access Framework selection and streamed I/O for exports and encrypted backups. */
class BackupFiles(
    activity: ComponentActivity,
) {
    val isChoosing: Boolean get() = busy

    private val resolver = activity.applicationContext.contentResolver
    private val executor = Executors.newSingleThreadExecutor()
    private var busy = false
    private var closed = false
    private var createResult: ((Uri?) -> Unit)? = null
    private var multipleResult: ((List<Uri>) -> Unit)? = null
    private var openResult: ((Uri?) -> Unit)? = null

    private val create =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_TYPES.first())) { uri ->
            finish(createResult.also { createResult = null }, uri)
        }
    private val open =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            finish(openResult.also { openResult = null }, uri)
        }

    private val openMultiple =
        activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            busy = false
            val callback = multipleResult
            multipleResult = null
            if (!closed) callback?.invoke(uris)
        }

    /** Selects export documents as a single import batch. Main thread only. */
    fun chooseExchangeExports(result: (List<Uri>) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed && !busy)
        busy = true
        multipleResult = result
        try {
            openMultiple.launch(arrayOf("*/*"))
        } catch (_: Exception) {
            busy = false
            multipleResult = null
            result(emptyList())
        }
    }

    init {
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    closed = true
                    busy = false
                    createResult = null
                    openResult = null
                    multipleResult = null
                    executor.shutdownNow()
                    owner.lifecycle.removeObserver(this)
                }
            },
        )
    }

    /** Asks for a new document; [result] receives null when the user cancels. Main thread only. */
    fun chooseDestination(
        name: String,
        result: (Uri?) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed && !busy)
        busy = true
        createResult = result
        try {
            create.launch(name)
        } catch (_: Exception) {
            finish(createResult.also { createResult = null }, null)
        }
    }

    /**
     * Asks for an existing document of [types]; [result] receives null when the user cancels. Main thread only. The
     * picker filters by type only, so callers check the name where it matters.
     */
    fun chooseSource(
        types: Array<String> = arrayOf("*/*"),
        result: (Uri?) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed && !busy)
        busy = true
        openResult = result
        try {
            open.launch(types)
        } catch (_: Exception) {
            finish(openResult.also { openResult = null }, null)
        }
    }

    /** The name the document provider shows for [uri], or null when it has none. */
    fun displayName(uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()

    /** Small generated files such as CSV exports. */
    fun save(
        name: String,
        bytes: ByteArray,
        result: (Boolean) -> Unit,
    ) {
        chooseDestination(name) { uri ->
            if (uri == null) {
                result(false)
            } else {
                executor.execute {
                    val success = runCatching { write(uri) { it.write(bytes) } }.isSuccess
                    android.os.Handler(Looper.getMainLooper()).post { if (!closed) result(success) }
                }
            }
        }
    }

    /** Streams into [uri] on the calling thread; a failed write removes the partial document. */
    fun write(
        uri: Uri,
        block: (OutputStream) -> Unit,
    ) {
        try {
            requireNotNull(resolver.openOutputStream(uri, "wt")).buffered().use(block)
        } catch (failure: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw failure
        }
    }

    /** Streams from [uri] on the calling thread. */
    fun <T> read(
        uri: Uri,
        block: (InputStream) -> T,
    ): T = requireNotNull(resolver.openInputStream(uri)).buffered().use(block)

    private fun finish(
        callback: ((Uri?) -> Unit)?,
        uri: Uri?,
    ) {
        busy = false
        if (!closed) callback?.invoke(uri)
    }
}

/**
 * Types a Valroot backup can have. Android knows no type for `.valroot`, so most providers report it as binary data;
 * some keep the type Valroot saved it with.
 */
internal val BACKUP_TYPES = arrayOf("application/octet-stream", "application/x-valroot")

/** Whether [name] is a Valroot backup's name; an unknown name is accepted and the backup's own header decides. */
internal fun isBackupName(name: String?) = name == null || name.endsWith(".valroot", ignoreCase = true)

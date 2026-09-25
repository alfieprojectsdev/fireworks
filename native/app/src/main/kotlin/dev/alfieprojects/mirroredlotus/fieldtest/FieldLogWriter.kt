package dev.alfieprojects.mirroredlotus.fieldtest

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Appends a field-test run's JSON Lines to a file the wearer can reach
 * without a laptop: `Download/LotusTests/` through MediaStore on API 29+ (no
 * permission needed for the app's own files, visible in Files, pullable with
 * adb), or the app's external files directory on older releases.
 *
 * Lines are buffered on the main thread and handed to a single background
 * thread on [flush], which writes and syncs them. The file stays open for the
 * run, so a crash or a thermal kill loses at most the last few seconds.
 */
class FieldLogWriter private constructor(
    /** Where to tell the wearer to look. */
    val displayPath: String,
    /** The MediaStore entry on API 29+; null for a plain file. */
    val uri: Uri?,
    private val out: FileOutputStream,
    private val pfd: ParcelFileDescriptor?,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "field-log").apply { priority = Thread.MIN_PRIORITY }
    }
    private val pending = StringBuilder()
    private var closed = false

    /** Main thread. */
    fun append(line: String) {
        if (!closed) pending.append(line)
    }

    /** Main thread: hands the buffered lines to the writer thread. */
    fun flush() {
        if (closed || pending.isEmpty()) return
        val chunk = pending.toString().toByteArray(Charsets.UTF_8)
        pending.setLength(0)
        executor.execute {
            try {
                out.write(chunk)
                out.flush()
                out.fd.sync()
            } catch (e: Exception) {
                Log.w(TAG, "write failed: ${e.message}")
            }
        }
    }

    fun close() {
        if (closed) return
        flush()
        closed = true
        executor.execute {
            runCatching { out.close() }
            runCatching { pfd?.close() }
        }
        executor.shutdown()
    }

    /** After [close]: waits for the last write to land. For tests. */
    fun awaitClosed(timeoutMs: Long): Boolean = executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)

    companion object {
        private const val TAG = "LotusFieldLog"
        private const val FOLDER = "LotusTests"

        /** Null if storage refused; the run then goes ahead without a file. */
        fun create(context: Context, fileName: String): FieldLogWriter? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) createInDownloads(context, fileName) else createInAppFiles(context, fileName)
        } catch (e: Exception) {
            Log.w(TAG, "could not create $fileName: ${e.message}")
            null
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun createInDownloads(context: Context, fileName: String): FieldLogWriter? {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                // Must match the .json extension, or MediaStore appends its own.
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val pfd = resolver.openFileDescriptor(uri, "w") ?: return null
            // MediaStore may have renamed it to keep it unique: report the real name.
            val actual = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: fileName
            return FieldLogWriter("${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$actual", uri, FileOutputStream(pfd.fileDescriptor), pfd)
        }

        private fun createInAppFiles(context: Context, fileName: String): FieldLogWriter? {
            val dir = File(context.getExternalFilesDir(null) ?: return null, FOLDER).apply { mkdirs() }
            val file = File(dir, fileName)
            return FieldLogWriter(file.absolutePath, null, FileOutputStream(file), null)
        }
    }
}

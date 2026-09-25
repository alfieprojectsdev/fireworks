package dev.alfieprojects.mirroredlotus.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a session JSON to the Android share sheet as a real file. The web
 * build fell back to the clipboard because the WebView ignores `<a download>`;
 * natively there is no such problem. A copy also lands in the app's external
 * files directory, so `adb pull /sdcard/Android/data/<pkg>/files/sessions/`
 * works without touching the phone.
 */
object SessionExporter {
    private const val TAG = "LotusExport"

    /** Returns the chooser intent, or null if the file couldn't be written. */
    fun share(context: Context, json: String, chooserTitle: String): Intent? {
        val name = "session_${System.currentTimeMillis()}.json"
        return try {
            val dir = File(context.cacheDir, "sessions").apply { mkdirs() }
            val file = File(dir, name).apply { writeText(json) }
            context.getExternalFilesDir("sessions")?.let { ext ->
                runCatching { File(ext, name).writeText(json) }
                    .onFailure { Log.w(TAG, "external copy failed: ${it.message}") }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                clipData = ClipData.newRawUri(name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(send, chooserTitle)
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            null
        }
    }
}

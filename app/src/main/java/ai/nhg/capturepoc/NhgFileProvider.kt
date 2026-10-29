package ai.nhg.capturepoc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class NhgFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "ai.nhg.capturepoc.provider"

        fun getUriForFile(context: Context, file: File): Uri {
            return Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .path(file.absolutePath)
                .build()
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val path = uri.path ?: throw FileNotFoundException("Missing file path")
        val file = File(path)
        val ctx = context ?: throw FileNotFoundException("Context missing")
        val appFilesDir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val recordingsDir = File(appFilesDir, "sessions")
        val downloadsDir = File("/sdcard/Download/NHG-InternalAudioPOC")

        val canonical = file.canonicalPath
        val inApp = try { canonical.startsWith(appFilesDir.canonicalPath) } catch (_: Throwable) { false }
        val inRec = try { canonical.startsWith(recordingsDir.canonicalPath) } catch (_: Throwable) { false }
        val inDl = try { canonical.startsWith(downloadsDir.canonicalPath) } catch (_: Throwable) { false }

        if (!inApp && !inRec && !inDl) {
            throw SecurityException("Access to path denied: $canonical")
        }

        if (!file.exists()) throw FileNotFoundException("File not found: $canonical")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val path = uri.path ?: return MatrixCursor(emptyArray())
        val file = File(path)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = cursor.newRow()
        for (col in cols) {
            when (col) {
                OpenableColumns.DISPLAY_NAME -> row.add(file.name)
                OpenableColumns.SIZE -> row.add(file.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String? {
        val path = uri.path ?: return null
        return when {
            path.endsWith(".wav", ignoreCase = true) -> "audio/wav"
            path.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
            path.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            path.endsWith(".json", ignoreCase = true) -> "application/json"
            path.endsWith(".txt", ignoreCase = true) || path.endsWith(".log", ignoreCase = true) -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

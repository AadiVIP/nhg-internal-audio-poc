package ai.nhg.capturepoc

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

data class RecordingItem(
    val id: String,
    val name: String,
    val file: File,
    val format: AudioOutputFormat,
    val durationMs: Long,
    val sizeBytes: Long,
    val createdAtMs: Long,
    val sha256: String? = null,
    val metaFile: File? = null,
    val internalFile: File? = null,
    val masterWavFile: File? = null
)

object SavedAudioRepository {
    private const val TAG = "NhgSavedAudio"

    fun loadRecordings(context: Context): List<RecordingItem> {
        val sessionDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions")
        val exportDir = File("/sdcard/Download/NHG-InternalAudioPOC")
        return loadRecordingsFromDirs(sessionDir, exportDir)
    }

    fun loadRecordingsFromDirs(sessionDir: File, exportDir: File): List<RecordingItem> {
        val candidates = mutableMapOf<String, SessionCandidate>()
        val dirs = listOf(sessionDir, exportDir)

        fun normPath(f: File): String = try { f.canonicalPath } catch (_: Throwable) { f.absolutePath }

        // 1. Scan metadata (.json) files across both directories
        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val jsonFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) } ?: continue
            for (jsonFile in jsonFiles) {
                try {
                    val content = jsonFile.readText()
                    if (content.isBlank()) continue
                    val obj = JSONObject(content)
                    val sid = obj.optString("sessionId").takeIf { it.isNotBlank() } ?: jsonFile.nameWithoutExtension
                    val cand = candidates.getOrPut(sid) { SessionCandidate(sid) }

                    // Prefer metadata in sessionDir if available, otherwise exportDir
                    if (cand.metaFile == null || (jsonFile.parentFile?.let { normPath(it) == normPath(sessionDir) } == true)) {
                        cand.metaFile = jsonFile
                    }

                    val fmtStr = obj.optString("outputFormatActual").takeIf { it.isNotBlank() }
                        ?: obj.optString("outputFormat").takeIf { it.isNotBlank() }
                    if (fmtStr != null) {
                        val fmt = AudioOutputFormat.values().find { it.name.equals(fmtStr, ignoreCase = true) }
                        if (fmt != null) cand.declaredFormat = fmt
                    }

                    val d = obj.optLong("durationMs", 0L)
                    if (d > cand.durationMs) cand.durationMs = d

                    val sha = obj.optString("sha256").takeIf { it.isNotBlank() && it != "null" }
                    if (sha != null && cand.sha256 == null) cand.sha256 = sha

                    val created = jsonFile.lastModified()
                    if (cand.createdAtMs == 0L || created < cand.createdAtMs) cand.createdAtMs = created

                    obj.optString("audioFile").takeIf { it.isNotBlank() }?.let { cand.declaredAudioPath = it }
                    obj.optString("exportedAudio").takeIf { it.isNotBlank() }?.let { cand.declaredExportedPath = it }
                    obj.optString("wavFile").takeIf { it.isNotBlank() }?.let { cand.declaredWavPath = it }
                } catch (t: Throwable) {
                    try { Log.w(TAG, "Failed to parse metadata ${jsonFile.name}: ${t.message}") } catch (_: Throwable) {}
                }
            }
        }

        // 2. Scan audio files (.wav, .m4a, .mp3) with length > 44
        val allAudioFiles = mutableListOf<File>()
        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                val ext = f.extension.lowercase()
                if (ext == "wav" || ext == "m4a" || ext == "mp3") {
                    if (f.length() > 44) {
                        allAudioFiles.add(f)
                    }
                }
            }
        }

        // 3. Associate audio files with metadata candidates
        val unassociatedAudio = mutableListOf<File>()

        for (audioFile in allAudioFiles) {
            val audioNorm = normPath(audioFile)
            val audioBase = audioFile.nameWithoutExtension

            var matchedCand: SessionCandidate? = null
            for ((_, cand) in candidates) {
                val matchDeclared = (cand.declaredAudioPath != null && normPath(File(cand.declaredAudioPath!!)) == audioNorm) ||
                        (cand.declaredExportedPath != null && normPath(File(cand.declaredExportedPath!!)) == audioNorm) ||
                        (cand.declaredWavPath != null && normPath(File(cand.declaredWavPath!!)) == audioNorm)
                val matchName = (cand.sessionId == audioBase)
                if (matchDeclared || matchName) {
                    matchedCand = cand
                    break
                }
            }

            if (matchedCand != null) {
                if (!matchedCand.audioFiles.any { normPath(it) == audioNorm }) {
                    matchedCand.audioFiles.add(audioFile)
                }
            } else {
                unassociatedAudio.add(audioFile)
            }
        }

        // 4. Group any unassociated audio files by base name
        val unassociatedGroups = unassociatedAudio.groupBy { it.nameWithoutExtension }
        for ((baseName, filesInGroup) in unassociatedGroups) {
            // Check if files in this group have different extensions (e.g. wav master + mp3 output)
            // or if same extension in different dirs have identical lengths (internal vs exported copy)
            val cand = candidates.getOrPut(baseName) { SessionCandidate(baseName) }
            for (f in filesInGroup) {
                val fNorm = normPath(f)
                if (!cand.audioFiles.any { normPath(it) == fNorm }) {
                    cand.audioFiles.add(f)
                }
            }
        }

        // 5. Build exactly one primary RecordingItem per candidate
        val items = mutableListOf<RecordingItem>()

        for ((_, cand) in candidates) {
            if (cand.audioFiles.isEmpty()) continue

            // Determine target/primary format
            val targetFormat: AudioOutputFormat = cand.declaredFormat ?: run {
                when {
                    cand.audioFiles.any { it.extension.equals("mp3", ignoreCase = true) } -> AudioOutputFormat.MP3
                    cand.audioFiles.any { it.extension.equals("m4a", ignoreCase = true) } -> AudioOutputFormat.M4A
                    else -> AudioOutputFormat.WAV
                }
            }

            // Identify intermediate master WAV (if target format is MP3 or M4A)
            var masterWavFile: File? = null
            if (targetFormat == AudioOutputFormat.MP3 || targetFormat == AudioOutputFormat.M4A) {
                masterWavFile = cand.audioFiles.find { it.extension.equals("wav", ignoreCase = true) }
            }

            // Audio files matching the primary format
            val primaryFormatFiles = cand.audioFiles.filter {
                it.extension.equals(targetFormat.extension, ignoreCase = true)
            }

            val primaryFile: File?
            val internalFile: File?

            if (primaryFormatFiles.isNotEmpty()) {
                // Prefer exported file in exportDir as user-facing file, otherwise sessionDir
                val exportedCopy = primaryFormatFiles.find { it.parentFile?.let { p -> normPath(p) == normPath(exportDir) } == true }
                val internalCopy = primaryFormatFiles.find { it.parentFile?.let { p -> normPath(p) == normPath(sessionDir) } == true }

                primaryFile = exportedCopy ?: internalCopy ?: primaryFormatFiles.first()
                internalFile = internalCopy
            } else if (masterWavFile != null) {
                // Fallback for interrupted session where transcode never occurred
                primaryFile = masterWavFile
                internalFile = masterWavFile
                masterWavFile = null
            } else {
                continue
            }

            val finalFormat = AudioOutputFormat.fromExtension(primaryFile.extension)
            var durationMs = cand.durationMs
            if (durationMs <= 0L) {
                durationMs = parseDurationMs(primaryFile, primaryFile.extension)
            }

            val createdAt = if (cand.createdAtMs > 0L) cand.createdAtMs else primaryFile.lastModified()

            items.add(
                RecordingItem(
                    id = cand.sessionId,
                    name = primaryFile.name,
                    file = primaryFile,
                    format = finalFormat,
                    durationMs = durationMs,
                    sizeBytes = primaryFile.length(),
                    createdAtMs = createdAt,
                    sha256 = cand.sha256,
                    metaFile = cand.metaFile,
                    internalFile = internalFile,
                    masterWavFile = masterWavFile
                )
            )
        }

        items.sortByDescending { it.createdAtMs }
        return items
    }

    private class SessionCandidate(
        val sessionId: String,
        var declaredFormat: AudioOutputFormat? = null,
        var durationMs: Long = 0L,
        var sha256: String? = null,
        var createdAtMs: Long = 0L,
        var metaFile: File? = null,
        val audioFiles: MutableList<File> = mutableListOf(),
        var declaredAudioPath: String? = null,
        var declaredExportedPath: String? = null,
        var declaredWavPath: String? = null
    )

    private fun parseDurationMs(file: File, ext: String): Long {
        if (ext == "wav") {
            try {
                if (file.length() > 44) {
                    RandomAccessFile(file, "r").use { r ->
                        val h = ByteArray(44)
                        r.readFully(h)
                        val channels = (h[22].toInt() and 0xFF) or ((h[23].toInt() and 0xFF) shl 8)
                        val sampleRate = (h[24].toInt() and 0xFF) or
                                ((h[25].toInt() and 0xFF) shl 8) or
                                ((h[26].toInt() and 0xFF) shl 16) or
                                ((h[27].toInt() and 0xFF) shl 24)
                        val dataBytes = file.length() - 44
                        val bytesPerSec = sampleRate * channels * 2
                        if (bytesPerSec > 0) return dataBytes * 1000L / bytesPerSec
                    }
                }
            } catch (_: Throwable) {}
        }

        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            mmr.release()
            return durStr?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            return 0L
        }
    }

    fun renameRecording(context: Context, item: RecordingItem, newBaseName: String): Result<RecordingItem> {
        val cleanName = newBaseName.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (cleanName.isBlank()) return Result.failure(IllegalArgumentException("Name cannot be blank"))

        val ext = item.file.extension
        val newFileName = "$cleanName.$ext"
        val newFile = File(item.file.parentFile, newFileName)
        if (newFile.exists()) {
            return Result.failure(IllegalStateException("A file with name $newFileName already exists"))
        }

        val success = item.file.renameTo(newFile)
        if (!success) {
            return Result.failure(IllegalStateException("Failed to rename file"))
        }

        var newInternalFile = item.internalFile
        if (item.internalFile != null && item.internalFile.exists() && item.internalFile != item.file) {
            val targetInternal = File(item.internalFile.parentFile, "$cleanName.$ext")
            if (item.internalFile.renameTo(targetInternal)) {
                newInternalFile = targetInternal
            }
        }

        var newMasterWav = item.masterWavFile
        if (item.masterWavFile != null && item.masterWavFile.exists() && item.masterWavFile != item.file) {
            val masterExt = item.masterWavFile.extension
            val targetMaster = File(item.masterWavFile.parentFile, "$cleanName.$masterExt")
            if (item.masterWavFile.renameTo(targetMaster)) {
                newMasterWav = targetMaster
            }
        }

        var newMetaFile = item.metaFile
        if (item.metaFile != null && item.metaFile.exists()) {
            val targetMeta = File(item.metaFile.parentFile, "$cleanName.json")
            if (item.metaFile.renameTo(targetMeta)) {
                newMetaFile = targetMeta
            }
        }

        val updated = item.copy(
            id = cleanName,
            name = newFileName,
            file = newFile,
            metaFile = newMetaFile,
            internalFile = newInternalFile,
            masterWavFile = newMasterWav
        )
        return Result.success(updated)
    }

    fun deleteRecording(context: Context, item: RecordingItem): Boolean {
        var deleted = false
        try {
            if (item.file.exists()) {
                deleted = item.file.delete()
            }
            if (item.internalFile != null && item.internalFile.exists() && item.internalFile != item.file) {
                item.internalFile.delete()
            }
            if (item.masterWavFile != null && item.masterWavFile.exists() && item.masterWavFile != item.file) {
                item.masterWavFile.delete()
            }
            if (item.metaFile != null && item.metaFile.exists()) {
                item.metaFile.delete()
            }
            val parentDirs = listOfNotNull(item.file.parentFile, item.internalFile?.parentFile).distinct()
            for (dir in parentDirs) {
                File(dir, "${item.id}.log").takeIf { it.exists() }?.delete()
                File(dir, "${item.id}.log.txt").takeIf { it.exists() }?.delete()
                File(dir, "${item.id}.json").takeIf { it.exists() }?.delete()
            }
        } catch (t: Throwable) {
            try { Log.e(TAG, "Error deleting recording ${item.name}", t) } catch (_: Throwable) {}
        }
        return deleted
    }

    fun shareRecording(context: Context, item: RecordingItem) {
        try {
            val uri = NhgFileProvider.getUriForFile(context, item.file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.format.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, item.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Share recording").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (t: Throwable) {
            try { Log.e(TAG, "Share failed", t) } catch (_: Throwable) {}
        }
    }
}

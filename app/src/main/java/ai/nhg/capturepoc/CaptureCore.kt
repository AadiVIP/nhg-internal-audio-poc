package ai.nhg.capturepoc

import android.app.KeyguardManager
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "NhgCapture"
const val APP_VERSION = "0.3.0"

data class CaptureConfig(
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val chunkFrames: Int = 960,
    val usages: List<Int> = listOf(
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_UNKNOWN
    )
) {
    val bytesPerSample: Int = 2
    val bytesPerFrame: Int = channels * bytesPerSample
    val chunkSamples: Int = chunkFrames * channels
    val chunkBytes: Int = chunkSamples * bytesPerSample
    val byteRate: Int = sampleRate * bytesPerFrame

    fun toJson(): JSONObject = JSONObject().apply {
        put("sampleRate", sampleRate)
        put("channels", channels)
        put("encoding", "ENCODING_PCM_16BIT")
        put("chunkFrames", chunkFrames)
        put("usages", JSONArray(usages))
    }
}

enum class CaptureState {
    IDLE,
    REQUESTING_PERMISSION,
    STARTING,
    RECORDING,
    PAUSED,
    FINALIZING,
    COMPLETED,
    COMPLETED_SILENT,
    FAILED,
    REVOKED,
    INTERRUPTED;

    val isActive: Boolean
        get() = this in setOf(REQUESTING_PERMISSION, STARTING, RECORDING, PAUSED, FINALIZING)
}

enum class StopReason { USER, READ_ERROR, PROJECTION_STOP, SETUP_ERROR, SERVICE_DESTROY, UNKNOWN }

data class StatsSnapshot(
    val frames: Long,
    val totalSamples: Long,
    val peakSample: Int,
    val rmsL: Double,
    val rmsR: Double,
    val rmsAll: Double,
    val peakDb: Double,
    val rmsDb: Double,
    val nonzeroSamples: Long,
    val zeroSamples: Long,
    val clippedSamples: Long
)

class CaptureStats(private val channels: Int) {
    var frames = 0L
        private set
    private var peak = 0
    private var nonzero = 0L
    private var zero = 0L
    private var clipped = 0L
    private var sumSqAll = 0.0
    private var nAll = 0L
    private var sumSqL = 0.0
    private var nL = 0L
    private var sumSqR = 0.0
    private var nR = 0L

    private var winPeak = 0
    private var winSqAll = 0.0
    private var winN = 0L
    private var winNonzero = 0L

    fun add(buf: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            val l = buf[i].toInt()
            val r = if (channels > 1 && i + 1 < count) buf[i + 1].toInt() else l
            val al = abs(l)
            val ar = abs(r)
            val m = max(al, ar)
            if (m > peak) peak = m
            if (m > winPeak) winPeak = m
            if (l != 0 || r != 0) { nonzero++; winNonzero++ } else { zero++ }
            if (m >= 32760) clipped++
            sumSqAll += (l.toDouble() * l) + (r.toDouble() * r)
            nAll += 2
            sumSqL += l.toDouble() * l; nL++
            sumSqR += r.toDouble() * r; nR++
            winSqAll += (l.toDouble() * l) + (r.toDouble() * r); winN += 2
            i += channels
        }
        if (count > 0) frames += count / channels
    }

    fun windowRmsDb(): Double = dbfs(sqrt(if (winN == 0L) 0.0 else winSqAll / winN))
    fun windowPeakDb(): Double = dbfs(winPeak.toDouble())

    fun resetWindow() {
        winPeak = 0; winSqAll = 0.0; winN = 0; winNonzero = 0
    }

    fun snapshot(): StatsSnapshot = StatsSnapshot(
        frames = frames,
        totalSamples = nAll,
        peakSample = peak,
        rmsL = sqrt(if (nL == 0L) 0.0 else sumSqL / nL),
        rmsR = sqrt(if (nR == 0L) 0.0 else sumSqR / nR),
        rmsAll = sqrt(if (nAll == 0L) 0.0 else sumSqAll / nAll),
        peakDb = dbfs(peak.toDouble()),
        rmsDb = dbfs(sqrt(if (nAll == 0L) 0.0 else sumSqAll / nAll)),
        nonzeroSamples = nonzero,
        zeroSamples = zero,
        clippedSamples = clipped
    )

    companion object {
        fun dbfs(v: Double): Double = if (v <= 0.0) -120.0 else 20.0 * log10(v / 32768.0)
    }
}

object CaptureController {
    @Volatile var state: CaptureState = CaptureState.IDLE
    @Volatile var stateDetail: String = "Idle - ready to capture internal audio"
    @Volatile var elapsedMs: Long = 0L
    @Volatile var frames: Long = 0L
    @Volatile var rmsDb: Double = -120.0
    @Volatile var peakDb: Double = -120.0
    @Volatile var sessionId: String? = null
    @Volatile var lastFile: String? = null
    @Volatile var screenInteractive: Boolean = true
    @Volatile var keyguardLocked: Boolean = false
    @Volatile var lastError: String? = null
    @Volatile var isPaused: Boolean = false
    @Volatile var selectedFormat: AudioOutputFormat = AudioOutputFormat.WAV
    @Volatile var lastRecordedFile: File? = null
    @Volatile var lastRecordedDurationMs: Long = 0L
    @Volatile var lastRecordedSizeBytes: Long = 0L
    @Volatile var logTail: List<String> = emptyList()

    fun resetForNewSession(id: String, format: AudioOutputFormat) {
        state = CaptureState.STARTING
        stateDetail = "Session $id: initializing"
        elapsedMs = 0L
        frames = 0L
        rmsDb = -120.0
        peakDb = -120.0
        sessionId = id
        selectedFormat = format
        lastFile = null
        lastError = null
        isPaused = false
        lastRecordedFile = null
        lastRecordedDurationMs = 0L
        lastRecordedSizeBytes = 0L
        screenInteractive = true
        keyguardLocked = false
    }

    fun set(newState: CaptureState, detail: String) {
        state = newState
        stateDetail = detail
        isPaused = (newState == CaptureState.PAUSED)
    }
}

class SessionLogger(
    context: Context,
    val sessionId: String,
    val config: CaptureConfig,
    val format: AudioOutputFormat
) {
    val dir: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "sessions"
    ).apply { mkdirs() }
    val wavFile = File(dir, "$sessionId.wav")
    val m4aFile = File(dir, "$sessionId.m4a")
    val mp3File = File(dir, "$sessionId.mp3")
    val logFile = File(dir, "$sessionId.log")
    val metaFile = File(dir, "$sessionId.json")

    private val startElapsed = SystemClock.elapsedRealtime()
    private val startWall = System.currentTimeMillis()
    private val lock = Any()
    private val fos = FileOutputStream(logFile, true)
    private val tail = ArrayDeque<String>()
    private var closed = false

    init {
        val requested = requestedPermissions(context)
        log("SESSION_START id=$sessionId app=$APP_VERSION format=${format.name}")
        log("DEVICE model=${Build.MODEL} manufacturer=${Build.MANUFACTURER} release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        log("BUILD fingerprint=${Build.FINGERPRINT}")
        log("CONFIG $config")
        log("MANIFEST requestedPermissions=${requested.joinToString(",")}")
        log("MANIFEST RECORD_AUDIO=${requested.contains("android.permission.RECORD_AUDIO")} INTERNET=${requested.contains("android.permission.INTERNET")}")
        writeInitialMeta(context, requested)
    }

    fun startElapsedMs(): Long = startElapsed
    fun wallStartMs(): Long = startWall

    fun log(message: String) {
        val line = "+${SystemClock.elapsedRealtime() - startElapsed}ms ${isoNow()} $message"
        synchronized(lock) {
            if (!closed) {
                try {
                    fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                    fos.flush()
                } catch (t: Throwable) {
                    Log.e(TAG, "log write failed", t)
                }
            }
            tail.addLast(line)
            while (tail.size > 14) tail.removeFirst()
            CaptureController.logTail = tail.toList()
        }
        Log.i(TAG, line)
    }

    fun writeInitialMeta(context: Context, requested: List<String>) {
        val obj = JSONObject().apply {
            put("schemaVersion", 1)
            put("app", "NHG Internal Audio")
            put("appVersion", APP_VERSION)
            put("sessionId", sessionId)
            put("outputFormat", format.name)
            put("outputFormatRequested", format.name)
            put("outputFormatActual", JSONObject.NULL)
            put("startedAt", iso(startWall))
            put("startElapsedRealtimeMs", startElapsed)
            put("device", deviceJson())
            put("config", config.toJson())
            put("requestedPermissions", JSONArray(requested))
            put("recordAudioRequested", requested.contains("android.permission.RECORD_AUDIO"))
            put("internetRequested", requested.contains("android.permission.INTERNET"))
            put("finalized", false)
            put("exported", false)
            put("finalState", "IN_PROGRESS")
            put("wavFile", wavFile.absolutePath)
            put("m4aFile", m4aFile.absolutePath)
            put("mp3File", mp3File.absolutePath)
            put("logFile", logFile.absolutePath)
            put("note", "If this file still says finalized=false after a crash, SessionRecovery repairs the WAV header and marks it INTERRUPTED.")
        }
        writeMeta(obj)
    }

    fun writeFinalMeta(
        result: SessionResult,
        snapshot: StatsSnapshot?,
        screenOffMs: Long,
        keyguardMs: Long,
        zeroReads: Long,
        requested: List<String>
    ) {
        val obj = JSONObject().apply {
            put("schemaVersion", 1)
            put("app", "NHG Internal Audio")
            put("appVersion", APP_VERSION)
            put("sessionId", result.sessionId)
            put("outputFormat", (result.outputFormat ?: result.requestedFormat).name)
            put("outputFormatRequested", result.requestedFormat.name)
            put("outputFormatActual", result.outputFormat?.name ?: JSONObject.NULL)
            put("startedAt", iso(startWall))
            put("endedAt", isoNow())
            put("startElapsedRealtimeMs", startElapsed)
            put("wallMs", result.wallMs)
            put("device", deviceJson())
            put("config", config.toJson())
            put("requestedPermissions", JSONArray(requested))
            put("recordAudioRequested", requested.contains("android.permission.RECORD_AUDIO"))
            put("internetRequested", requested.contains("android.permission.INTERNET"))
            put("finalized", result.wavFinalized)
            put("exported", false)
            put("finalState", result.finalState.name)
            put("reason", result.reason.name)
            put("finalDetail", result.detail ?: JSONObject.NULL)
            put("audioFile", result.audioFile?.absolutePath ?: JSONObject.NULL)
            put("wavFile", wavFile.absolutePath)
            put("m4aFile", m4aFile.absolutePath)
            put("mp3File", mp3File.absolutePath)
            put("logFile", logFile.absolutePath)
            put("dataBytes", result.dataBytes)
            put("frames", result.frames)
            put("durationMs", result.durationMs)
            put("sampleRate", config.sampleRate)
            put("channels", config.channels)
            put("encoding", "PCM_16BIT")
            put("peakSample", result.peakSample)
            put("peakDbfs", result.peakDb)
            put("rmsDbfs", result.rmsDb)
            put("nonzeroSamples", snapshot?.nonzeroSamples ?: 0L)
            put("zeroSamples", snapshot?.zeroSamples ?: 0L)
            put("clippedSamples", snapshot?.clippedSamples ?: 0L)
            put("rmsLeft", snapshot?.rmsL ?: 0.0)
            put("rmsRight", snapshot?.rmsR ?: 0.0)
            put("sha256", result.sha256 ?: JSONObject.NULL)
            put("screenOffMs", screenOffMs)
            put("keyguardMs", keyguardMs)
            put("zeroReads", zeroReads)
            put("error", result.error ?: JSONObject.NULL)
        }
        writeMeta(obj)
    }

    fun writeMeta(obj: JSONObject) {
        synchronized(lock) {
            try {
                metaFile.writeText(obj.toString(2))
            } catch (t: Throwable) {
                Log.e(TAG, "meta write failed", t)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                try { fos.flush(); fos.close() } catch (_: Throwable) {}
            }
        }
    }

    private fun deviceJson(): JSONObject = JSONObject().apply {
        put("model", Build.MODEL)
        put("manufacturer", Build.MANUFACTURER)
        put("device", Build.DEVICE)
        put("androidRelease", Build.VERSION.RELEASE)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("fingerprint", Build.FINGERPRINT)
        put("abis", JSONArray(Build.SUPPORTED_ABIS))
    }

    companion object {
        fun requestedPermissions(context: Context): List<String> {
            return try {
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_PERMISSIONS
                )
                info.requestedPermissions?.toList() ?: emptyList()
            } catch (t: Throwable) {
                emptyList()
            }
        }

        fun iso(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(millis))

        fun isoNow(): String = iso(System.currentTimeMillis())
    }
}

class WavWriter(
    val file: File,
    private val config: CaptureConfig,
    private val logger: (String) -> Unit
) {
    private val raf = RandomAccessFile(file, "rw")
    private val byteBuf = ByteArray(config.chunkBytes)
    private var dataBytes = 0L
    private var closed = false

    init {
        raf.setLength(0)
        writeHeader(0L)
    }

    fun write(samples: ShortArray, count: Int) {
        if (closed) throw IllegalStateException("WavWriter already closed")
        var j = 0
        var i = 0
        while (i < count) {
            val s = samples[i].toInt()
            byteBuf[j] = (s and 0xFF).toByte()
            byteBuf[j + 1] = ((s shr 8) and 0xFF).toByte()
            j += 2
            i++
        }
        raf.write(byteBuf, 0, j)
        dataBytes += j
    }

    fun finalizeHeader(): Long {
        if (closed) return dataBytes
        raf.seek(0)
        writeHeader(dataBytes)
        raf.fd.sync()
        raf.close()
        closed = true
        logger("WAV finalized size=${44 + dataBytes} dataBytes=$dataBytes headerSizesPatched=true")
        return dataBytes
    }

    private fun writeHeader(dataSize: Long) {
        val h = ByteArray(44)
        putAscii(h, 0, "RIFF")
        putIntLE(h, 4, (36L + dataSize).coerceAtMost(0xFFFFFFFFL).toInt())
        putAscii(h, 8, "WAVE")
        putAscii(h, 12, "fmt ")
        putIntLE(h, 16, 16)
        putShortLE(h, 20, 1)
        putShortLE(h, 22, config.channels)
        putIntLE(h, 24, config.sampleRate)
        putIntLE(h, 28, config.byteRate)
        putShortLE(h, 32, config.bytesPerFrame)
        putShortLE(h, 34, 16)
        putAscii(h, 36, "data")
        putIntLE(h, 40, dataSize.coerceAtMost(0xFFFFFFFFL).toInt())
        raf.write(h, 0, h.size)
    }

    companion object {
        fun repair(file: File): Long {
            if (!file.exists() || file.length() < 44) return 0
            RandomAccessFile(file, "rw").use { r ->
                val h = ByteArray(44)
                r.seek(0)
                r.readFully(h)
                val riff = String(h, 0, 4, Charsets.US_ASCII)
                val wave = String(h, 8, 4, Charsets.US_ASCII)
                if (riff != "RIFF" || wave != "WAVE") return 0
                var data = file.length() - 44
                val blockAlign = getShortLE(h, 32).toInt().let { if (it <= 0) 4 else it }
                data -= data % blockAlign
                if (data <= 0) return 0
                r.setLength(44 + data)
                r.seek(0)
                r.write(h, 0, h.size)
                val sizes = ByteArray(8)
                putIntLE(sizes, 0, (36L + data).coerceAtMost(0xFFFFFFFFL).toInt())
                putIntLE(sizes, 4, data.coerceAtMost(0xFFFFFFFFL).toInt())
                r.seek(4); r.write(sizes, 0, 4)
                r.seek(40); r.write(sizes, 4, 4)
                r.fd.sync()
                return data
            }
        }

        private fun putAscii(b: ByteArray, off: Int, s: String) {
            for (i in s.indices) b[off + i] = s[i].code.toByte()
        }

        fun putIntLE(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
            b[off + 2] = ((v shr 16) and 0xFF).toByte()
            b[off + 3] = ((v shr 24) and 0xFF).toByte()
        }

        fun putShortLE(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        fun getShortLE(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }
}

object MediaStoreExporter {
    private const val REL_PATH = "Download/NHG-InternalAudioPOC"

    fun export(context: Context, file: File, mime: String): String? {
        if (!file.exists() || file.length() == 0L) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, REL_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: throw IllegalStateException("openOutputStream returned null")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            var actual = file.name
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) actual = c.getString(0) ?: file.name }
            val path = "/sdcard/$REL_PATH/$actual"
            Log.i(TAG, "Exported ${file.name} -> $path ($uri)")
            path
        } catch (t: Throwable) {
            Log.e(TAG, "export failed for ${file.name}", t)
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            null
        }
    }
}

data class SessionResult(
    val sessionId: String,
    val requestedFormat: AudioOutputFormat,
    val outputFormat: AudioOutputFormat?,
    val finalState: CaptureState,
    val reason: StopReason,
    val detail: String?,
    val error: String?,
    val audioFile: File?,
    val wavFile: File?,
    val logFile: File,
    val metaFile: File,
    val wavFinalized: Boolean,
    val dataBytes: Long,
    val frames: Long,
    val durationMs: Long,
    val wallMs: Long,
    val peakSample: Int,
    val peakDb: Double,
    val rmsDb: Double,
    val sha256: String?,
    val screenOffMs: Long,
    val keyguardMs: Long
)

class CaptureEngine(
    context: Context,
    private val projection: MediaProjection,
    private val config: CaptureConfig,
    private val format: AudioOutputFormat,
    private val logger: SessionLogger,
    private val listener: (SessionResult) -> Unit
) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)

    private var record: AudioRecord? = null
    private var writer: WavWriter? = null
    private var stats: CaptureStats? = null
    private var readThread: Thread? = null

    @Volatile private var running = false
    @Volatile private var isPaused = false
    @Volatile private var startElapsed = 0L
    @Volatile private var stopReason: StopReason? = null
    @Volatile private var stopDetail: String? = null
    private val stateLock = Any()
    private val finalizerStarted = AtomicBoolean(false)

    private var lastInteractive = true
    private var lastKeyguard = false
    private var screenOffSince: Long? = null
    private var keyguardSince: Long? = null
    private var screenOffMs = 0L
    private var keyguardMs = 0L
    private var zeroReads = 0L
    private var timestampPolled = false
    private var lastStatsPublish = 0L
    private var lastScreenCheck = 0L

    fun start() {
        startElapsed = SystemClock.elapsedRealtime()
        try {
            val apccBuilder = AudioPlaybackCaptureConfiguration.Builder(projection)
            config.usages.forEach { apccBuilder.addMatchingUsage(it) }
            val apcc = apccBuilder.build()

            val minBuf = AudioRecord.getMinBufferSize(
                config.sampleRate,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                throw IllegalStateException("AudioRecord.getMinBufferSize returned $minBuf")
            }
            val bufferBytes = max(minBuf, config.chunkBytes * 4)
            logger.log("AUDIO_CONFIG requestedRate=${config.sampleRate} channels=${config.channels} encoding=PCM_16BIT usages=${config.usages} minBuf=$minBuf bufferBytes=$bufferBytes chunkBytes=${config.chunkBytes} format=${format.name}")

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(config.sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val recBuilder = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(apcc)
            if (Build.VERSION.SDK_INT >= 31) {
                recBuilder.setContext(appContext)
            }
            val rec = recBuilder.build()
            record = rec
            logger.log("AUDIO_RECORD built state=${rec.state} audioSessionId=${rec.audioSessionId} sampleRate=${rec.sampleRate} bufferSizeInFrames=${rec.bufferSizeInFrames}")
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord state=${rec.state} (STATE_UNINITIALIZED)")
            }

            stats = CaptureStats(config.channels)
            rec.startRecording()
            logger.log("AUDIO_RECORD startRecording returned; recordingState=${rec.recordingState} (1=RECORDING)")
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord not recording; state=${rec.recordingState}")
            }

            writer = WavWriter(logger.wavFile, config, logger::log)
            startElapsed = SystemClock.elapsedRealtime()
            lastInteractive = powerManager?.isInteractive ?: true
            lastKeyguard = keyguardManager?.isKeyguardLocked ?: false
            if (!lastInteractive) screenOffSince = startElapsed
            if (lastKeyguard) keyguardSince = startElapsed
            CaptureController.screenInteractive = lastInteractive
            CaptureController.keyguardLocked = lastKeyguard
            logger.log("SCREEN at start interactive=$lastInteractive keyguardLocked=$lastKeyguard")
            logger.log("CAPTURE_STARTED no RECORD_AUDIO permission declared or requested; playback capture sink active")

            running = true
            isPaused = false
            CaptureController.set(CaptureState.RECORDING, "Recording playback audio")
            readThread = Thread({ readLoop() }, "nhg-capture-read").apply { start() }
        } catch (t: Throwable) {
            try { record?.release() } catch (_: Throwable) {}
            record = null
            try { writer?.finalizeHeader() } catch (_: Throwable) {}
            throw t
        }
    }

    fun requestPause() {
        synchronized(stateLock) {
            if (running && !isPaused) {
                isPaused = true
                CaptureController.set(CaptureState.PAUSED, "Recording paused")
                logger.log("RECORDING_PAUSED elapsedFrames=${stats?.frames ?: 0}")
            }
        }
    }

    fun requestResume() {
        synchronized(stateLock) {
            if (running && isPaused) {
                isPaused = false
                CaptureController.set(CaptureState.RECORDING, "Recording resumed")
                logger.log("RECORDING_RESUMED elapsedFrames=${stats?.frames ?: 0}")
            }
        }
    }

    fun requestStop(reason: StopReason, detail: String? = null) {
        synchronized(stateLock) {
            if (stopReason == null) {
                stopReason = reason
                stopDetail = detail
            }
        }
        running = false
        try {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record?.stop()
        } catch (t: Throwable) {
            logger.log("WARN record.stop() from requestStop threw ${t.javaClass.simpleName}: ${t.message}")
        }
        if (finalizerStarted.compareAndSet(false, true)) {
            Thread({ finish() }, "nhg-capture-finalize").start()
        }
    }

    fun shutdownBlocking() {
        synchronized(stateLock) { if (stopReason == null) stopReason = StopReason.SERVICE_DESTROY }
        running = false
        try { record?.stop() } catch (_: Throwable) {}
        if (finalizerStarted.compareAndSet(false, true)) {
            finish()
        } else {
            readThread?.join(1500)
        }
    }

    private fun readLoop() {
        val chunk = ShortArray(config.chunkSamples)
        logger.log("READ_LOOP started chunkSamples=${chunk.size}")
        try {
            while (running) {
                val rec = record ?: break
                val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                val now = SystemClock.elapsedRealtime()
                if (n > 0) {
                    if (!isPaused) {
                        if (n % config.channels != 0) {
                            logger.log("WARN read returned $n samples, not a multiple of ${config.channels}")
                        }
                        writer?.write(chunk, n)
                        stats?.add(chunk, n)
                        if (!timestampPolled && stats != null && stats!!.frames >= config.sampleRate) {
                            timestampPolled = true
                            pollTimestamp(rec)
                        }
                        if (now - lastStatsPublish >= 100) {
                            publishStats(now)
                            lastStatsPublish = now
                        }
                    } else {
                        // While paused, visualizer levels fall to silence
                        CaptureController.rmsDb = -120.0
                        CaptureController.peakDb = -120.0
                    }

                    if (now - lastScreenCheck >= 250) {
                        checkScreen(now)
                        lastScreenCheck = now
                    }
                } else if (n == 0) {
                    zeroReads++
                    if (zeroReads == 1L || zeroReads % 100L == 0L) {
                        logger.log("READ zero bytes ($zeroReads occurrences); no PCM delivered yet")
                    }
                    Thread.sleep(10)
                } else {
                    val msg = "AudioRecord.read error=$n (${audioErrorName(n)}); zeroReads=$zeroReads"
                    logger.log(msg)
                    requestStop(StopReason.READ_ERROR, msg)
                    break
                }
            }
        } catch (t: Throwable) {
            val msg = "readLoop exception ${t.javaClass.name}: ${t.message}"
            logger.log(msg)
            requestStop(StopReason.READ_ERROR, msg)
        }
        logger.log("READ_LOOP exiting running=$running isPaused=$isPaused reason=$stopReason")
    }

    private fun pollTimestamp(rec: AudioRecord) {
        try {
            val ts = AudioTimestamp()
            val status = rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME)
            if (status == AudioRecord.SUCCESS) {
                logger.log("TIMESTAMP getTimestamp OK framePosition=${ts.framePosition} nanoTime=${ts.nanoTime} timebase=BOOTTIME")
            } else {
                logger.log("TIMESTAMP getTimestamp returned $status (${audioErrorName(status)}); timestamp not available")
            }
        } catch (t: Throwable) {
            logger.log("TIMESTAMP getTimestamp threw ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun publishStats(now: Long) {
        val s = stats ?: return
        CaptureController.frames = s.frames
        CaptureController.elapsedMs = s.frames * 1000L / config.sampleRate
        CaptureController.rmsDb = s.windowRmsDb()
        CaptureController.peakDb = s.windowPeakDb()
        s.resetWindow()
    }

    private fun checkScreen(now: Long) {
        val interactive = try { powerManager?.isInteractive ?: true } catch (_: Throwable) { true }
        val locked = try { keyguardManager?.isKeyguardLocked ?: false } catch (_: Throwable) { false }
        if (interactive != lastInteractive) {
            logger.log("SCREEN interactive=$interactive keyguardLocked=$locked elapsed=${now - startElapsed}ms")
            if (interactive) {
                screenOffSince?.let { screenOffMs += now - it }
                screenOffSince = null
            } else {
                screenOffSince = now
            }
            lastInteractive = interactive
        }
        if (locked != lastKeyguard) {
            logger.log("KEYGUARD locked=$locked interactive=$interactive elapsed=${now - startElapsed}ms")
            if (!locked) {
                keyguardSince?.let { keyguardMs += now - it }
                keyguardSince = null
            } else {
                keyguardSince = now
            }
            lastKeyguard = locked
        }
        CaptureController.screenInteractive = interactive
        CaptureController.keyguardLocked = locked
    }

    private fun finish() {
        val reason = stopReason ?: StopReason.UNKNOWN
        CaptureController.set(CaptureState.FINALIZING, "Finalizing audio container and exporting")
        running = false
        try { record?.stop() } catch (_: Throwable) {}
        if (Thread.currentThread() != readThread) {
            try { readThread?.join(3000) } catch (_: InterruptedException) {}
        }
        val now = SystemClock.elapsedRealtime()
        if (screenOffSince != null) { screenOffMs += now - screenOffSince!!; screenOffSince = null }
        if (keyguardSince != null) { keyguardMs += now - keyguardSince!!; keyguardSince = null }

        var dataBytes = 0L
        var wavFinalized = false
        var finalizeError: String? = null
        try {
            dataBytes = writer?.finalizeHeader() ?: 0L
            wavFinalized = writer != null
        } catch (t: Throwable) {
            finalizeError = "WAV finalize failed: ${t.javaClass.name}: ${t.message}"
            logger.log("ERROR $finalizeError")
        }
        try { record?.release() } catch (_: Throwable) {}
        record = null

        var outputAudioFile = if (wavFinalized) logger.wavFile else null
        var outputFormat: AudioOutputFormat? = if (outputAudioFile != null) AudioOutputFormat.WAV else null
        var encodeError: String? = null
        if (wavFinalized && dataBytes > 0 && format == AudioOutputFormat.M4A) {
            CaptureController.set(CaptureState.FINALIZING, "Encoding M4A (AAC)...")
            logger.log("ENCODE_M4A start input=${logger.wavFile.absolutePath} output=${logger.m4aFile.absolutePath}")
            val success = M4aEncoder.encodeWavToM4a(logger.wavFile, logger.m4aFile, 192000)
            if (success && logger.m4aFile.exists() && logger.m4aFile.length() > 0) {
                outputAudioFile = logger.m4aFile
                outputFormat = AudioOutputFormat.M4A
                logger.log("ENCODE_M4A success size=${logger.m4aFile.length()}")
            } else {
                encodeError = "M4A encoding failed; retaining master WAV"
                logger.log("ENCODE_M4A failed; $encodeError")
            }
        }
        if (wavFinalized && dataBytes > 0 && format == AudioOutputFormat.MP3) {
            CaptureController.set(CaptureState.FINALIZING, "Encoding MP3...")
            logger.log("ENCODE_MP3 start input=${logger.wavFile.absolutePath} output=${logger.mp3File.absolutePath} version=${Mp3Encoder.version() ?: "unavailable"}")
            val success = try {
                Mp3Encoder.encodeWavToMp3(logger.wavFile, logger.mp3File) { pct ->
                    CaptureController.set(CaptureState.FINALIZING, "Encoding MP3... ${(pct * 100).toInt()}%")
                }
            } catch (t: Throwable) {
                logger.log("ENCODE_MP3 exception ${t.javaClass.name}: ${t.message}")
                false
            }
            if (success && logger.mp3File.exists() && logger.mp3File.length() > 0) {
                outputAudioFile = logger.mp3File
                outputFormat = AudioOutputFormat.MP3
                logger.log("ENCODE_MP3 success size=${logger.mp3File.length()}")
            } else {
                encodeError = "MP3 encoding failed (${Mp3Encoder.lastError ?: "unknown error"}); retaining master WAV"
                logger.log("ENCODE_MP3 failed; $encodeError")
            }
        }

        val snap = stats?.snapshot()
        val peak = snap?.peakSample ?: 0
        var finalState: CaptureState = when (reason) {
            StopReason.USER -> when {
                dataBytes <= 0L -> CaptureState.FAILED
                peak == 0 -> CaptureState.COMPLETED_SILENT
                else -> CaptureState.COMPLETED
            }
            StopReason.PROJECTION_STOP -> CaptureState.REVOKED
            StopReason.READ_ERROR -> CaptureState.FAILED
            StopReason.SERVICE_DESTROY -> CaptureState.INTERRUPTED
            StopReason.SETUP_ERROR, StopReason.UNKNOWN -> CaptureState.FAILED
        }
        var error = stopDetail
        if (finalizeError != null) {
            finalState = CaptureState.FAILED
            error = listOfNotNull(error, finalizeError).joinToString("; ")
        }
        if (encodeError != null) {
            if (finalState == CaptureState.COMPLETED || finalState == CaptureState.COMPLETED_SILENT) {
                finalState = CaptureState.FAILED
            }
            error = listOfNotNull(error, encodeError).joinToString("; ")
        }
        if (dataBytes <= 0 && reason == StopReason.USER) {
            error = listOfNotNull(error, "No PCM data was written").joinToString("; ")
        }
        val wallMs = now - startElapsed
        val frames = snap?.frames ?: 0L
        val durationMs = frames * 1000L / config.sampleRate
        val sha = try {
            if (outputAudioFile != null && outputAudioFile.exists()) sha256(outputAudioFile) else null
        } catch (t: Throwable) {
            logger.log("WARN sha256 failed: ${t.message}")
            null
        }

        val result = SessionResult(
            sessionId = logger.sessionId,
            requestedFormat = format,
            outputFormat = outputFormat,
            finalState = finalState,
            reason = reason,
            detail = stopDetail,
            error = error,
            audioFile = outputAudioFile,
            wavFile = if (wavFinalized) logger.wavFile else null,
            logFile = logger.logFile,
            metaFile = logger.metaFile,
            wavFinalized = wavFinalized,
            dataBytes = dataBytes,
            frames = frames,
            durationMs = durationMs,
            wallMs = wallMs,
            peakSample = peak,
            peakDb = snap?.peakDb ?: -120.0,
            rmsDb = snap?.rmsDb ?: -120.0,
            sha256 = sha,
            screenOffMs = screenOffMs,
            keyguardMs = keyguardMs
        )

        CaptureController.lastRecordedFile = outputAudioFile
        CaptureController.lastRecordedDurationMs = durationMs
        CaptureController.lastRecordedSizeBytes = outputAudioFile?.length() ?: 0L

        logger.log("SESSION_END state=${finalState.name} reason=${reason.name} detail=${stopDetail ?: "-"} formatRequested=${format.name} formatActual=${outputFormat?.name ?: "NONE"} file=${outputAudioFile?.name ?: "-"} dataBytes=$dataBytes frames=$frames durationMs=$durationMs wallMs=$wallMs peak=$peak peakDbfs=${result.peakDb} rmsDbfs=${result.rmsDb} sha256=${sha ?: "-"} screenOffMs=$screenOffMs keyguardMs=$keyguardMs zeroReads=$zeroReads")
        try {
            logger.writeFinalMeta(
                result,
                snap,
                screenOffMs,
                keyguardMs,
                zeroReads,
                SessionLogger.requestedPermissions(appContext)
            )
        } catch (t: Throwable) {
            logger.log("ERROR final meta write failed: ${t.message}")
        }
        try { listener(result) } catch (t: Throwable) { logger.log("ERROR listener failed: ${t.message}") }
        writer = null
    }

    companion object {
        fun audioErrorName(code: Int): String = when (code) {
            AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
            AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
            AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
            AudioRecord.ERROR -> "ERROR"
            AudioRecord.SUCCESS -> "SUCCESS"
            else -> "UNKNOWN"
        }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

object SessionRecovery {
    private val TERMINAL_STATES = setOf(
        CaptureState.COMPLETED, CaptureState.COMPLETED_SILENT,
        CaptureState.FAILED, CaptureState.REVOKED, CaptureState.INTERRUPTED
    )

    fun recoverAndExport(context: Context): String {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions")
        if (!dir.isDirectory) return "No sessions directory"
        val metas = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name } ?: return "No metadata"
        var processed = 0
        for (meta in metas) {
            try {
                val obj = JSONObject(meta.readText())
                if (obj.optBoolean("exported", false)) continue
                val base = meta.nameWithoutExtension
                if (base == CaptureController.sessionId && !TERMINAL_STATES.contains(CaptureController.state)) {
                    continue
                }
                val wav = File(dir, "$base.wav")
                val m4a = File(dir, "$base.m4a")
                val mp3 = File(dir, "$base.mp3")
                val log = File(dir, "$base.log")
                var finalized = obj.optBoolean("finalized", false)
                if (wav.exists() && wav.length() > 44 && !finalized) {
                    val data = WavWriter.repair(wav)
                    finalized = data > 0
                    val sampleRate = obj.optInt("sampleRate", 48000).takeIf { it > 0 } ?: 48000
                    val channels = obj.optInt("channels", 2).takeIf { it in 1..2 } ?: 2
                    obj.put("finalized", finalized)
                    obj.put("interrupted", true)
                    obj.put("recoveredAt", SessionLogger.isoNow())
                    obj.put("dataBytes", data)
                    obj.put("durationMs", if (data > 0) data * 1000L / (sampleRate.toLong() * channels * 2L) else 0L)
                    obj.put("finalState", "INTERRUPTED")
                    obj.put("note", "WAV header repaired after unclean process death; not a successful completion.")
                }
                if (!finalized && mp3.exists()) {
                    val removed = mp3.delete()
                    if (log.exists()) {
                        FileOutputStream(log, true).use {
                            it.write("RECOVERY ${SessionLogger.isoNow()} removed incomplete MP3 $base.mp3 deleted=$removed\n".toByteArray())
                        }
                    }
                }
                // A finalized WAV is not proof the MP3 transcode finished. Only the
                // final metadata marks a completed MP3; otherwise a process kill
                // during encoding would leave a truncated file that looks usable.
                val mp3Declared = obj.optString("outputFormatActual", "") == "MP3"
                val mp3Complete = finalized && mp3Declared && mp3.exists() && mp3.length() > 0
                if (finalized && !mp3Declared && mp3.exists()) {
                    val removed = mp3.delete()
                    if (log.exists()) {
                        FileOutputStream(log, true).use {
                            it.write("RECOVERY ${SessionLogger.isoNow()} removed unconfirmed MP3 $base.mp3 deleted=$removed\n".toByteArray())
                        }
                    }
                }
                val finalFile = when {
                    mp3Complete -> mp3
                    m4a.exists() && m4a.length() > 0 -> m4a
                    else -> wav
                }
                val finalFormat = AudioOutputFormat.fromExtension(finalFile.extension)
                val exportedAudio = if (finalFile.exists() && finalFile.length() > 44) {
                    MediaStoreExporter.export(context, finalFile, finalFormat.mimeType)
                } else null
                if (log.exists()) {
                    FileOutputStream(log, true).use {
                        it.write("RECOVERY ${SessionLogger.isoNow()} repaired/exported session $base exportedAudio=$exportedAudio\n".toByteArray())
                    }
                }
                val exportedLog = if (log.exists()) MediaStoreExporter.export(context, log, "text/plain") else null
                obj.put("exported", true)
                obj.put("exportedAudio", exportedAudio ?: JSONObject.NULL)
                obj.put("exportedLog", exportedLog ?: JSONObject.NULL)
                meta.writeText(obj.toString(2))
                val exportedMeta = MediaStoreExporter.export(context, meta, "application/json")
                if (log.exists()) {
                    FileOutputStream(log, true).use {
                        it.write("RECOVERY ${SessionLogger.isoNow()} exportedMeta=$exportedMeta\n".toByteArray())
                    }
                }
                processed++
            } catch (t: Throwable) {
                Log.e(TAG, "recovery failed for ${meta.name}", t)
            }
        }
        return "Recovered/exported $processed session(s)"
    }
}

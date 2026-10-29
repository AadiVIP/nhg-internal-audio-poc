package ai.nhg.capturepoc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "ai.nhg.capturepoc.action.START"
        const val ACTION_PAUSE = "ai.nhg.capturepoc.action.PAUSE"
        const val ACTION_RESUME = "ai.nhg.capturepoc.action.RESUME"
        const val ACTION_STOP = "ai.nhg.capturepoc.action.STOP"
        const val EXTRA_RESULT_CODE = "ai.nhg.capturepoc.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "ai.nhg.capturepoc.extra.RESULT_DATA"
        const val EXTRA_OUTPUT_FORMAT = "ai.nhg.capturepoc.extra.OUTPUT_FORMAT"

        private const val TAG = "NhgCaptureService"
        private const val CHANNEL_ID = "nhg_capture"
        private const val NOTIFICATION_ID = 4127
        private const val WAKE_TAG = "nhg:playback-capture"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var engine: CaptureEngine? = null
    private var logger: SessionLogger? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationPosted = false
    private var stopping = false

    private val config = CaptureConfig()
    private var currentFormat: AudioOutputFormat = AudioOutputFormat.WAV

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            val detail = "MediaProjection onStop: token revoked or system stopped the session"
            logger?.log("PROJECTION onStop callback received")
            CaptureController.set(CaptureState.FINALIZING, "Projection revoked; finalizing recording")
            val e = engine
            if (e != null) {
                e.requestStop(StopReason.PROJECTION_STOP, detail)
            } else {
                CaptureController.lastError = detail
                CaptureController.set(CaptureState.REVOKED, detail)
                stopAndCleanup(StopReason.PROJECTION_STOP)
            }
        }
    }

    private val notificationTick = object : Runnable {
        override fun run() {
            val e = engine
            if (e != null) {
                val elapsedSec = CaptureController.elapsedMs / 1000L
                val isPaused = (CaptureController.state == CaptureState.PAUSED)
                val rms = CaptureController.rmsDb

                val statusText = if (isPaused) {
                    val m = elapsedSec / 60
                    val s = elapsedSec % 60
                    String.format(Locale.US, "Paused: %02d:%02d", m, s)
                } else {
                    val m = elapsedSec / 60
                    val s = elapsedSec % 60
                    String.format(Locale.US, "Recording %02d:%02d | %.1f dBFS", m, s, rms)
                }

                updateNotification(statusText, isPaused)
                FloatingBubbleManager.update(isPaused, elapsedSec)
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
            else -> Log.w(TAG, "CaptureService started with no/unknown action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleStart(intent: Intent) {
        if (engine != null || logger != null) {
            Log.w(TAG, "START ignored: a session is already active")
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val formatId = intent.getStringExtra(EXTRA_OUTPUT_FORMAT)
        val requestedFormat = AudioOutputFormat.fromId(formatId)
        val formatFallback = !requestedFormat.isAvailable
        currentFormat = if (formatFallback) AudioOutputFormat.WAV else requestedFormat

        val sessionId = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        CaptureController.resetForNewSession(sessionId, currentFormat)
        val sessionLogger = SessionLogger(this, sessionId, config, currentFormat)
        logger = sessionLogger
        if (formatFallback) {
            sessionLogger.log("WARN requested format ${requestedFormat.name} unavailable (${Mp3Encoder.unavailableReason() ?: "native encoder load failed"}); falling back to WAV")
        }
        sessionLogger.log("SERVICE handleStart resultCode=$resultCode format=${currentFormat.name} dataPresent=${data != null}")

        try {
            val notification = buildNotification("Preparing internal audio capture", false)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            notificationPosted = true
            sessionLogger.log("SERVICE startForeground type=mediaProjection ok")
        } catch (t: Throwable) {
            val msg = "startForeground(mediaProjection) failed: ${t.javaClass.name}: ${t.message}"
            sessionLogger.log("SETUP_FAILED $msg")
            CaptureController.lastError = msg
            CaptureController.set(CaptureState.FAILED, msg)
            closeQuietly()
            stopSelf()
            return
        }

        acquireWakeLock(sessionLogger)

        if (data == null) {
            failSetup(sessionLogger, "Missing MediaProjection consent result data")
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            sessionLogger.log("SERVICE getMediaProjection threw ${t.javaClass.name}: ${t.message}")
            null
        }
        if (mp == null) {
            failSetup(sessionLogger, "getMediaProjection returned null (consent token rejected)")
            return
        }
        projection = mp
        try {
            mp.registerCallback(projectionCallback, mainHandler)
            sessionLogger.log("PROJECTION obtained and callback registered")
        } catch (t: Throwable) {
            sessionLogger.log("PROJECTION registerCallback failed: ${t.javaClass.name}: ${t.message}")
            failSetup(sessionLogger, "registerCallback failed: ${t.message}")
            return
        }

        CaptureController.set(CaptureState.STARTING, "Projection token accepted; opening playback capture sink")
        val e = CaptureEngine(this, mp, config, currentFormat, sessionLogger) { result -> onSessionFinished(result) }
        engine = e
        try {
            e.start()
            sessionLogger.log("SERVICE engine.start returned; state=${CaptureController.state}")
        } catch (t: Throwable) {
            val msg = "Capture failed to start: ${t.javaClass.name}: ${t.message}"
            sessionLogger.log("SETUP_FAILED $msg")
            e.requestStop(StopReason.SETUP_ERROR, msg)
        }

        if (PreferencesManager.get(this).floatingControlsEnabled) {
            FloatingBubbleManager.show(this) { action ->
                when (action) {
                    ACTION_PAUSE -> handlePause()
                    ACTION_RESUME -> handleResume()
                    ACTION_STOP -> handleStop()
                }
            }
        }

        mainHandler.post(notificationTick)
    }

    private fun handlePause() {
        val e = engine ?: return
        if (CaptureController.state == CaptureState.RECORDING) {
            e.requestPause()
            updateNotification("Paused", true)
            FloatingBubbleManager.update(true, CaptureController.elapsedMs / 1000L)
        }
    }

    private fun handleResume() {
        val e = engine ?: return
        if (CaptureController.state == CaptureState.PAUSED) {
            e.requestResume()
            updateNotification("Recording", false)
            FloatingBubbleManager.update(false, CaptureController.elapsedMs / 1000L)
        }
    }

    private fun handleStop() {
        val e = engine
        if (e == null) {
            if (logger == null) {
                CaptureController.set(CaptureState.IDLE, "No active capture session")
                stopSelf()
            }
            return
        }
        logger?.log("USER stop requested")
        CaptureController.set(CaptureState.FINALIZING, "Stop requested; finalizing audio file")
        FloatingBubbleManager.hide()
        e.requestStop(StopReason.USER, "User pressed Stop")
    }

    private fun failSetup(sessionLogger: SessionLogger, message: String) {
        sessionLogger.log("SETUP_FAILED $message")
        try {
            val obj = JSONObject(sessionLogger.metaFile.readText())
            obj.put("finalState", "FAILED")
            obj.put("reason", "SETUP_ERROR")
            obj.put("error", message)
            obj.put("finalized", false)
            obj.put("exported", false)
            obj.put("endedAt", SessionLogger.isoNow())
            sessionLogger.writeMeta(obj)
        } catch (t: Throwable) {
            sessionLogger.log("WARN could not update meta for setup failure: ${t.message}")
        }
        sessionLogger.log("SESSION_END state=FAILED reason=SETUP_ERROR detail=$message")
        val logPath = try { MediaStoreExporter.export(this, sessionLogger.logFile, "text/plain") } catch (t: Throwable) { null }
        val metaPath = try { MediaStoreExporter.export(this, sessionLogger.metaFile, "application/json") } catch (t: Throwable) { null }
        CaptureController.lastFile = logPath
        CaptureController.lastError = message
        CaptureController.set(CaptureState.FAILED, "$message (log: ${logPath ?: "export failed"})")
        closeQuietly()
        stopAndCleanup(StopReason.SETUP_ERROR)
    }

    private fun onSessionFinished(result: SessionResult) {
        val sess = logger
        sess?.log("EXPORT_BEGIN session=${result.sessionId} finalFile=${result.audioFile?.name} wavFinalized=${result.wavFinalized} dataBytes=${result.dataBytes}")

        val exportedAudioPath = try {
            val target = result.audioFile?.takeIf { it.exists() && it.length() > 44 }
            if (target != null) {
                val mime = AudioOutputFormat.fromExtension(target.extension).mimeType
                MediaStoreExporter.export(this, target, mime)
            } else null
        } catch (t: Throwable) {
            sess?.log("WARN audio export failed: ${t.javaClass.name}: ${t.message}")
            null
        }

        try {
            val obj = JSONObject(result.metaFile.readText())
            obj.put("exported", true)
            obj.put("exportedAudio", exportedAudioPath ?: JSONObject.NULL)
            obj.put("exportedAt", SessionLogger.isoNow())
            result.metaFile.writeText(obj.toString(2))
        } catch (t: Throwable) {
            sess?.log("WARN meta pre-export update failed: ${t.message}")
        }
        sess?.log("EXPORT audio=$exportedAudioPath internal=${result.audioFile?.absolutePath}")

        val logPath = try {
            result.logFile.takeIf { it.exists() }?.let { MediaStoreExporter.export(this, it, "text/plain") }
        } catch (t: Throwable) {
            sess?.log("WARN log export failed: ${t.message}")
            null
        }
        val metaPath = try {
            val obj = JSONObject(result.metaFile.readText())
            obj.put("exportedLog", logPath ?: JSONObject.NULL)
            obj.put("exportedAudio", exportedAudioPath ?: JSONObject.NULL)
            obj.put("exportedAt", SessionLogger.isoNow())
            result.metaFile.writeText(obj.toString(2))
            MediaStoreExporter.export(this, result.metaFile, "application/json")
        } catch (t: Throwable) {
            sess?.log("WARN meta export failed: ${t.message}")
            null
        }

        val secs = result.durationMs / 1000.0
        val mb = (result.audioFile?.length() ?: result.dataBytes) / (1024.0 * 1024.0)
        val summary = String.format(
            Locale.US,
            "%s [%s] - %.1f s, %.2f MB, peak %.1f dBFS, RMS %.1f dBFS",
            result.finalState.name,
            (result.outputFormat ?: result.requestedFormat).displayName,
            secs, mb, result.peakDb, result.rmsDb
        )
        val fileNote = when {
            exportedAudioPath != null -> "Exported: $exportedAudioPath"
            result.audioFile != null -> "Saved: ${result.audioFile.name}"
            result.wavFinalized -> "Finalized (export failed)"
            else -> "No audio captured"
        }

        CaptureController.lastFile = exportedAudioPath ?: result.audioFile?.absolutePath ?: logPath
        CaptureController.lastError = result.error
        CaptureController.set(result.finalState, "$summary | $fileNote")
        sess?.log("EXPORT_DONE audio=$exportedAudioPath log=$logPath meta=$metaPath")

        mainHandler.post { stopAndCleanup(result.reason) }
    }

    private fun stopAndCleanup(reason: StopReason) {
        if (stopping) return
        stopping = true
        mainHandler.removeCallbacks(notificationTick)
        FloatingBubbleManager.hide()
        try {
            if (notificationPosted) stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {}
        notificationPosted = false
        val mp = projection
        try { mp?.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        try { mp?.stop() } catch (_: Throwable) {}
        projection = null
        engine = null
        releaseWakeLock()
        closeQuietly()
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(notificationTick)
        FloatingBubbleManager.hide()
        val e = engine
        if (e != null) {
            logger?.log("SERVICE onDestroy while session active; requesting INTERRUPTED finalize")
            e.requestStop(StopReason.SERVICE_DESTROY, "Service destroyed while capture active")
            releaseWakeLock()
        } else {
            closeQuietly()
            releaseWakeLock()
        }
        super.onDestroy()
    }

    private fun closeQuietly() {
        try { logger?.close() } catch (_: Throwable) {}
        logger = null
    }

    private fun acquireWakeLock(sessionLogger: SessionLogger) {
        try {
            val pm = getSystemService(PowerManager::class.java)
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
            wl.setReferenceCounted(false)
            wl.acquire(60L * 60L * 1000L)
            wakeLock = wl
            sessionLogger.log("WAKELOCK acquired partial timeout=60min")
        } catch (t: Throwable) {
            sessionLogger.log("WARN wakelock acquire failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Internal audio capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing internal playback capture session"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, isPaused: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeAction = if (isPaused) {
            val resumeIntent = PendingIntent.getService(
                this, 2,
                Intent(this, CaptureService::class.java).setAction(ACTION_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_play),
                "Resume",
                resumeIntent
            ).build()
        } else {
            val pauseIntent = PendingIntent.getService(
                this, 3,
                Intent(this, CaptureService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_pause),
                "Pause",
                pauseIntent
            ).build()
        }

        val stopAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stop),
            "Stop",
            stopIntent
        ).build()

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nhg)
            .setContentTitle("NHG Internal Audio")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(pauseResumeAction)
            .addAction(stopAction)

        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun updateNotification(text: String, isPaused: Boolean) {
        try {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(text, isPaused)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Notification update failed: ${t.message}")
        }
    }
}

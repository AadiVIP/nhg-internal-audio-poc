package ai.nhg.capturepoc

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

object AudioPlayerController {
    private const val TAG = "NhgAudioPlayer"

    enum class PlayState { STOPPED, PLAYING, PAUSED }

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    var currentItem: RecordingItem? = null
        private set
    var playState: PlayState = PlayState.STOPPED
        private set
    var currentPositionMs: Int = 0
        private set
    var durationMs: Int = 0
        private set

    private var progressListener: ((posMs: Int, totalMs: Int, isPlaying: Boolean) -> Unit)? = null
    private var completionListener: (() -> Unit)? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && playState == PlayState.PLAYING) {
                try {
                    currentPositionMs = mp.currentPosition
                    durationMs = mp.duration
                    progressListener?.invoke(currentPositionMs, durationMs, true)
                } catch (_: Throwable) {}
                handler.postDelayed(this, 100)
            }
        }
    }

    fun play(
        item: RecordingItem,
        onProgress: (posMs: Int, totalMs: Int, isPlaying: Boolean) -> Unit,
        onCompletion: () -> Unit
    ) {
        stop()

        currentItem = item
        progressListener = onProgress
        completionListener = onCompletion

        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(item.file.absolutePath)
                setOnPreparedListener { player ->
                    player.start()
                    playState = PlayState.PLAYING
                    durationMs = player.duration
                    handler.post(progressTicker)
                    progressListener?.invoke(0, durationMs, true)
                }
                setOnCompletionListener {
                    playState = PlayState.STOPPED
                    currentPositionMs = durationMs
                    handler.removeCallbacks(progressTicker)
                    progressListener?.invoke(durationMs, durationMs, false)
                    completionListener?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    stop()
                    true
                }
                prepareAsync()
            }
            mediaPlayer = mp
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start playback for ${item.name}", t)
            stop()
        }
    }

    fun pause() {
        val mp = mediaPlayer ?: return
        if (playState == PlayState.PLAYING) {
            try {
                mp.pause()
                playState = PlayState.PAUSED
                handler.removeCallbacks(progressTicker)
                currentPositionMs = mp.currentPosition
                progressListener?.invoke(currentPositionMs, durationMs, false)
            } catch (t: Throwable) {
                Log.e(TAG, "pause error", t)
            }
        }
    }

    fun resume() {
        val mp = mediaPlayer ?: return
        if (playState == PlayState.PAUSED) {
            try {
                mp.start()
                playState = PlayState.PLAYING
                handler.post(progressTicker)
                progressListener?.invoke(currentPositionMs, durationMs, true)
            } catch (t: Throwable) {
                Log.e(TAG, "resume error", t)
            }
        }
    }

    fun togglePlayPause() {
        when (playState) {
            PlayState.PLAYING -> pause()
            PlayState.PAUSED -> resume()
            PlayState.STOPPED -> currentItem?.let { play(it, progressListener ?: { _, _, _ -> }, completionListener ?: {}) }
        }
    }

    fun seekTo(positionMs: Int) {
        val mp = mediaPlayer ?: return
        try {
            mp.seekTo(positionMs)
            currentPositionMs = positionMs
            progressListener?.invoke(currentPositionMs, durationMs, playState == PlayState.PLAYING)
        } catch (t: Throwable) {
            Log.e(TAG, "seekTo error", t)
        }
    }

    fun stop() {
        handler.removeCallbacks(progressTicker)
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Throwable) {}
        mediaPlayer = null
        playState = PlayState.STOPPED
        currentPositionMs = 0
        durationMs = 0
        currentItem = null
    }
}

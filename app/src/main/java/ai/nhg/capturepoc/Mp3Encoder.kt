package ai.nhg.capturepoc

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Offline WAV (PCM16) to MP3 conversion backed by the bundled LAME 4.0 native
 * library. This type has no Android dependencies so it can be exercised on the
 * Termux JVM with the same native libraries that ship in the APK.
 */
object Mp3Encoder {
    const val BITRATE_KBPS = 192
    private const val QUALITY = 2
    private const val FRAMES_PER_CHUNK = 960
    private const val BYTES_PER_SAMPLE = 2
    private const val OUTPUT_BUFFER_BYTES = 64 * 1024
    private const val LOAD_UNTRIED = 0
    private const val LOAD_OK = 1
    private const val LOAD_FAILED = -1

    @Volatile private var loadState = LOAD_UNTRIED
    @Volatile private var loadFailure: String? = null

    @Volatile var lastError: String? = null
        private set

    fun isAvailable(): Boolean = ensureLoaded()

    fun unavailableReason(): String? = if (ensureLoaded()) null else loadFailure

    fun version(): String? {
        if (!ensureLoaded()) return null
        return try {
            nativeVersion()
        } catch (t: Throwable) {
            loadFailure = "${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (loadState == LOAD_OK) return true
        if (loadState == LOAD_FAILED) return false
        return try {
            System.loadLibrary("mp3lame")
            System.loadLibrary("nhgmp3")
            loadState = LOAD_OK
            true
        } catch (t: Throwable) {
            loadState = LOAD_FAILED
            loadFailure = "${t.javaClass.simpleName}: ${t.message ?: "native encoder unavailable"}"
            false
        }
    }

    fun encodeWavToMp3(
        wavFile: File,
        outputFile: File,
        bitrateKbps: Int = BITRATE_KBPS,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        lastError = null
        if (!ensureLoaded()) {
            lastError = "MP3 encoder unavailable: ${loadFailure ?: "unknown"}"
            return false
        }
        if (!wavFile.isFile || wavFile.length() <= 44L) {
            lastError = "Input WAV is missing or empty"
            return false
        }
        if (outputFile.exists() && !outputFile.delete()) {
            lastError = "Cannot replace existing output file"
            return false
        }

        val info = try {
            parseWav(wavFile)
        } catch (t: Throwable) {
            lastError = "WAV parse failed: ${t.javaClass.simpleName}: ${t.message}"
            null
        }
        if (info == null) {
            if (lastError == null) lastError = "Not a PCM16 RIFF/WAVE file"
            return false
        }

        var handle = 0L
        var success = false
        try {
            handle = nativeCreate(info.sampleRate, info.channels, bitrateKbps, QUALITY)
            if (handle == 0L) {
                lastError = "LAME initialization failed (rate=${info.sampleRate}, channels=${info.channels})"
                return false
            }

            RandomAccessFile(wavFile, "r").use { raf ->
                FileOutputStream(outputFile).use { out ->
                    raf.seek(info.dataOffset)
                    val bytesPerFrame = info.channels * BYTES_PER_SAMPLE
                    val pcmBytes = ByteArray(FRAMES_PER_CHUNK * bytesPerFrame)
                    val samples = ShortArray(FRAMES_PER_CHUNK * info.channels)
                    val outBuf = ByteArray(OUTPUT_BUFFER_BYTES)
                    var processed = 0L

                    while (processed < info.dataBytes) {
                        val maxFrames = (info.dataBytes - processed) / bytesPerFrame
                        val frames = minOf(FRAMES_PER_CHUNK.toLong(), maxFrames).toInt()
                        if (frames <= 0) break
                        val want = frames * bytesPerFrame
                        var got = 0
                        while (got < want) {
                            val n = raf.read(pcmBytes, got, want - got)
                            if (n <= 0) break
                            got += n
                        }
                        if (got != want) {
                            lastError = "Unexpected end of WAV data at $processed bytes"
                            return false
                        }

                        val sampleCount = frames * info.channels
                        var i = 0
                        while (i < sampleCount) {
                            val lo = pcmBytes[i * 2].toInt() and 0xFF
                            val hi = pcmBytes[i * 2 + 1].toInt()
                            samples[i] = ((hi shl 8) or lo).toShort()
                            i++
                        }

                        val written = nativeEncode(handle, samples, frames, outBuf)
                        if (written < 0) {
                            lastError = "LAME encode error $written at $processed bytes"
                            return false
                        }
                        if (written > 0) out.write(outBuf, 0, written)
                        processed += want
                        onProgress?.invoke(if (info.dataBytes == 0L) 1f else processed.toFloat() / info.dataBytes)
                    }

                    if (processed < info.dataBytes) {
                        lastError = "WAV data is shorter than its header declares"
                        return false
                    }

                    var flushGuard = 0
                    while (flushGuard++ < 16) {
                        val written = nativeFlush(handle, outBuf)
                        if (written < 0) {
                            lastError = "LAME flush error $written"
                            return false
                        }
                        if (written == 0) break
                        out.write(outBuf, 0, written)
                    }
                    out.flush()
                    out.fd.sync()
                }
            }

            success = outputFile.length() > 0L && startsWithMp3Frame(outputFile)
            if (!success) lastError = "Encoder produced no valid MP3 frames"
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            success = false
        } finally {
            if (handle != 0L) {
                try {
                    nativeClose(handle)
                } catch (_: Throwable) {
                }
            }
            if (!success) outputFile.delete()
        }
        return success
    }

    private fun startsWithMp3Frame(file: File): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 4L) return false
                val b0 = raf.read()
                val b1 = raf.read()
                b0 == 0xFF && (b1 and 0xE0) == 0xE0
            }
        } catch (_: Throwable) {
            false
        }
    }

    private data class WavInfo(
        val dataOffset: Long,
        val dataBytes: Long,
        val sampleRate: Int,
        val channels: Int
    )

    private fun parseWav(file: File): WavInfo? {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < 44L) return null
            val riff = ByteArray(12)
            raf.readFully(riff)
            if (ascii(riff, 0) != "RIFF" || ascii(riff, 8) != "WAVE") return null

            var sampleRate = 0
            var channels = 0
            var blockAlign = 0
            var dataOffset = -1L
            var dataBytes = 0L

            while (raf.filePointer + 8L <= length) {
                val header = ByteArray(8)
                raf.readFully(header)
                val id = ascii(header, 0)
                val size = u32(header, 4)
                val body = raf.filePointer

                when (id) {
                    "fmt " -> {
                        if (size < 16L || body + 16L > length) return null
                        val fmt = ByteArray(16)
                        raf.readFully(fmt)
                        val audioFormat = u16(fmt, 0)
                        channels = u16(fmt, 2)
                        sampleRate = u32(fmt, 4).toInt()
                        blockAlign = u16(fmt, 12)
                        val bitsPerSample = u16(fmt, 14)
                        if (audioFormat != 1 || bitsPerSample != 16) return null
                    }
                    "data" -> {
                        dataOffset = body
                        dataBytes = minOf(size, length - body)
                    }
                }

                if (dataOffset >= 0L && sampleRate > 0 && channels > 0) break

                val next = body + size + (size and 1L)
                if (next < body || next > length) return null
                raf.seek(next)
            }

            if (dataOffset < 0L || sampleRate <= 0) return null
            if (channels < 1 || channels > 2) return null
            if (blockAlign != 0 && blockAlign != channels * BYTES_PER_SAMPLE) return null

            val frameBytes = channels * BYTES_PER_SAMPLE
            dataBytes -= dataBytes % frameBytes
            if (dataBytes <= 0L) return null
            if (dataOffset + dataBytes > length) return null
            return WavInfo(dataOffset, dataBytes, sampleRate, channels)
        }
    }

    private fun ascii(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private external fun nativeCreate(
        sampleRate: Int,
        channels: Int,
        bitrateKbps: Int,
        quality: Int
    ): Long

    private external fun nativeEncode(
        handle: Long,
        pcm: ShortArray,
        frames: Int,
        out: ByteArray
    ): Int

    private external fun nativeFlush(handle: Long, out: ByteArray): Int

    private external fun nativeClose(handle: Long)

    private external fun nativeVersion(): String
}

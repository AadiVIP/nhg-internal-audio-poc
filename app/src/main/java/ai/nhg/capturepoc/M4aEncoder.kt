package ai.nhg.capturepoc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

object M4aEncoder {
    private const val TAG = "NhgM4aEncoder"

    fun encodeWavToM4a(
        wavFile: File,
        outputFile: File,
        bitRate: Int = 192000,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        if (!wavFile.exists() || wavFile.length() <= 44) {
            Log.e(TAG, "Input WAV file invalid or too small: ${wavFile.absolutePath}")
            return false
        }

        var raf: RandomAccessFile? = null
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var audioTrackIndex = -1

        try {
            raf = RandomAccessFile(wavFile, "r")
            val header = ByteArray(44)
            raf.readFully(header)

            val riff = String(header, 0, 4, Charsets.US_ASCII)
            val wave = String(header, 8, 4, Charsets.US_ASCII)
            if (riff != "RIFF" || wave != "WAVE") {
                Log.e(TAG, "Not a valid WAV file: $riff / $wave")
                return false
            }

            val channels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
            val sampleRate = (header[24].toInt() and 0xFF) or
                    ((header[25].toInt() and 0xFF) shl 8) or
                    ((header[26].toInt() and 0xFF) shl 16) or
                    ((header[27].toInt() and 0xFF) shl 24)
            val dataBytes = wavFile.length() - 44
            if (dataBytes <= 0 || channels <= 0 || sampleRate <= 0) {
                Log.e(TAG, "Invalid audio parameters: channels=$channels sampleRate=$sampleRate dataBytes=$dataBytes")
                return false
            }

            if (outputFile.exists()) outputFile.delete()

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            val chunk = ByteArray(4096)
            var totalReadBytes = 0L
            var totalInputFrames = 0L
            var inputDone = false
            var outputDone = false
            val bytesPerFrame = channels * 2

            while (!outputDone) {
                if (!inputDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputBufferIndex)
                        inputBuffer?.clear()
                        val remainingBytes = dataBytes - totalReadBytes
                        val toRead = minOf(chunk.size.toLong(), remainingBytes).toInt()

                        if (toRead > 0) {
                            val n = raf.read(chunk, 0, toRead)
                            if (n > 0) {
                                inputBuffer?.put(chunk, 0, n)
                                val ptsUs = (totalInputFrames * 1_000_000L) / sampleRate
                                val frames = n / bytesPerFrame
                                totalInputFrames += frames
                                totalReadBytes += n
                                codec.queueInputBuffer(inputBufferIndex, 0, n, ptsUs, 0)
                                onProgress?.invoke(minOf(1f, totalReadBytes.toFloat() / dataBytes.toFloat()))
                            } else {
                                inputDone = true
                                val ptsUs = (totalInputFrames * 1_000_000L) / sampleRate
                                codec.queueInputBuffer(inputBufferIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                        } else {
                            inputDone = true
                            val ptsUs = (totalInputFrames * 1_000_000L) / sampleRate
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        Log.e(TAG, "MediaCodec reported format change multiple times")
                    } else {
                        val newFormat = codec.outputFormat
                        Log.i(TAG, "Encoder output format changed: $newFormat")
                        audioTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                } else if (outputBufferIndex >= 0) {
                    val outBuffer: ByteBuffer? = codec.getOutputBuffer(outputBufferIndex)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted && outBuffer != null) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(audioTrackIndex, outBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }

            Log.i(TAG, "M4A encode finished successfully: ${outputFile.name} (${outputFile.length()} bytes)")
            return outputFile.exists() && outputFile.length() > 0
        } catch (t: Throwable) {
            Log.e(TAG, "M4A encoding failed", t)
            try { if (outputFile.exists()) outputFile.delete() } catch (_: Throwable) {}
            return false
        } finally {
            try { raf?.close() } catch (_: Throwable) {}
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try {
                if (muxerStarted && muxer != null) {
                    muxer.stop()
                    muxer.release()
                }
            } catch (_: Throwable) {}
        }
    }
}

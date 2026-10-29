# Format strategy — WAV, M4A/AAC, MP3

Status labels used below:

- **research completed** — API/container/codec behavior researched from sources in
  `SOURCES.md`.
- **implementation completed** — code exists in this repository.
- **independently validated** — output decoded/probed by a tool outside the app, with
  evidence in `evidence/`.
- **validated on target device** — the format was produced and played/verified on the
  vivo V25 itself.

An extension, a successful compile, a unit test, or a UI message never upgrades a status.

## Status matrix

| Format | Research | Implementation | Independent validation | Target-device validation |
|---|---|---|---|---|
| WAV (PCM16) | completed | completed in POC (`WavWriter`) | pending until capture tests | pending |
| M4A/AAC | completed (this document) | not started | not started | not started |
| MP3 | completed (this document) | not started | not started | not started |

The POC produces WAV only. M4A and MP3 are production-phase work; implementing them is
outside Mission 1.

## 1. WAV — the evidence and master format

Container: RIFF/WAVE, `fmt ` chunk of 16 bytes, PCM format 1, 2 channels, 48,000 Hz,
16 bits per sample, block align 4, byte rate 192,000, `data` chunk with interleaved
little-endian signed shorts.

Why WAV first:
- No encoder, no codec delay, no container muxer failure mode.
- Byte-exact samples can be analyzed independently (tones, RMS, zero fraction).
- Header sizes are simple to patch and to repair after a crash.

POC implementation (`CaptureCore.kt`, `WavWriter`):
- Header written with zero sizes at start; PCM appended continuously.
- On finalize: seek to 0, rewrite RIFF size (`36 + data`) and `data` size, `fsync`,
  close. `dataBytes` is the source of truth.
- `repair()` validates `RIFF`/`WAVE`, truncates to a whole block, and patches sizes.
- Limit: 32-bit RIFF sizes cap at 4 GiB; at 192 kB/s that is about 6.2 hours. Beyond that,
  RF64/BW64 would be required; not part of NHG's current scope.
- Export: app-external file -> MediaStore Downloads with `IS_PENDING=1`, copy, clear
  pending. A production recorder can write directly to a pending MediaStore file if it
  needs to avoid double I/O; header patching then requires a seekable file descriptor
  (`ContentResolver.openFileDescriptor(uri, "rw")` + `FileChannel.position`). That change
  is not made in the POC because the export-at-end path is simpler to audit.

Validation requirements: `ffprobe` reports `pcm_s16le`, 48000 Hz, 2 channels; duration
matches frames; `tools/analyze_wav.py` finds the expected tones; file plays on the device.

## 2. M4A / AAC — production plan

Target: AAC-LC in an MP4/M4A container, 48 kHz stereo, 128–192 kbps (speech/music
compromise), optional 256 kbps for music. AAC-LC is universally supported by Android
decoders; HE-AAC is not needed and adds complexity.

Pipeline (on-device, no network):

1. Capture PCM exactly as the POC does (`AudioRecord` + APCC).
2. Feed the read loop's `ShortArray` to a `MediaCodec` encoder:
   - `MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2)`
   - `KEY_AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC`
   - `KEY_BIT_RATE = 128000..256000`, `KEY_MAX_INPUT_SIZE` from the codec format.
   - `configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)`.
3. Dequeue input buffers, copy PCM bytes (16-bit little-endian), set
   `presentationTimeUs = framesWritten * 1_000_000 / 48000`, queue.
4. Dequeue output buffers; on `INFO_OUTPUT_FORMAT_CHANGED`, add the AAC track to a
   `MediaMuxer` and start it; write each encoded frame with
   `BufferInfo.presentationTimeUs`, flags, size.
5. On stop: queue end-of-stream, drain, `muxer.stop()`, `muxer.release()`,
   `codec.stop()/release()`.
6. Write the muxer to an app-private temp file, then export to MediaStore Downloads
   (`audio/mp4`), so an interrupted encode never leaves a half-finalized MediaStore item.
7. Keep the WAV master; M4A is a deliverable, not the archival source.

Constraints and risks:
- `MediaMuxer` output must be seekable; a temp file is the reliable choice.
- Codec availability/profile support must be checked at runtime
  (`MediaCodecList`); if AAC-LC encoding is unavailable, fail cleanly rather than
  mislabeling.
- Encoder delay/priming means M4A duration may differ from PCM by a few milliseconds;
  validation must tolerate codec delay or use container timing metadata. Do not call a
  duration mismatch a capture failure without inspecting the stream.
- Encoding can run concurrently with capture, but CPU load on a mid-range phone can cause
  dropped PCM or encoder backpressure. The safer production path is: capture WAV first,
  then encode from the finalized WAV, with a progress state. If live encoding is needed,
  bound the queue and account for `INFO_TRY_AGAIN_LATER`.
- A process kill during muxing leaves an unplayable temp file; recovery should delete it
  and re-encode from the repaired WAV, never expose it as complete.

Validation requirements: `ffprobe` reports `aac`, profile LC, 48000 Hz, 2 channels;
`ffmpeg` decodes without errors; decoded tone amplitudes match the WAV within a small
tolerance; duration within codec-delay tolerance; file plays in VLC/YouTube Music on the
device. Only then "independently validated on target device".

## 3. MP3 — production plan and licensing

Android's platform `MediaCodec` provides MP3 **decoding**, not encoding. There is no
platform MP3 encoder. Options:

| Option | Verdict |
|---|---|
| LAME 3.100 compiled with the NDK | Recommended. LGPL 2.1; dynamic-link and provide relinkable object code/source per LGPL. MP3 patents expired by 2017, so no royalty. |
| ffmpeg with libmp3lame bundled as a native library | Works but much larger; license depends on build flags (LGPL vs GPL). Keep the build script and license notices. |
| Server-side transcode | Not acceptable for an offline, no-upload recorder unless explicitly offered as an opt-in cloud feature (outside Mission 1). |
| Renaming WAV/AAC to `.mp3` | Never. Not an encoder. |

Recommended integration:
- Build LAME as a shared native library (`libmp3lame.so`) for arm64-v8a (and other ABIs as
  needed), wrap with JNI: `lame_init`, `lame_set_in_samplerate(48000)`,
  `lame_set_num_channels(2)`, `lame_set_brate(192)`, `lame_set_mode(STEREO)`,
  `lame_set_quality(2)`, `lame_init_params`, `lame_encode_buffer_interleaved`, `lame_flush`,
  `lame_close`.
- Feed the same PCM read loop or encode from the WAV master. Encode from the master first;
  it is simpler and keeps the capture path identical to the proven POC.
- Write ID3v2 tags (title/artist/album = NHG, date, comment with session id) and a
  Xing/LAME header for duration and gapless metadata. Without the Xing header, VBR
  duration can be wrong.
- Export as `audio/mpeg` to MediaStore Downloads; keep WAV master.
- LAME adds encoder delay/padding (~576 samples plus padding). Use gapless metadata; for
  strict duration checks, compare decoded sample counts or allow the documented delay.

Validation requirements: `ffprobe` reports `mp3`, 48000 Hz, 2 channels, expected bitrate;
`ffmpeg` decodes without errors; tone analysis matches; duration within LAME delay
tolerance; plays on the device. License notices and LGPL relink provisions must ship with
the production app.

## 4. Recommended production format policy

- Always capture and retain a WAV master (or a lossless FLAC master if size matters; FLAC
  would be another encoder decision).
- Offer M4A/AAC as the default share format (small, universally playable, platform codec).
- Offer MP3 only with LAME and correct licensing; it is the most compatible with old
  players but adds native code and license obligations.
- Never delete the WAV until the encoded file has been independently decoded and its
  duration/levels verified.
- Surface the actual container/codec/bitrate in the UI; do not infer from file extension.

## 5. Interface contract for the production encoder layer

The capture core should stay encoder-agnostic. A minimal contract:

```kotlin
interface EncodedSink {
    fun start(sampleRate: Int, channels: Int, bitrate: Int)
    fun writePcm(samples: ShortArray, count: Int)
    fun finish(): File   // finalized, seekable, playable container
    fun abort()
}
```

`WavSink` is the POC's writer. `AacM4aSink` and `LameMp3Sink` implement the same contract
in production. Capture lifecycle, revocation, and recovery stay in `CaptureEngine`; an
encoder failure is a session failure, never a silent success.

## 6. Unresolved risks

1. vivo codec behavior (AAC encoder profile/bitrate support) is unverified.
2. Live AAC encoding under screen-off/vendor power management may be killed; encoding
   from the WAV master reduces exposure.
3. MP3 licensing/relink obligations must be reviewed before shipping.
4. Gapless metadata and duration checks need device-specific verification.
5. MediaStore duplicate naming and pending-file cleanup need production hardening.

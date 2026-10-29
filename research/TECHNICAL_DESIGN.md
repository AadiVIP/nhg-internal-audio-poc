# Technical design — NHG internal-audio POC 0.1.0

Scope: the smallest real Android app that can prove or disprove internal playback capture
on the vivo V25, without root, microphone, or network. This is a research instrument, not
a production recorder.

## 1. Architecture

```
MainActivity (single activity, programmatic UI)
  |  createScreenCaptureIntent() consent (API 34+: createConfigForDefaultDisplay)
  |  startForegroundService(ACTION_START, resultCode, resultData)
  v
CaptureService (foreground, type=mediaProjection)
  |  startForeground(type) BEFORE getMediaProjection()
  |  MediaProjection + Callback(onStop)
  |  partial wake lock (60 min timeout)
  v
CaptureEngine (audio thread)
  |  AudioPlaybackCaptureConfiguration.Builder(projection)
  |    .addMatchingUsage(MEDIA/GAME/UNKNOWN)
  |  AudioRecord.Builder()
  |    .setAudioFormat(PCM16, 48 kHz, stereo)
  |    .setBufferSizeInBytes(max(minBuffer, 4*chunk))
  |    .setAudioPlaybackCaptureConfig(apcc)
  |  blocking read -> WavWriter + CaptureStats
  v
SessionLogger + WavWriter        MediaStoreExporter
  app-external sessions/<id>.*  ->  /sdcard/Download/NHG-InternalAudioPOC/
```

Source files:

| File | Responsibility |
|---|---|
| `app/src/main/java/ai/nhg/capturepoc/MainActivity.kt` | consent, start/stop, live state, recovery trigger, crash-test hook |
| `.../CaptureService.kt` | FGS lifecycle, projection token, ordering, wake lock, export, cleanup |
| `.../CaptureCore.kt` | config, state/controller, logger, WAV writer/repair, stats, engine, recovery |
| `app/src/main/res/values/*` | dark theme, app name |
| `app/src/main/res/drawable/ic_nhg.xml` | vector icon |
| `app/src/main/AndroidManifest.xml` | 4 permissions, activity, non-exported service |

There is no Gradle, no AndroidX, no third-party runtime dependency. The APK contains only
the app classes, the Kotlin standard library, resources, and the manifest.

## 2. Lifecycle and ordering constraints

The exact sequence in `CaptureService.handleStart()` (`CaptureService.kt`, lines 94-186):

1. Reject a second start while a session object exists.
2. Create the session logger and JSON metadata before any risky call, so setup failures
   are still logged.
3. Create the notification and call
   `startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`
   (line 116). This must happen before step 4.
4. Acquire the partial wake lock.
5. Call `MediaProjectionManager.getMediaProjection(resultCode, data)` **once** (line 141).
6. Register `MediaProjection.Callback` on the main looper (line 152).
7. Build the APCC/AudioRecord and start the read thread (`CaptureEngine.start()`).
8. Update the notification once per second with elapsed time and window RMS.

The activity obtains a **fresh** consent intent for every Start:
`MediaProjectionConfig.createConfigForDefaultDisplay()` on API 34+, otherwise
`createScreenCaptureIntent()` (`MainActivity.kt`, `startCapture`). A cancelled consent
returns the app to `IDLE` and creates no session/WAV.

Stop paths:
- User presses Stop -> `ACTION_STOP` -> `requestStop(USER)` -> finalize.
- System/user revokes projection -> `Callback.onStop` -> `requestStop(PROJECTION_STOP)`
  -> finalize as `REVOKED`.
- Read error -> `requestStop(READ_ERROR)` -> `FAILED`.
- Service destroyed while active -> `shutdownBlocking`/`requestStop(SERVICE_DESTROY)` ->
  `INTERRUPTED`.
- Process killed (long-press test) -> no finalize; next launch repairs the WAV and marks
  `INTERRUPTED`.

First-reason-wins is enforced under `stopLock`; finalization runs once via an
`AtomicBoolean` (`CaptureCore.kt`, lines 626-652). A late success cannot overwrite an
earlier revocation or error.

## 3. State machine

| State | Meaning | Terminal |
|---|---|---|
| `IDLE` | no session | yes |
| `REQUESTING_CONSENT` | system consent UI is open | no |
| `STARTING` | token accepted, sink opening | no |
| `CAPTURING` | read thread delivering/attempting PCM | no |
| `STOPPING` | stop requested | no |
| `SAVING` | WAV header finalize, SHA-256, export | no |
| `COMPLETED` | user-stopped, data > 0, nonzero peak | yes |
| `COMPLETED_SILENT` | user-stopped, data > 0, all-zero peak | yes |
| `FAILED` | setup/read/finalize error or no data | yes |
| `REVOKED` | projection stopped by system/user | yes |
| `INTERRUPTED` | recovered after process death | yes |

`COMPLETED_SILENT` is deliberately separate from `COMPLETED`: a valid file of digital
silence is evidence that the path opened but the source was ineligible/muted, not evidence
of captured audio.

## 4. Data path and formats

- Requested input: `ENCODING_PCM_16BIT`, `CHANNEL_IN_STEREO`, 48 kHz. The platform may
  resample; the WAV header describes what was requested, so the test must compare
  `AudioRecord.getSampleRate()`/read behavior and the independent probe. If the device
  cannot honor the format, construction/start fails rather than mislabeling.
- Read chunk: 960 frames (20 ms) = 3,840 bytes. Buffer:
  `max(getMinBufferSize(), 4 * 3,840)` bytes. One read thread; no audio on the UI thread.
- Writing: each short is converted to two little-endian bytes and appended immediately to
  the file; the 44-byte RIFF/WAVE header is rewritten on finalize with the real
  `RIFF`/`data` sizes and `fsync`.
- Stats: per-read peak, sum of squares, nonzero/zero/clipped counts, windowed RMS/peak in
  dBFS. `peak >= 32760` counts as clipped. dBFS uses full scale 32768.
- Timestamp: one `getTimestamp(TIMEBASE_BOOTTIME)` poll at about one second; logged, not
  used for timing.
- Screen/keyguard: polled every 250 ms and logged with elapsed ms. `FLAG_KEEP_SCREEN_ON`
  is set only while the activity is visible, so the screen does not time out during tests
  but the power button can still lock it for test D.
- No volume normalization, no gain, no DSP, no channel mixing. What the mix delivers is
  written unchanged.

## 5. Session artifacts

Internal (app-specific external files, not relied on for evidence because scoped storage
may block Termux):

```
sessions/<sessionId>.wav
sessions/<sessionId>.log
sessions/<sessionId>.json
```

`<sessionId>` = `yyyyMMdd-HHmmss-SSS` local time, so runs cannot overwrite each other.
The JSON records device/build/config/requested permissions, final state/reason, durations,
stats, SHA-256, screen-off/keyguard durations, zero-read count, and export paths. The
logger flushes every line.

Exported copies (MediaStore Downloads, visible to Termux and to the user):

```
/sdcard/Download/NHG-InternalAudioPOC/<original name>
```

MediaStore may rename duplicates (e.g. `... (1).wav`); the app queries and stores the
actual display name. WAV + log + JSON are exported at session end; setup failures export
log + JSON. The export path shown in the UI is the authoritative artifact path.

## 6. Crash recovery

`SessionRecovery.recoverAndExport()` scans session JSONs with `exported=false`. It skips a
session that is active in this process (matching `CaptureController.sessionId` and a
non-terminal state). For an unfinalized WAV it:

1. validates `RIFF`/`WAVE` and the block alignment,
2. truncates to a whole number of frames,
3. patches `RIFF` and `data` sizes,
4. marks the JSON `finalized` where possible, `interrupted=true`,
   `finalState=INTERRUPTED`, `recoveredAt`, and a note that this is not a success,
5. exports WAV/log/JSON through MediaStore and marks `exported=true`.

Recovery cannot know the true stop reason. `INTERRUPTED` is never promoted to
`COMPLETED`; the WAV may be intact but the session is not reported as a successful user
stop.

## 7. Export and evidence rules

- A file is only reported as a WAV if the header was finalized (`wavFinalized`) and
  `dataBytes > 0`; a 44-byte file is not exported as a capture.
- SHA-256 is computed over the finalized WAV before export.
- The UI reports state, detail, elapsed, frames, MB, RMS/peak, session, export path, and
  the last log lines. The notification mirrors elapsed/RMS.
- The POC itself never decides "proven". Independent validation (ffprobe/ffmpeg/Python,
  tone analysis, hashes) decides, per `EXPERIMENT_PROTOCOL.md`.

## 8. Build and packaging

`scripts/build.sh` is the reproducible build and the manifest gate:

```
aapt2 compile -> aapt2 link (targetSdk 35) -> javac R.java -> kotlinc -> d8
-> add classes.dex -> zipalign -> apksigner (v2/v3) -> audit -> SHA-256
```

- Build tools 36.0.0, Kotlin 2.4.21/JVM 21, JDK 21 javac, target device API 35.
- Link/compile uses `android-34/android.jar` because the locally installed aapt2 cannot
  load the local API 35/36 jars (verified with a minimal manifest; API 34 loads). The APK
  still declares `targetSdkVersion 35`, `minSdkVersion 29`. This is an environment
  limitation, recorded in `ENVIRONMENT.md`; the app does not use API-35-only symbols.
- Release signing uses a key supplied through environment variables; the keystore and
  passwords are never committed or distributed. The published POC APK was signed with a
  dedicated non-production key.
- The build fails if `RECORD_AUDIO` or `INTERNET` appears in the merged manifest.
- Output: `dist/NHG-InternalAudioPOC-v0.1.0-poc.apk`, plus `build/build-*.log`,
  `build/badging.txt`, `build/merged-permissions.txt`, `build/signature.txt`,
  `build/apk-sha256.txt`.

Installation on this device is via the system package-installer UI (Termux
`termux-open`); direct `pm install` is blocked by SELinux/user restrictions recorded in
`ENVIRONMENT.md`. The install method is reported honestly; if it required a manual tap,
the report says so.

## 9. Deliberate limitations

- One capture session at a time; one screen, no app-specific window capture choice.
- Fixed 48 kHz/stereo/PCM16 request; no auto-negotiation.
- No encoder in the APK. WAV only. M4A/MP3 are researched in `FORMAT_STRATEGY.md`.
- No microphone fallback of any kind. If playback capture fails, the POC cannot record
  room audio, by design.
- No network, analytics, ads, accounts, or background retry.
- No attempt to defeat source-app capture policy, DRM, secure surfaces, or vendor
  restrictions. An ineligible source is reported as silence/failure.
- UI text is English; no localization.
- The POC key is public by design; the APK is for this device only, not distribution.

## 10. Open risks before experiments

1. Docs-vs-AOSP `RECORD_AUDIO` conflict (Q2). If vivo's audio policy enforces the docs,
   capture will fail without the permission; that would make the no-permission constraint
   unachievable on this device and must be reported.
2. Audio-only projection with no virtual display is not the documented mainstream path;
   Android 14+ projection bookkeeping may behave differently on Funtouch OS.
3. Screen lock may revoke projection (Android 15 QPR1); test D may prove only that
   limitation.
4. Vendor background killing during screen-off; the wake lock/FGS is not a guarantee.
5. Third-party source apps may have opted out; a controlled fixture/source may be needed.
6. Source media volume, mute mode, and audio focus can change the captured level; the
   protocol controls what it can and records the rest.

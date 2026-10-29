# Android internal playback capture — research

Target: vivo V25 5G (V2202), Android 15 / API 35, Funtouch OS 15, unprivileged app UID
(`u0_a365`), no root, no ADB. Research date: 2026-10-28. All local source paths are
relative to this project; URLs are listed in `SOURCES.md`.

This document is research plus a design rationale. It is not a claim that capture works
on this device. Only `POC_TEST_REPORT.md` and the independently verified artifacts in
`evidence/` may establish that.

## The eight mandatory questions

### Q1. Is there a supported, no-root way to copy internal playback audio?

Yes in principle. Android 10 (API 29) introduced the playback-capture API:
`MediaProjection` consent + `AudioPlaybackCaptureConfiguration` (APCC) + `AudioRecord`.
The capturing app receives a PCM mix of eligible players. It does not need root, a
privileged shell, or the microphone. Official overview:
https://developer.android.com/media/platform/av-capture (`sources/av-capture.txt`, lines
48-79).

This does **not** mean every app's audio is capturable, and it does not authorize
bypassing DRM, secure playback, or a source app's policy. It is a user-consented,
policy-filtered copy of the playback mix.

### Q2. Is `RECORD_AUDIO` required? Documentation vs implementation

The public guide says the capture app "must have the `RECORD_AUDIO` permission"
(`sources/av-capture.txt`, lines 63-68). If that were the whole contract, this POC's
no-`RECORD_AUDIO` requirement would be impossible.

AOSP Android 15 shows a different implementation path:

1. `AudioRecord.Builder.setAudioPlaybackCaptureConfig()` stores an APCC
   (`sources/aosp/AudioRecord.java`, lines 724-730). When present, `build()` takes the
   playback-capture branch.
2. That branch creates an `AudioMix` with `ROUTE_FLAG_LOOP_BACK | ROUTE_FLAG_RENDER`
   (`sources/aosp/AudioPlaybackCaptureConfiguration.java`, lines 134-136), registers an
   `AudioPolicy` carrying the `MediaProjection`
   (`sources/aosp/AudioRecord.java`, lines 789-797), and creates an `AudioRecord` sink.
3. `AudioPolicyService`/`AudioService` validate the projection token. For a
   `RENDER|LOOPBACK` mix, a valid, audio-capable projection is sufficient; no
   `RECORD_AUDIO` check appears in `isPolicyRegisterAllowed()`
   (`sources/aosp/AudioService.java`, lines 12502-12636). `canProjectAudio()` returns
   true for screen-capture projections
   (`sources/aosp/MediaProjectionManagerService.java`, lines 1024-1028).
4. In `AudioPolicyInterfaceImpl.cpp`, `AUDIO_SOURCE_REMOTE_SUBMIX` is exempt from the
   `recordingAllowed()` check (lines 641-654), and the
   `API_INPUT_MIX_PUBLIC_CAPTURE_PLAYBACK` case says the use case "has been validated in
   audio service with a MediaProjection token, and doesn't rely on regular permissions"
   (lines 714-721).
5. The `@RequiresPermission(RECORD_AUDIO)` on the relevant `AudioRecord` API is a lint
   annotation, not a runtime permission check in this path.

Interpretation: the documented requirement may be conservative or outdated for this
specific path; the implementation enforces the projection token instead. **This conflict
is exactly why the POC omits `RECORD_AUDIO` and is tested empirically.** If the target
device's vendor audio policy follows the documented rule instead of AOSP, capture setup
will fail with `SecurityException`/`UnsupportedOperationException` or silence; that
outcome will be reported, not hidden.

The POC never calls a microphone API and never declares or requests `RECORD_AUDIO`.
Omitting the permission also makes the mic-exclusion test structural.

### Q3. Which audio is eligible?

From `AudioPlaybackCaptureConfiguration` (API 29,
https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration,
`sources/apcc.txt`, lines 23-50):

- Player usage MUST be `USAGE_UNKNOWN`, `USAGE_GAME`, or `USAGE_MEDIA`. All other usages
  cannot be captured.
- The effective capture policy must be `ALLOW_CAPTURE_BY_ALL`, where the most restrictive
  of manifest `android:allowAudioPlaybackCapture`, `AudioManager.setAllowedCapturePolicy`,
  and per-player `AudioAttributes` wins.
- Manifest `allowAudioPlaybackCapture` must be true, or unset with source
  `targetSdkVersion >= 29`. Apps targeting 28 or lower must explicitly opt in
  (`sources/av-capture.txt`, lines 158-171).
- Both apps must be in the same user profile.

The capture app can constrain its own capture with `addMatchingUsage`/`excludeUsage` and
`addMatchingUid`/`excludeUid`; inclusive and exclusive forms cannot be mixed
(`sources/av-capture.txt`, lines 88-120; `sources/apcc.txt`, lines 170-240). The POC uses
inclusive usages MEDIA, GAME, UNKNOWN and no UID filters.

System components can capture by default for accessibility/captioning. Apps can restrict
them only with `ALLOW_CAPTURE_BY_NONE`; the documentation recommends allowing the system
(`sources/av-capture.txt`, lines 185-202). The POC does not alter any other app's policy.

Consequences for tests:
- Third-party players (VLC, Musicolet) may or may not opt out. This must be observed.
- `com.termux.api` targets SDK 28 and does not opt in, so it is a useful negative source:
  its playback should not be captured by default.
- DRM/secure audio may be silent or excluded. No bypass is attempted.

### Q4. Android 10–15 behavior and the exact consent/FGS sequence

Playback capture exists since API 29. On the target device only Android 15 can be tested;
older versions are documented here but are **not** independently verified on this device.

Android 14 (API 34) changes that affect this POC:
- Apps targeting 34+ must declare a foreground-service type and hold the matching
  permission. For projection the type is `mediaProjection` /
  `FOREGROUND_SERVICE_MEDIA_PROJECTION`
  (`sources/fgs-changes14.txt`, lines 386-403; `sources/fgs-types.txt`, lines 301-317).
- The documented runtime order is: call `createScreenCaptureIntent()` and get user
  consent, start the media-projection foreground service, then call
  `getMediaProjection()` (`sources/fgs-types.txt`, lines 309-317). The POC does exactly
  this: the activity requests consent, then starts the service; the service calls
  `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` before
  `getMediaProjection()`.
- User consent is required for each capture session. A cached consent `Intent` cannot be
  reused, and each `MediaProjection` instance must be used once
  (`sources/bc14.txt`, lines 530-558). The POC obtains a fresh consent every Start.
- A `MediaProjection.Callback` must be registered; without it, `createVirtualDisplay()`
  throws (`sources/bc14.txt`, lines 577-591). The POC registers the callback before
  creating the capture sink and stops on `onStop`.
- The POC does not call `createVirtualDisplay()` at all; audio capture only needs the
  token. The Android 14 "one virtual display per projection" rule is therefore not
  exercised. This is deliberate and is a compatibility risk to watch in the test log.

Android 15 (API 35) changes:
- On Android 15 QPR1+, a status-bar chip shows an active projection and the system
  **automatically stops projection when the screen is locked**
  (`sources/bc15-all.txt`, lines 362-374). `MediaProjection.Callback.onStop` explicitly
  lists screen lock as a system-stop reason (`sources/mp-callback.txt`, lines 209-228).
  Test D must therefore distinguish "capture continued with screen off" from "projection
  was revoked at lock"; either is a valid observation, but only the former proves
  screen-off capture.
- Apps targeting Android 15 cannot launch a `mediaProjection` FGS from a
  `BOOT_COMPLETED` receiver (`sources/fgs-types.txt`, lines 323-330). This POC only starts
  from a visible activity after consent, so this restriction does not apply.
- Target 35 enforces edge-to-edge; the POC uses `fitsSystemWindows` for its scroll view.
  This is cosmetic and unrelated to capture.

Android 10–13 (not tested here): the same APCC/usage/policy contract applies from API 29.
Later releases added background-FGS restrictions, the Android 13 `POST_NOTIFICATIONS`
runtime permission, and tighter projection consent UI. None of those change the audio
eligibility rules above. Do not cite this paragraph as device evidence.

`MediaProjection` is a one-time token. A second `getMediaProjection()` with the same
result `Intent`, or reuse after `onStop`, must fail. The POC requests new consent for
every session and treats `onStop` as terminal (`REVOKED`), never as success.

### Q5. Required foreground service, permissions, notification

Manifest declarations used by the POC (and nothing else):

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE` | host the session |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | required type permission on API 34+ |
| `POST_NOTIFICATIONS` | show the ongoing notification on API 33+; capture does not depend on it |
| `WAKE_LOCK` | keep the CPU alive during the deliberate screen-off test |

The service declares `android:foregroundServiceType="mediaProjection"`, is not exported,
and returns `START_NOT_STICKY`. It calls
`startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`
before `getMediaProjection()`, holds a non-reference-counted partial wake lock with a
60-minute timeout, and releases it at session end. This ordering is required by the
Android 14/15 documentation cited above; doing it in the reverse order can fail.

Deliberately absent: `RECORD_AUDIO`, `INTERNET`, storage permissions, `SYSTEM_ALERT_WINDOW`,
accessibility, and device-admin rights. The APK audit in `scripts/build.sh` fails the
build if `RECORD_AUDIO` or `INTERNET` appears in the merged manifest.

### Q6. vivo/Funtouch OS, background, screen-off

No public vivo documentation establishes playback-capture behavior. Funtouch OS adds its
own background/battery management and privacy UI, and the Android 15 QPR1 auto-stop rule
may or may not be present in this firmware (`PD2215F_EX_A_15.3.16.0.W30`). These are
**unknowns**, not assumptions:

- A foreground service with an ongoing notification and a wake lock is the strongest
  unprivileged guarantee available, but vendor battery managers can still kill it.
- Screen lock may terminate the projection (QPR1 behavior). The POC records
  `PowerManager.isInteractive` and `KeyguardManager.isKeyguardLocked` every 250 ms and
  logs the transitions, so the screen-off interval is evidenced independently of the
  WAV.
- If the projection is revoked, `onStop` is the only reliable signal. The POC's read loop
  would otherwise keep writing zeros; the callback stops and marks `REVOKED`.
- Battery optimization/auto-start settings are not modified by the POC. If the vendor
  kills the process, `SessionRecovery` repairs the WAV header and marks the session
  `INTERRUPTED`; that is not a success.

There is no way to force screen-off from an unprivileged app. The protocol uses the power
button and the app's own screen/keyguard log as evidence.

### Q7. PCM format, buffers, timestamps, errors

The POC requests 48 kHz, stereo, signed 16-bit little-endian PCM
(`ENCODING_PCM_16BIT`, `CHANNEL_IN_STEREO`). The platform mixes playback at its own rate;
`AudioRecord` may resample. If the requested format is unsupported, construction or start
fails and the session becomes `FAILED`; it is never silently relabeled.

- Buffer: `AudioRecord.getMinBufferSize()` is checked; the record buffer is
  `max(minBuffer, 4 * chunkBytes)`, with a 960-frame (20 ms) read chunk.
- Read loop: blocking `read(ShortArray, ..., READ_BLOCKING)`. Negative results are
  `ERROR_BAD_VALUE (-2)`, `ERROR_INVALID_OPERATION (-3)`, `ERROR_DEAD_OBJECT (-6)`, or
  `ERROR (-1)`; any negative result finalizes the WAV and marks `FAILED`.
- Zero-length reads are counted and logged (first occurrence and every 100th); they are
  not treated as success.
- Timestamps: one `getTimestamp(..., TIMEBASE_BOOTTIME)` poll after roughly one second of
  audio; success or failure is logged. Timestamps are diagnostic only; the WAV duration
  comes from actual frames written.
- Streaming: shorts are converted to little-endian bytes and appended to the WAV
  continuously, not accumulated in RAM. Statistics (peak, RMS per channel, nonzero/zero/
  clipped counts, windowed dBFS) are computed on the fly.
- Threading: service/UI never block on the audio read; a dedicated read thread writes the
  WAV. The finalizer is guarded by an `AtomicBoolean` and first-reason-wins stop logic so
  a later "success" cannot overwrite a revocation/error.

### Q8. Output formats vs encoding

Capture produces PCM only. Deliverables require containers/encoders:

- **WAV**: direct RIFF/WAVE PCM16 writing. The POC patches RIFF and `data` sizes on
  finalize and can repair an unfinalized file. Independently checkable with
  `ffprobe`, `ffmpeg` and a Python `wave` reader.
- **M4A/AAC**: platform `MediaCodec` AAC-LC encoder + `MediaMuxer` MPEG-4 container. No
  extra native library. Not yet implemented in the POC; contract is in
  `FORMAT_STRATEGY.md`.
- **MP3**: Android has no platform MP3 encoder. Options are LAME (LGPL) or an external
  ffmpeg/libmp3lame transcode. Not implemented in the POC.

A successful capture is only PCM in a valid WAV. Encoding or renaming a file is never
evidence of capture. All three formats must preserve duration/sample rate/channel count
and pass independent decode/probe checks in production.

## Contract summary (what the implementation relies on)

1. Fresh `createScreenCaptureIntent()` consent per session; never cache the result.
2. Foreground service with type `mediaProjection` started and promoted with
   `startForeground` **before** `getMediaProjection`.
3. `AudioPlaybackCaptureConfiguration.Builder(projection)` + inclusive usages
   MEDIA/GAME/UNKNOWN; `AudioRecord.Builder.setAudioPlaybackCaptureConfig`.
4. Register `MediaProjection.Callback`; on `onStop`, stop reading and finalize as
   `REVOKED`.
5. Stream PCM16 to WAV; patch sizes; compute SHA-256; export WAV + log + JSON metadata.
6. Never let a later success overwrite an error/revocation; never report a file without a
   finalized header and independently verified audio.

## Failure modes that must not be mistaken for success

- Source app opted out (`ALLOW_CAPTURE_BY_NONE`), targets SDK <= 28 without opt-in, uses
  a non-eligible usage, or is in another profile: capture may deliver silence or no data.
- DRM/secure audio: may be silent; no bypass is attempted.
- Screen lock revokes projection: `REVOKED`, even if a partial WAV exists.
- Vendor kills the process: unfinalized WAV -> `INTERRUPTED` after repair.
- A 44-byte WAV or a WAV of zeros is **not** a successful capture; the POC distinguishes
  `COMPLETED_SILENT` and `FAILED`.

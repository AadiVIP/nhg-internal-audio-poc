# NHG Internal Audio

No-root internal playback audio capture for Android, built for the
vivo V25 5G (`V2202`), Android 15 / API 35, Funtouch OS 15. Research
prototype, not a production app.

Brand: No Headphone Gamerz (NHG).

## Current release: v0.3.3

| Item | Value |
|---|---|
| APK | `NHG-InternalAudio-v0.3.3.apk` |
| Size | 2,643,861 bytes |
| SHA-256 | `0a8a925fade5ff3634ef0c3f0c9c49cd51541769b6b3ab9f8cff8413f3b9c44b` |
| Package | `ai.nhg.capturepoc` |
| Version | 0.3.3 (versionCode 6) |
| SDK | minSdk 29, targetSdk 35 |
| ABI | `arm64-v8a` only |
| Signature | APK Signature Scheme v3, POC key |

Download the APK and its checksum from the
[v0.3.3 release](https://github.com/AadiVIP/nhg-internal-audio-poc/releases/tag/v0.3.3).

## What the app does

- Requests a standard Android screen-capture (MediaProjection) consent per session.
- Runs a foreground service of type `mediaProjection`.
- Builds `AudioPlaybackCaptureConfiguration` with usages `MEDIA`, `GAME`, `UNKNOWN`.
- Creates the only `AudioRecord` through
  `AudioRecord.Builder().setAudioPlaybackCaptureConfig(...)` — no microphone source,
  no `MediaRecorder`, no `RECORD_AUDIO` permission.
- Captures PCM16 48 kHz stereo in 960-frame blocking reads and writes a WAV master.
- After Stop/finalization, optionally transcodes the finalized WAV master to:
  - M4A/AAC-LC at 192 kbps via MediaCodec/MediaMuxer, or
  - MP3 at 192 kbps CBR via the bundled LAME 4.0 native library.
- Exports audio, log, and JSON metadata to `Download/NHG-InternalAudioPOC/` through
  MediaStore (`IS_PENDING`).
- On unclean process death, startup recovery repairs the WAV header, marks the session
  `INTERRUPTED`, and removes incomplete compressed output. Nothing incomplete is
  reported as completed.
- If an encoder fails or is unavailable, the session is marked `FAILED`, the partial
  file is deleted, and the master WAV is retained and exported.

## Features

- **WAV, M4A, and MP3 output.** WAV is the lossless 48 kHz stereo PCM master. M4A and
  MP3 are produced from the finalized WAV after Stop.
- **Pause and Resume.** PCM received during a pause is discarded; no synthetic silence
  is inserted. Resume continues the same session and file.
- **Persistent notification controls.** Pause, Resume, and Stop from the foreground
  notification.
- **Optional floating controls.** Pause/Stop controls over other apps after granting
  the Android "Display over other apps" permission.
- **Saved Audio library.** Lists exports, shows duration/size, and supports playback,
  rename, share, delete, and multi-select actions.
- **Duplicate-recording fix (v0.3.3).** A session is shown once even when the API has
  the internal file and the exported copy, and the private master WAV is no longer
  exposed as a duplicate library entry.

## Permissions and security boundary

The APK declares exactly:

- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION`
- `android.permission.POST_NOTIFICATIONS`
- `android.permission.WAKE_LOCK`
- `android.permission.SYSTEM_ALERT_WINDOW`

There is **no** `RECORD_AUDIO`, **no** `INTERNET`, and **no** storage permission.
The app does not record the microphone and does not send data over the network.

## Android playback-capture limits

Playback capture is controlled by Android and by the source app. Audio from apps that
opt out, audio marked as protected/DRM, and some system or privileged routes cannot be
captured. The user must approve a fresh MediaProjection consent for each session.
Availability and exact behavior vary by OEM build. This project does not bypass those
policies.

## Compatibility

- Android 10 (API 29) or newer.
- `arm64-v8a` devices only; the APK contains arm64 native libraries and will not
  install on x86 emulators.
- Device-tested by the project author on a vivo V25 5G (`V2202`), Android 15 /
  API 35, Funtouch OS 15.
- The v0.3.3 device verification is user-confirmed, not an automated test result:
  recording, WAV/M4A/MP3 output, pause/resume, notification controls, floating
  controls, Saved Audio, and the duplicate-recording fix were reported working.
- Not tested on other devices. Not available on Google Play.

## Install

1. Download `NHG-InternalAudio-v0.3.3.apk` and the checksum file from the release page.
2. Verify the download:

   ```bash
   sha256sum -c NHG-InternalAudio-v0.3.3.apk.sha256
   ```

3. Open the APK in the system package installer. Android will ask you to allow installs
   from the file manager/browser you use; this is expected for a sideloaded APK.
4. Grant notification permission and, optionally, overlay permission for floating
   controls. MediaProjection consent is requested for each recording.

The POC signing key is not distributed. Android may warn that the app is from an
unknown developer; that is expected for this research build.

## Build from source

Prerequisites used for the published build:

- Termux on arm64 Android with `clang`, `make`, `ar`, `ranlib`, `readelf`, `nm`, `tar`,
  `patchelf`, `llvm-strip`, JDK 21, `kotlinc`, Android SDK build-tools 36.0.0,
  `android-34` platform jar, `ffmpeg`/`ffprobe`, Python 3.
- The build uses the `android-34` resources jar while declaring `targetSdk 35`
  because the Termux `aapt2` available here cannot load the locally installed
  android-35/36 jars. The app uses no API-35-only compile-time symbols.

```bash
# Signing material is supplied through the environment and is never committed.
export NHG_KEYSTORE=/path/to/your-release.jks
export NHG_KEY_ALIAS=your-alias
export NHG_KS_PASS='your-keystore-password'
export NHG_KEY_PASS='your-key-password'

scripts/build.sh
```

`scripts/build.sh`:

1. verifies and builds LAME 4.0 from `third_party/lame/lame-4.0.tar.gz` using
   `scripts/build_native_mp3.sh` (static LAME, encoder only, no decoder/frontend,
   16 KB ELF alignment, symbol/NEEDED/RPATH checks);
2. compiles/link resources, Kotlin sources, and DEX;
3. packages the arm64 native libraries and license assets;
4. zipaligns and signs with the keystore supplied in the environment;
5. audits the manifest (fails on `RECORD_AUDIO` or `INTERNET`), native-library
   hashes, `extractNativeLibs`, and zip alignment.

Output: `dist/NHG-InternalAudio-v0.3.3.apk`.

Run the independent duplicate-recording regression suite:

```bash
scripts/run_repo_test.sh
```

Run APK signature/permission/native-library checks:

```bash
scripts/verify_app.sh dist/NHG-InternalAudio-v0.3.3.apk
```

## Repository layout

```
app/src/main/                 Android manifest, res, assets, Kotlin and JNI source
app/src/main/cpp/             JNI shim over libmp3lame
app/src/main/assets/licenses/ LGPL text, LAME license/README, third-party notices
third_party/lame/             vendored LAME 4.0 source tarball + license
scripts/                      build, native build, verification, device test helpers
tests/mp3/                    JVM harness for Mp3Encoder
tests/repository/             Saved Audio regression tests
research/                     design, environment, API and format research notes
tools/                        WAV analysis and documentation helpers
```

The public repository intentionally excludes signing keys, local build outputs,
private evidence bundles, user recordings, and test media.

## Verification status

Independent release checks performed for v0.3.3:

- APK SHA-256 matched the expected release hash.
- APK Signature Scheme v3 verification succeeded (`ai.nhg.capturepoc`, versionCode 6,
  versionName 0.3.3).
- Permission audit showed exactly the five permissions above; no `RECORD_AUDIO`, no
  `INTERNET`.
- Native libraries are packaged, SHA-256 matched the build outputs, expose the LAME
  API, and have 16 KB ELF LOAD alignment.
- LAME LGPL and third-party notice assets are present in the APK.
- The Saved Audio regression suite passed all five tests, including MP3/M4A/WAV
  deduplication and interrupted-session fallback.

The project author's on-device results are reported above as user-confirmed. The
release automation did not independently reproduce the on-device capture tests.

## Licensing

### LAME 4.0

- License: **LGPL-2.0-only**.
- Source: [`third_party/lame/lame-4.0.tar.gz`](third_party/lame/lame-4.0.tar.gz)
- Source SHA-256: `3df5124d5ad3a98312ffd7ba6a9b36230e4f8a3e66d3ce0f425e336c32d216eb`
- License text: [`third_party/lame/COPYING`](third_party/lame/COPYING)
- LAME's redistribution notes: [`third_party/lame/LICENSE`](third_party/lame/LICENSE)
- Build instructions: [`scripts/build_native_mp3.sh`](scripts/build_native_mp3.sh)
- Attribution, dynamic-linking, and relinking information:
  [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)

The app loads `libmp3lame.so` as a separate shared library through
`System.loadLibrary`. The unmodified LAME source and build script are included so the
library can be rebuilt or relinked.

### Original application code

No separate license grant is declared for the original NHG Internal Audio source in
this repository. Third-party components retain their own licenses; do not assume the
original application code may be reused beyond applicable copyright law.

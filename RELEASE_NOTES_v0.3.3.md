# NHG Internal Audio v0.3.3

No-root internal playback audio capture for Android. This is a research prototype,
not a production app.

## Highlights

- Internal Android playback capture through MediaProjection and
  `AudioPlaybackCaptureConfiguration`; the microphone is not used.
- WAV (48 kHz stereo PCM16), M4A/AAC-LC (192 kbps), and MP3 (192 kbps CBR).
- Pause/Resume with paused PCM discarded (no inserted silence), and Stop with final
  WAV/compressed output handling.
- Persistent notification controls and optional floating controls.
- Saved Audio library with playback, rename, share, delete, and multi-select actions.
- **Duplicate-recording fix:** each session appears once in Saved Audio; internal and
  exported copies are deduplicated, and the private master WAV is not exposed as a
  second library entry.

## Compatibility

- Requires Android 10 (API 29) or newer.
- `arm64-v8a` only.
- Target SDK 35 (Android 15).
- Device-tested by the project author on a vivo V25 5G (`V2202`), Android 15 /
  API 35, Funtouch OS 15.

The project author reports that recording, WAV/M4A/MP3 output, pause/resume,
notification controls, floating controls, Saved Audio, and the duplicate-recording fix
work on that device. Those are user-confirmed results; the release automation did not
independently reproduce them on hardware.

Independent checks run for this release:

- APK SHA-256 matched `0a8a925fade5ff3634ef0c3f0c9c49cd51541769b6b3ab9f8cff8413f3b9c44b`.
- APK Signature Scheme v3 verification succeeded.
- Package `ai.nhg.capturepoc`, versionName `0.3.3`, versionCode `6`.
- Permission audit: five declared permissions; no `RECORD_AUDIO`, no `INTERNET`.
- Packaged MP3 native libraries matched the build outputs and are 16 KB aligned.
- LAME LGPL and third-party notice assets are present.
- The Saved Audio regression suite passed all five tests.

## Install

1. Download `NHG-InternalAudio-v0.3.3.apk` and
   `NHG-InternalAudio-v0.3.3.apk.sha256`.
2. Verify: `sha256sum -c NHG-InternalAudio-v0.3.3.apk.sha256`.
3. Open the APK in the system installer and approve sideloading if prompted.
4. Grant notification permission; overlay permission is optional and only needed for
   floating controls.

There is no Play Store release.

## Playback-capture limits

Android and the source app control playback capture. Apps that opt out, protected/DRM
audio, and some system routes cannot be captured. A fresh MediaProjection consent is
required for each session. OEM behavior can vary. This project does not bypass these
restrictions.

## APK

- File: `NHG-InternalAudio-v0.3.3.apk`
- Size: 2,643,861 bytes
- SHA-256: `0a8a925fade5ff3634ef0c3f0c9c49cd51541769b6b3ab9f8cff8413f3b9c44b`
- Signature: APK Signature Scheme v3, POC key (not a Play Store signing identity)
- ABI: `arm64-v8a`

## Source and licensing

- Repository: https://github.com/AadiVIP/nhg-internal-audio-poc
- Release tag: https://github.com/AadiVIP/nhg-internal-audio-poc/tree/v0.3.3
- LAME 4.0 source:
  https://github.com/AadiVIP/nhg-internal-audio-poc/tree/v0.3.3/third_party/lame
  (`lame-4.0.tar.gz`, SHA-256
  `3df5124d5ad3a98312ffd7ba6a9b36230e4f8a3e66d3ce0f425e336c32d216eb`)
- LGPL-2.0 text:
  https://github.com/AadiVIP/nhg-internal-audio-poc/blob/v0.3.3/third_party/lame/COPYING
- Third-party notices and relinking information:
  https://github.com/AadiVIP/nhg-internal-audio-poc/blob/v0.3.3/THIRD_PARTY_NOTICES.md
- Native build instructions:
  https://github.com/AadiVIP/nhg-internal-audio-poc/blob/v0.3.3/scripts/build_native_mp3.sh

LAME 4.0 is LGPL-2.0-only. The app links it as a separate shared library
(`libmp3lame.so`), and the unmodified source plus build script are published so the
library can be rebuilt or relinked. No separate license grant is declared for the
original NHG application code.

# Sources and retrieval record

Retrieved 2026-10-28 on the target device (vivo V25 5G, Android 15/API 35).
WebFetch failed for developer.android.com from this environment, so each page was
downloaded with `curl` (mobile user agent), stored as HTML, and converted to text with
`tools/extract_doc.py` (BeautifulSoup). Both forms are kept so quotes can be re-checked.

## Official Android documentation (saved locally)

| Key | URL | Local files |
|---|---|---|
| av-capture | https://developer.android.com/media/platform/av-capture?hl=en | `sources/av-capture.html`, `.txt` |
| apcc | https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration?hl=en | `sources/apcc.html`, `.txt` |
| mpm | https://developer.android.com/reference/android/media/projection/MediaProjectionManager?hl=en | `sources/mpm.html`, `.txt` |
| mp | https://developer.android.com/reference/android/media/projection/MediaProjection?hl=en | `sources/mp.html`, `.txt` |
| mp-callback | https://developer.android.com/reference/android/media/projection/MediaProjection.Callback?hl=en | `sources/mp-callback.html`, `.txt` |
| audi-record | https://developer.android.com/reference/android/media/AudioRecord?hl=en | `sources/audi-record.html`, `.txt` |
| audi-record-builder | https://developer.android.com/reference/android/media/AudioRecord.Builder?hl=en | `sources/audi-record-builder.html`, `.txt` |
| audio-attributes | https://developer.android.com/reference/android/media/AudioAttributes?hl=en | `sources/audio-attributes.html`, `.txt` |
| audio-format | https://developer.android.com/reference/android/media/AudioFormat?hl=en | `sources/audio-format.html`, `.txt` |
| fgs-types | https://developer.android.com/develop/background-work/services/fgs/service-types?hl=en | `sources/fgs-types.html`, `.txt` |
| fgs-changes14 | https://developer.android.com/about/versions/14/changes/fgs-types-required?hl=en | `sources/fgs-changes14.html`, `.txt` |
| bc14 | https://developer.android.com/about/versions/14/behavior-changes-14?hl=en | `sources/bc14.html`, `.txt` |
| bc15 | https://developer.android.com/about/versions/15/behavior-changes-15?hl=en | `sources/bc15.html`, `.txt` |
| bc15-all | https://developer.android.com/about/versions/15/behavior-changes-all?hl=en | `sources/bc15-all.html`, `.txt` |
| media-projection14 | https://developer.android.com/about/versions/14/behavior-changes-all#media-projection?hl=en | `sources/media-projection14.html`, `.txt` |

## AOSP source (Android 15)

Branch confirmed by SHA-256 comparison against the live branch on 2026-10-28:
`refs/heads/android15-release`. Local copies are in `sources/aosp/`.

| Local file | Canonical URL |
|---|---|
| AudioPlaybackCaptureConfiguration.java | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/media/java/android/media/AudioPlaybackCaptureConfiguration.java |
| AudioRecord.java | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/media/java/android/media/AudioRecord.java |
| AudioAttributes.java | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/media/java/android/media/AudioAttributes.java |
| AudioPolicy.java | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/media/java/android/media/audiopolicy/AudioPolicy.java |
| MediaProjection.java | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/media/java/android/media/projection/MediaProjection.java |
| MediaProjectionManagerService.java | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/media/projection/MediaProjectionManagerService.java |
| AudioService.java | https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/audio/AudioService.java |
| AudioRecord.cpp | https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android15-release/media/libaudioclient/AudioRecord.cpp |
| AudioPolicyInterfaceImpl.cpp | https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android15-release/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp |
| AudioPolicyService.cpp | https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android15-release/services/audiopolicy/service/AudioPolicyService.cpp |
| AudioPolicyManager.cpp | https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android15-release/services/audiopolicy/managerdefault/AudioPolicyManager.cpp |

`AudioPlaybackCaptureTest.java` is a 2-byte failed download (an error page); it is not
used as evidence and is deliberately left visible rather than silently deleted.

## Device facts used in the research

Recorded in `ENVIRONMENT.md`, obtained from Android APIs, `getprop`, `pm`, and
world-readable APKs on the target device. No ADB, root, or privileged shell was available.

## Interpretation rule

Documentation and AOSP source disagree on `RECORD_AUDIO` (see
`ANDROID_CAPTURE_RESEARCH.md`, question 2). Neither is treated as proof for the target
device. The empirical test in `EXPERIMENT_PROTOCOL.md` decides, and only an
independently verified WAV counts as capture evidence.

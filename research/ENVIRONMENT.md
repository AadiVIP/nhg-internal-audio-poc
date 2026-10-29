# Environment Report — NHG Internal Audio POC

Collected: 2026-10-28 (device local time), by Qoder running inside Termux on the target phone.
No host computer, no root, no ADB server connection was assumed or used.

## 1. Target device

| Fact | Value | Source |
|---|---|---|
| Marketing model | vivo V25 5G | `ro.product.model` = `V2202`; task brief |
| Model / device | `V2202` | `getprop ro.product.model`, `ro.product.device` |
| Manufacturer | vivo | `ro.product.manufacturer` |
| Android release | 15 | `ro.build.version.release` |
| API level | 35 | `ro.build.version.sdk` |
| Build ID | `PD2215F_EX_A_15.3.16.0.W30` | `ro.build.display.id` |
| Fingerprint | `vivo/V2202/V2202:15/AP3A.240905.015.A2_IN/compiler260716144253:user/release-keys` | `ro.build.fingerprint` |
| Security patch | 2025-05-01 | `ro.build.version.security_patch` |
| OS skin | Funtouch OS 15 (`ro.vivo.os.version`=6.0, `ro.vivo.os.build.display.id`=Funtouch OS 15) | `getprop` |
| SoC | MediaTek MT6877 | `ro.boot.hardware` |
| ABIs | arm64-v8a, armeabi-v7a, armeabi | `ro.product.cpu.abilist` |
| Density / locale | 440 dpi / en-US | `ro.sf.lcd_density`, `persist.sys.locale` |
| Build type | `user` (production, not userdebug) | fingerprint |

Confirmation that this is a physical phone, not an emulator: vivo production fingerprint,
MediaTek hardware, Funtouch OS properties, and installed vivo system packages.

## 2. Execution identity and privilege

- Termux UID: `u0_a365` (uid=10365), SELinux context `u:r:untrusted_app_27:s0`.
- `su` is the Termux stub only ("No su program found on this device"). **No root.**
- No privileged shell, no Shizuku/rish, no `dumpsys`, no `input`, no `screencap` access.
- `pm list packages` works from Termux (public package list). `settings get` is denied
  (`INTERACT_ACROSS_USERS`), which confirms normal app privileges, not shell privileges.
- `logcat -d` returns a restricted buffer (833 lines, no `ActivityManager` entries and no
  other apps' PIDs), i.e. only app-visible logs. Full system logcat is **not** available.
- Only Termux's own processes are visible in `ps -A`.

## 3. Installation and device-control constraints

- `adb` binary exists (37.0.0p1-android-tools) but **no device is attached** and no TCP
  listener exists on 5555 (`service.adb.tcp.port=5555` is set but nothing listens;
  `adb connect 127.0.0.1:5555` → connection refused). `adb mdns` is not supported by this
  build. Wireless debugging is not enabled/paired.
- Consequently there is **no automated install, input injection, screenshot, or pull**.
- `termux-open` (Termux:API 0.53.0, `com.termux.api`, targetSdk 28) is available. It can
  hand an APK to the system package installer UI, which requires a manual "Install" tap.
- Direct `pm install` from Termux was attempted on 2026-10-28 and is **not usable**:
  - `pm install /sdcard/...apk` → system_server cannot read the path (SELinux +
    app-scoped storage), installation aborted.
  - Streaming `cat apk | pm install -S <size>` → rejected with `INTERACT_ACROSS_USERS`
    (caller user is -1); `--user 0` variant crashes the installer session with an NPE;
    `-i com.termux` streaming is refused ("Reverse mode only supported from shell or
    system").
  - Conclusion: installation requires the system package-installer UI and one manual
    "Install" tap. Reinstallation over an existing copy also requires a tap/confirmation.
- The POC must therefore log to a file in public storage so Termux can read results.
- `/sdcard` → `/storage/self/primary`; `/sdcard/Download` exists and is **writable and
  readable by Termux** (verified with a touch/remove test). App will export recordings to
  `Download/NHG-InternalAudioPOC/` via MediaStore, where Termux/ffprobe can read them.
- Android 14+ requires a fresh MediaProjection consent tap per capture session. This is a
  manual step for every experiment run.

## 4. Build toolchain actually available

| Tool | Path / version | Status |
|---|---|---|
| JDK | OpenJDK 21.0.13 (Termux) | works |
| javac | 21.0.13 | works |
| keytool | present | works |
| Kotlin compiler | kotlinc-jvm 2.4.21 (JRE 21) | works (jansi warning only) |
| Kotlin stdlib | `$PREFIX/opt/kotlin/lib/kotlin-stdlib.jar` | present |
| Gradle | 9.8.2 | present; AGP 8.7.3 cached (Gradle 9.x/AGP 8.7 compatibility is doubtful) |
| Android SDK root | `~/android-sdk` | present |
| Platforms | android-34, android-35, android-36 | present |
| Build-tools | 34.0.0, 36.0.0 | present |
| aapt2 | 2.19-compiler260716144253 (build-tools 36.0.0) | works |
| d8 | 9.2.4-dev (build-tools 36.0.0) | works |
| apksigner | 0.9 (build-tools 36.0.0) | works |
| zipalign | present | works |
| NDK | 27.0.12077973 | present, not needed for POC |
| cmake/ninja/clang | present | not needed |
| Network | HTTPS 200 from developer.android.com | available for docs/deps |
| Python | 3.14.8 | available for analysis |

**Chosen build path:** direct SDK build (`aapt2` → `kotlinc` → `d8` → `apksigner`), not
Gradle. It uses only tools verified above, needs no network at build time, and avoids
Gradle/AGP/JDK compatibility risk on Termux. A reproducible shell script will encode it.

Signing: release builds use a non-production key supplied through environment variables.
The keystore and passwords are never committed or distributed.

## 5. Media / analysis tools on device

- **ffmpeg 9.0.2** and **ffprobe 9.0.2** (Termux build), with `--enable-libmp3lame`,
  `libopus`, `libvorbis`, AAC (native), `libx264`, `libx265`, `libfdk_aac` **disabled**.
  This is the independent validation tool for WAV and future encoders.
- Python 3.14.8 with stdlib (`wave`, `audioop` may be removed in 3.13+; will verify) for
  independent PCM statistics. If `audioop` is missing, use `array`/`struct` or ffmpeg.
- No SoX, no LAME CLI, no `mediainfo`. MP3 CLI encoding can use ffmpeg's libmp3lame.
- `termux-audio-info` exists but is not a capture tool.

## 6. Candidate playback-source apps installed

Read via `aapt2 dump badging` on each installed base.apk (world-readable), 2026-10-28:

| Package | Version | minSdk | targetSdk | Notes |
|---|---|---|---|---|
| `org.videolan.vlc` | 3.7.2 (13070209) | 32 | 36 | third-party player, candidate positive source |
| `com.google.android.youtube` | 21.42.243 | 32 | 37 | Morphe-patched; policy likely restrictive |
| `app.morphe.android.apps.youtube.music` | — | — | — | patched YT Music |
| `com.google.android.apps.youtube.music` | — | — | — | YT Music |
| `in.krosbits.musicolet` | 6.14.1 (54000) | 32 | 37 | local music player |
| `app.symfonik.music.player` | 15.1.0 | 32 | 36 | local music player |
| `com.termux.api` | 0.53.0 | 24 | **28** | legacy targetSdk: **not capturable by apps** under default policy |

Important: VLC/YouTube/Musicolet may opt out of capture via
`AudioAttributes.setAllowedCapturePolicy(...)`. This cannot be read from the APK statically;
it must be tested. A controlled companion source app (targetSdk 35, USAGE_MEDIA,
ALLOW_CAPTURE_BY_ALL) will be built to provide a known digital fixture independent of
third-party behavior.

## 7. Known limitations recorded before experiments

1. No automated UI control or screenshots; all projection consents and button presses are
   manual. Evidence will be timestamped and stored by the app and by Termux.
2. No full logcat; diagnostics rely on the app's own log file plus `dumpsys`-free APIs
   (`PowerManager.isInteractive`, `isIgnoringBatteryOptimizations`, `AudioRecord` state).
3. Screen-off behavior cannot be forced programmatically; the user turns the screen off and
   the app logs `isInteractive()` transitions to prove the interval.
4. `pm install` from Termux is blocked by SELinux/user restrictions (see §3). The APK is
   staged at `Download/NHG-InternalAudioPOC-v0.1.0-poc.apk` and must be installed through
   the system installer UI with one manual tap. This is a human-in-the-loop step for every
   install; it does not affect capture once installed.
5. The local `aapt2` (build-tools 36.0.0) cannot load `android-35/android.jar` or
   `android-36/android.jar` ("failed to load include path"), reproducible even with a
   minimal manifest. The build therefore compiles and links against
   `android-34/android.jar` while the manifest declares `targetSdk 35` (the device runs
   API 35). This is a build-tool workaround, not a device limitation; all APIs used are
   guarded by `Build.VERSION.SDK_INT` where they differ between 34 and 35.

## 8. Reproducibility commands (environment)

```bash
getprop ro.product.model ro.product.manufacturer ro.build.version.release \
        ro.build.version.sdk ro.build.display.id ro.build.fingerprint \
        ro.product.cpu.abilist ro.build.version.security_patch
uname -a
id
java -version; javac -version; gradle --version | head -5
kotlinc -version
aapt2 version; d8 --version; apksigner --version
ffmpeg -version | head -2; ffprobe -version | head -1
adb devices -l; getprop service.adb.tcp.port
ls -ld /sdcard/Download; touch /sdcard/Download/.nhg_write_test && rm /sdcard/Download/.nhg_write_test
```

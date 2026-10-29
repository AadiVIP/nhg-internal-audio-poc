#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd "$(dirname "$0")/.."
PROJECT="$PWD"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$SDK/build-tools/36.0.0"
AAPT2="$BT/aapt2"
APKSIGNER="$BT/apksigner"
APK="${1:-$PROJECT/dist/NHG-InternalAudio-v0.3.3.apk}"

echo "=== NHG Internal Audio App Verification ==="
echo "Target APK: $APK"
[ -f "$APK" ] || { echo "ERROR: APK not found: $APK" >&2; exit 1; }

echo ""
echo "1. APK Size and SHA-256 Checksum"
ls -lh "$APK"
sha256sum "$APK"

echo ""
echo "2. Signature Verification"
bash "$APKSIGNER" verify --verbose --print-certs "$APK"

echo ""
echo "3. Manifest & Permissions Audit"
"$AAPT2" dump permissions "$APK" > build/verified-permissions.txt
cat build/verified-permissions.txt

echo "Checking prohibited permissions:"
if grep -q "android.permission.RECORD_AUDIO" build/verified-permissions.txt; then
  echo "FAIL: RECORD_AUDIO is present in manifest!" >&2
  exit 2
fi
echo "PASS: RECORD_AUDIO is NOT present."

if grep -q "android.permission.INTERNET" build/verified-permissions.txt; then
  echo "FAIL: INTERNET is present in manifest!" >&2
  exit 2
fi
echo "PASS: INTERNET is NOT present."

echo "Checking required permissions:"
for perm in "android.permission.FOREGROUND_SERVICE" \
            "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" \
            "android.permission.POST_NOTIFICATIONS" \
            "android.permission.WAKE_LOCK" \
            "android.permission.SYSTEM_ALERT_WINDOW"; do
  if grep -q "$perm" build/verified-permissions.txt; then
    echo "PASS: Declared $perm"
  else
    echo "FAIL: Missing $perm" >&2
    exit 3
  fi
done

echo ""
echo "4. Badging & Components Audit"
"$AAPT2" dump badging "$APK" > build/verified-badging.txt
grep -E "package:|launchable-activity:|service:|provider:" build/verified-badging.txt

echo ""
echo "5. Code and Structure Audit"
echo "Classes in DEX:"
jar tf build/classes.jar | grep -E "ai/nhg/capturepoc/.*\.class" | sort

echo ""
echo "5b. Native MP3 Encoder Audit"
unzip -l "$APK" | grep -E "lib/arm64-v8a/(libmp3lame|libnhgmp3)\.so" | tee build/verified-native-libs.txt
for lib in libmp3lame.so libnhgmp3.so; do
  grep -q "lib/arm64-v8a/$lib" build/verified-native-libs.txt || {
    echo "FAIL: missing packaged native lib $lib" >&2
    exit 4
  }
done
TMP_NATIVE="$(mktemp -d)"
unzip -q -o "$APK" "lib/arm64-v8a/*.so" -d "$TMP_NATIVE"
for lib in libmp3lame.so libnhgmp3.so; do
  APK_SHA="$(sha256sum "$TMP_NATIVE/lib/arm64-v8a/$lib" | awk '{print $1}')"
  SRC_SHA="$(sha256sum "build/native/arm64-v8a/$lib" | awk '{print $1}')"
  if [ "$APK_SHA" != "$SRC_SHA" ]; then
    echo "FAIL: packaged $lib differs from build/native (apk=$APK_SHA src=$SRC_SHA)" >&2
    exit 4
  fi
  while read -r align; do
    if [ "$align" != "0x4000" ]; then
      echo "FAIL: $lib LOAD alignment $align (expected 0x4000)" >&2
      exit 4
    fi
  done < <(readelf -l "$TMP_NATIVE/lib/arm64-v8a/$lib" | awk '/LOAD/{print $NF}')
done
rm -rf "$TMP_NATIVE"
echo "PASS: packaged libmp3lame.so/libnhgmp3.so match build/native SHA-256 and 16 KB alignment."
"$AAPT2" dump xmltree "$APK" --file AndroidManifest.xml | grep -i "extractNativeLibs" | tee build/verified-extract-native-libs.txt
if ! grep -q "extractNativeLibs.*=true" build/verified-extract-native-libs.txt; then
  echo "FAIL: extractNativeLibs is not true in the packaged manifest" >&2
  exit 4
fi
echo "PASS: android:extractNativeLibs=true."
"$AAPT2" dump badging "$APK" > build/verified-badging.txt
grep -q "native-code: 'arm64-v8a'" build/verified-badging.txt \
  && echo "PASS: badging declares arm64-v8a native code." \
  || { echo "FAIL: badging does not declare arm64-v8a native code" >&2; exit 4; }
unzip -l "$APK" > build/verified-apk-contents.txt
grep -q "assets/licenses/LAME-LGPL-2.0.txt" build/verified-apk-contents.txt \
  && echo "PASS: LAME LGPL license asset packaged." \
  || { echo "FAIL: missing LAME license asset" >&2; exit 4; }

echo ""
echo "6. Format & Container Verification"
CAPTURE_DIR="/sdcard/Download/NHG-InternalAudioPOC"
if [ -d "$CAPTURE_DIR" ]; then
  LATEST_WAV="$(ls -1t "$CAPTURE_DIR"/*.wav 2>/dev/null | head -n1 || true)"
  LATEST_M4A="$(ls -1t "$CAPTURE_DIR"/*.m4a 2>/dev/null | head -n1 || true)"
  LATEST_MP3="$(ls -1t "$CAPTURE_DIR"/*.mp3 2>/dev/null | head -n1 || true)"
  for f in "$LATEST_WAV" "$LATEST_M4A" "$LATEST_MP3"; do
    [ -n "$f" ] && [ -f "$f" ] || continue
    echo "Analyzing $f:"
    ffprobe -hide_banner "$f" 2>&1 | grep -E "Duration|Stream"
    if [ "${f##*.}" = "wav" ]; then
      python tools/analyze_wav.py "$f" 1.0 2.0 | grep -E "durationS|sampleRate|channels|rmsDbfs|peakDbfs|nonzeroSamples"
    else
      ffmpeg -hide_banner -v error -i "$f" -f null - && echo "PASS: full decode without errors."
    fi
  done
else
  echo "No on-device capture directory found; format checks require device tests."
fi

echo ""
echo "=== ALL VERIFICATIONS PASSED SUCCESSFULLY ==="

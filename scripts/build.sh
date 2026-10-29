#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd "$(dirname "$0")/.."
PROJECT="$PWD"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$SDK/build-tools/36.0.0"
# aapt2 36.0.0 here cannot load the locally installed android-35/36 jars
# (verified with a minimal manifest; android-34.jar loads). Compile/link
# against API 34 resources but declare targetSdk 35 in the APK.
AJAR="$SDK/platforms/android-34/android.jar"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
STDLIB="$PREFIX/opt/kotlin/lib/kotlin-stdlib.jar"
AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
# Signing material is never committed. Provide it through the environment:
#   NHG_KEYSTORE  path to your keystore
#   NHG_KEY_ALIAS key alias
#   NHG_KS_PASS   keystore password
#   NHG_KEY_PASS  key password
: "${NHG_KEYSTORE:?ERROR: set NHG_KEYSTORE to your signing keystore path}"
: "${NHG_KEY_ALIAS:?ERROR: set NHG_KEY_ALIAS to your key alias}"
: "${NHG_KS_PASS:?ERROR: set NHG_KS_PASS to your keystore password}"
: "${NHG_KEY_PASS:?ERROR: set NHG_KEY_PASS to your key password}"
KEYSTORE="$NHG_KEYSTORE"
KS_ALIAS="$NHG_KEY_ALIAS"
VERSION_NAME="0.3.3"
VERSION_CODE="6"
MIN_SDK="29"
TARGET_SDK="35"
APK_NAME="NHG-InternalAudio-v${VERSION_NAME}.apk"

for tool in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$AJAR" "$STDLIB"; do
  [ -e "$tool" ] || { echo "ERROR: missing tool/artifact: $tool" >&2; exit 1; }
done

mkdir -p build/gen build/classes build/dex dist
LOG="build/build-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

echo "=== NHG internal audio POC build $(date -Iseconds) ==="
echo "project=$PROJECT"
echo "sdk=$SDK aapt2=$AAPT2 d8=$D8"
echo "kotlin=$(kotlinc -version 2>&1 | tail -1)"
echo "java=$(java -version 2>&1 | head -1)"

rm -rf build/gen/* build/classes/* build/dex/* build/res.zip build/app-unsigned.apk build/app-aligned.apk
mkdir -p build/gen build/classes build/dex

echo "--- aapt2 compile"
"$AAPT2" compile --dir app/src/main/res -o build/res.zip

echo "--- native MP3 encoder (LAME 4.0 from vendored source)"
bash "$PROJECT/scripts/build_native_mp3.sh"

echo "--- aapt2 link"
"$AAPT2" link \
  -o build/app-unsigned.apk \
  -I "$AJAR" \
  --manifest app/src/main/AndroidManifest.xml \
  -R build/res.zip \
  -A app/src/main/assets \
  --java build/gen \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay

echo "--- javac R.java"
javac -source 17 -target 17 -encoding UTF-8 -classpath "$AJAR" -d build/classes build/gen/ai/nhg/capturepoc/R.java

echo "--- kotlinc"
kotlinc app/src/main/java \
  -classpath "$AJAR:$STDLIB:build/classes" \
  -jvm-target 17 \
  -d build/classes \
  2>&1 | { grep -v -E "jansi|WARNING: A restricted method|WARNING: Restricted methods|sun.misc.Unsafe|--enable-native-access|^$" || true; }

echo "--- d8"
jar cf build/classes.jar -C build/classes .
bash "$D8" --release --min-api "$MIN_SDK" --lib "$AJAR" --output build/dex build/classes.jar "$STDLIB"

echo "--- package dex into APK"
( cd build/dex && zip -q -X -0 -u ../app-unsigned.apk classes.dex )

echo "--- package native libraries"
NATIVE_LIB_DIR="$PROJECT/build/native/arm64-v8a"
for lib in libmp3lame.so libnhgmp3.so; do
  [ -f "$NATIVE_LIB_DIR/$lib" ] || { echo "ERROR: missing native lib $NATIVE_LIB_DIR/$lib" >&2; exit 1; }
done
rm -rf build/apk-lib
mkdir -p build/apk-lib/lib/arm64-v8a
cp "$NATIVE_LIB_DIR/libmp3lame.so" "$NATIVE_LIB_DIR/libnhgmp3.so" build/apk-lib/lib/arm64-v8a/
( cd build/apk-lib && zip -q -X -9 -u ../app-unsigned.apk lib/arm64-v8a/libmp3lame.so lib/arm64-v8a/libnhgmp3.so )
(cd "$NATIVE_LIB_DIR" && sha256sum -c SHA256SUMS)

echo "--- zipalign"
"$ZIPALIGN" -f -p 4 build/app-unsigned.apk build/app-aligned.apk

echo "--- apksigner sign"
[ -f "$KEYSTORE" ] || { echo "ERROR: keystore not found: $KEYSTORE" >&2; exit 1; }
bash "$APKSIGNER" sign \
  --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
  --ks-pass env:NHG_KS_PASS --key-pass env:NHG_KEY_PASS \
  --out "dist/$APK_NAME" \
  build/app-aligned.apk

echo "--- apksigner verify"
bash "$APKSIGNER" verify --verbose --print-certs "dist/$APK_NAME" | tee build/signature.txt

echo "--- manifest audit"
"$AAPT2" dump badging "dist/$APK_NAME" > build/badging.txt
"$AAPT2" dump permissions "dist/$APK_NAME" > build/merged-permissions.txt
cat build/merged-permissions.txt
if grep -q "android.permission.RECORD_AUDIO" build/merged-permissions.txt build/badging.txt; then
  echo "FATAL: RECORD_AUDIO present in merged manifest" >&2
  exit 2
fi
if grep -q "android.permission.INTERNET" build/merged-permissions.txt build/badging.txt; then
  echo "FATAL: INTERNET present in merged manifest" >&2
  exit 2
fi
echo "manifest audit OK: no RECORD_AUDIO, no INTERNET"

echo "--- native library audit"
unzip -l "dist/$APK_NAME" | grep -E "lib/arm64-v8a/(libmp3lame|libnhgmp3)\.so" | tee build/apk-native-libs.txt
for lib in libmp3lame.so libnhgmp3.so; do
  APK_SHA=$(unzip -p "dist/$APK_NAME" "lib/arm64-v8a/$lib" | sha256sum | awk '{print $1}')
  SRC_SHA=$(sha256sum "$NATIVE_LIB_DIR/$lib" | awk '{print $1}')
  [ "$APK_SHA" = "$SRC_SHA" ] || { echo "FATAL: packaged $lib differs from build output" >&2; exit 2; }
done
echo "native library audit OK: packaged libs match build/native SHA-256"
"$AAPT2" dump xmltree "dist/$APK_NAME" --file AndroidManifest.xml \
  | grep -i "extractNativeLibs" | tee build/apk-extract-native-libs.txt
grep -q "extractNativeLibs.*=true" build/apk-extract-native-libs.txt \
  || { echo "FATAL: extractNativeLibs is not true" >&2; exit 2; }
"$ZIPALIGN" -c -p -v 4 "dist/$APK_NAME" > build/zipalign-check.txt
echo "zipalign check OK"

echo "--- artifact"
ls -l "dist/$APK_NAME"
sha256sum "dist/$APK_NAME" | tee build/apk-sha256.txt
echo "BUILD_OK apk=$PROJECT/dist/$APK_NAME log=$PROJECT/$LOG"

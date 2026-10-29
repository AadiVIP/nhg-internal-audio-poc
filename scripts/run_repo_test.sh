#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$SDK/build-tools/36.0.0"
AJAR="$SDK/platforms/android-35/android.jar"
D8="$BT/d8"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
STDLIB="$PREFIX/opt/kotlin/lib/kotlin-stdlib.jar"

TEST_DIR="$PROJ/build/test_repo"
rm -rf "$TEST_DIR"
mkdir -p "$TEST_DIR/classes"
mkdir -p "$PREFIX/tmp/dalvik-cache"

echo "=== Compiling SavedAudioRepositoryTest ==="
kotlinc \
    -cp "$AJAR:$STDLIB" \
    -d "$TEST_DIR/classes" \
    "$PROJ/app/src/main/java/ai/nhg/capturepoc/AudioOutputFormat.kt" \
    "$PROJ/app/src/main/java/ai/nhg/capturepoc/Mp3Encoder.kt" \
    "$PROJ/app/src/main/java/ai/nhg/capturepoc/NhgFileProvider.kt" \
    "$PROJ/app/src/main/java/ai/nhg/capturepoc/SavedAudioRepository.kt" \
    "$PROJ/tests/repository/SavedAudioRepositoryTest.kt"

echo "=== Packaging classes.jar ==="
jar cf "$TEST_DIR/classes.jar" -C "$TEST_DIR/classes" .

echo "=== Running D8 to package DEX ==="
bash "$D8" --release --min-api 29 --lib "$AJAR" --output "$TEST_DIR" "$TEST_DIR/classes.jar" "$STDLIB"

# Android 14+ ART requires loaded DEX files to be read-only
chmod 444 "$TEST_DIR/classes.dex"

echo "=== Running SavedAudioRepositoryTest with dalvikvm on Android ART ==="
ANDROID_DATA="$PREFIX/tmp" dalvikvm -classpath "$TEST_DIR/classes.dex" SavedAudioRepositoryTestKt
echo "=== REGRESSION SUITE COMPLETED SUCCESSFULLY ==="

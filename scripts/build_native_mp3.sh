#!/data/data/com.termux/files/usr/bin/bash
# Build libmp3lame.so and libnhgmp3.so for arm64 Android from the vendored
# LAME 4.0 source tarball. Offline and reproducible; no prebuilt binaries.
set -euo pipefail

cd "$(dirname "$0")/.."
PROJECT="$PWD"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
OUT="$PROJECT/build/native/arm64-v8a"
WORK="$PROJECT/build/native/lame-4.0"
TARBALL="$PROJECT/third_party/lame/lame-4.0.tar.gz"
EXPECTED_SHA256="3df5124d5ad3a98312ffd7ba6a9b36230e4f8a3e66d3ce0f425e336c32d216eb"
JDK_INCLUDE="$PREFIX/lib/jvm/java-21-openjdk/include"
CLANG="$PREFIX/bin/clang"

for tool in "$CLANG" make ar ranlib readelf nm tar; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: missing tool: $tool" >&2; exit 1; }
done
[ -f "$TARBALL" ] || { echo "ERROR: missing vendored LAME tarball: $TARBALL" >&2; exit 1; }
[ -f "$JDK_INCLUDE/jni.h" ] || { echo "ERROR: missing JNI headers: $JDK_INCLUDE/jni.h" >&2; exit 1; }

ACTUAL_SHA256="$(sha256sum "$TARBALL" | awk '{print $1}')"
if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
  echo "ERROR: LAME tarball SHA-256 mismatch" >&2
  echo "expected=$EXPECTED_SHA256" >&2
  echo "actual  =$ACTUAL_SHA256" >&2
  exit 2
fi

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK" "$OUT"
tar xzf "$TARBALL" -C "$WORK" --strip-components=1

echo "--- configure LAME (static, no frontend, no decoder)"
(
  cd "$WORK"
  CC="clang -std=gnu23" CFLAGS="-O2 -fPIC -DNDEBUG" \
    ./configure --disable-frontend --disable-decoder --enable-static --disable-shared \
    > configure.log 2>&1
)

echo "--- make LAME"
make -C "$WORK" -j"$(nproc)" > "$WORK/make.log" 2>&1

echo "--- link libmp3lame.so"
"$CLANG" -shared -o "$OUT/libmp3lame.so" \
  -Wl,-soname,libmp3lame.so \
  -Wl,-z,max-page-size=16384 \
  -Wl,-z,common-page-size=16384 \
  -Wl,--whole-archive "$WORK/libmp3lame/.libs/libmp3lame.a" -Wl,--no-whole-archive \
  -lm
if command -v patchelf >/dev/null 2>&1; then
  patchelf --remove-rpath "$OUT/libmp3lame.so"
fi

echo "--- link libnhgmp3.so (JNI)"
"$CLANG" -shared -fPIC -O2 -std=c11 \
  -I"$JDK_INCLUDE" -I"$JDK_INCLUDE/linux" -I"$WORK/include" \
  -o "$OUT/libnhgmp3.so" \
  "$PROJECT/app/src/main/cpp/nhg_mp3_jni.c" \
  -L"$OUT" -lmp3lame \
  -Wl,-soname,libnhgmp3.so \
  -Wl,-z,max-page-size=16384 \
  -Wl,-z,common-page-size=16384
if command -v patchelf >/dev/null 2>&1; then
  patchelf --remove-rpath "$OUT/libnhgmp3.so"
fi

if command -v llvm-strip >/dev/null 2>&1; then
  llvm-strip --strip-unneeded "$OUT/libmp3lame.so" "$OUT/libnhgmp3.so"
fi
chmod 644 "$OUT/libmp3lame.so" "$OUT/libnhgmp3.so"

echo "--- verify libmp3lame.so"
SONAME="$(readelf -d "$OUT/libmp3lame.so" | awk '/SONAME/{print $NF}' | tr -d '[]')"
[ "$SONAME" = "libmp3lame.so" ] || { echo "ERROR: bad SONAME: $SONAME" >&2; exit 3; }
nm -D --defined-only "$OUT/libmp3lame.so" > "$OUT/.lame-symbols.txt"
readelf -d "$OUT/libmp3lame.so" > "$OUT/.lame-dynamic.txt"
for sym in lame_init lame_set_in_samplerate lame_set_num_channels lame_set_brate \
           lame_set_quality lame_init_params lame_encode_buffer_interleaved \
           lame_encode_flush lame_close; do
  grep -q " $sym\$" "$OUT/.lame-symbols.txt" || {
    echo "ERROR: missing LAME symbol: $sym" >&2; exit 3;
  }
done
for needed in $(awk '/NEEDED/{gsub(/[\[\]]/,"",$NF); print $NF}' "$OUT/.lame-dynamic.txt"); do
  case "$needed" in
    libm.so|libdl.so|libc.so) ;;
    *) echo "ERROR: unexpected NEEDED in libmp3lame.so: $needed" >&2; exit 3 ;;
  esac
done
if grep -qE "RPATH|RUNPATH" "$OUT/.lame-dynamic.txt"; then
  echo "ERROR: libmp3lame.so still has RPATH/RUNPATH" >&2; exit 3
fi

echo "--- verify libnhgmp3.so"
SONAME="$(readelf -d "$OUT/libnhgmp3.so" | awk '/SONAME/{print $NF}' | tr -d '[]')"
[ "$SONAME" = "libnhgmp3.so" ] || { echo "ERROR: bad SONAME: $SONAME" >&2; exit 3; }
nm -D --defined-only "$OUT/libnhgmp3.so" > "$OUT/.jni-symbols.txt"
readelf -d "$OUT/libnhgmp3.so" > "$OUT/.jni-dynamic.txt"
for sym in nativeCreate nativeEncode nativeFlush nativeClose nativeVersion; do
  grep -q "Java_ai_nhg_capturepoc_Mp3Encoder_$sym\$" "$OUT/.jni-symbols.txt" || {
    echo "ERROR: missing JNI symbol: $sym" >&2; exit 3;
  }
done
for needed in $(awk '/NEEDED/{gsub(/[\[\]]/,"",$NF); print $NF}' "$OUT/.jni-dynamic.txt"); do
  case "$needed" in
    libmp3lame.so|libdl.so|libc.so) ;;
    *) echo "ERROR: unexpected NEEDED in libnhgmp3.so: $needed" >&2; exit 3 ;;
  esac
done
if grep -qE "RPATH|RUNPATH" "$OUT/.jni-dynamic.txt"; then
  echo "ERROR: libnhgmp3.so still has RPATH/RUNPATH" >&2; exit 3
fi
rm -f "$OUT/.lame-symbols.txt" "$OUT/.lame-dynamic.txt" "$OUT/.jni-symbols.txt" "$OUT/.jni-dynamic.txt"

echo "--- verify 16 KB ELF alignment"
for lib in libmp3lame.so libnhgmp3.so; do
  while read -r align; do
    if [ "$align" != "0x4000" ]; then
      echo "ERROR: $lib LOAD alignment $align (expected 0x4000)" >&2
      exit 4
    fi
  done < <(readelf -l "$OUT/$lib" | awk '/LOAD/{print $NF}')
done

echo "--- artifact checksums"
sha256sum "$OUT"/*.so | tee "$OUT/SHA256SUMS"
ls -l "$OUT"/*.so
echo "NATIVE_BUILD_OK dir=$OUT"

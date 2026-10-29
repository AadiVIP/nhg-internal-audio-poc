# LAME 4.0 (vendored source)

This directory contains the unmodified official LAME 4.0 source tarball used to
build the MP3 encoder bundled in the NHG Internal Audio APK.

## Provenance

- Upstream: The LAME Project — https://lame.sourceforge.io/
- Release: LAME 4.0
- Download: https://sourceforge.net/projects/lame/files/lame/4.0/lame-4.0.tar.gz/download
- File: `lame-4.0.tar.gz` (1,496,810 bytes)
- SHA-256: `3df5124d5ad3a98312ffd7ba6a9b36230e4f8a3e66d3ce0f425e336c32d216eb`
- BLAKE2b-512: `9e05275c28bc6d086011362f211edc2e2cd831429ca4d2daa1bc0ae7084ebd9bec3c3db93e1cab638570a9f624468372b9ff39f3dc9e7d0b105dbba7f248b2df`
- The BLAKE2b-512 digest matches the Arch Linux `lame` 4.0 packaging PKGBUILD.

## License

LAME 4.0 is distributed under the **GNU Library General Public License,
version 2 (LGPL-2.0-only)**. See:

- `COPYING` — full LGPL 2.0 text
- `LICENSE` — LAME's notes on use in commercial programs

The bundled app links LAME as a separate shared library (`libmp3lame.so`) and
ships the unmodified source tarball plus `scripts/build_native_mp3.sh`, so the
library can be rebuilt or relinked. No LAME source files are modified.

## Build

`scripts/build_native_mp3.sh` extracts this tarball into
`build/native/lame-4.0/`, configures it with the encoder frontend and decoder
disabled, builds a static archive with PIC objects using Termux clang
(`aarch64-unknown-linux-android24`), then links `libmp3lame.so` with SONAME
`libmp3lame.so` and 16 KB ELF segment alignment. The JNI shim
`app/src/main/cpp/nhg_mp3_jni.c` links against it to produce
`libnhgmp3.so`.

Relevant upstream API: `lame_init`, `lame_set_in_samplerate`,
`lame_set_num_channels`, `lame_set_brate`, `lame_set_quality`,
`lame_init_params`, `lame_encode_buffer_interleaved`, `lame_encode_flush`,
`lame_close`.

## Note

LAME's own README notes that MP3 may be subject to software patents in some
jurisdictions. This project is a private research POC; redistributors should
make their own assessment.

# Third-party notices

## LAME 4.0 (`libmp3lame.so`)

- Component: LAME (LAME Ain't an MP3 Encoder)
- Version: 4.0
- Upstream: https://lame.sourceforge.io/ and
  https://sourceforge.net/projects/lame/files/lame/4.0/
- Source: vendored at [`third_party/lame/lame-4.0.tar.gz`](third_party/lame/lame-4.0.tar.gz)
- Tarball SHA-256: `3df5124d5ad3a98312ffd7ba6a9b36230e4f8a3e66d3ce0f425e336c32d216eb`
- License: GNU Lesser General Public License v2.0 (LGPL-2.0-only). See
  [`third_party/lame/COPYING`](third_party/lame/COPYING).
- Additional redistribution notes from the LAME distribution:
  [`third_party/lame/LICENSE`](third_party/lame/LICENSE).
- Modifications: none to the LAME source. The build uses upstream `./configure` with
  `--disable-frontend --disable-decoder --enable-static --disable-shared`, then links
  the static archive into `libmp3lame.so` with the Termux clang toolchain. No
  precompiled binaries are downloaded or executed.
- Build instructions: [`scripts/build_native_mp3.sh`](scripts/build_native_mp3.sh).
- Dynamic linking: the application loads `libmp3lame.so` at runtime through
  `System.loadLibrary`. The library is a separate, replaceable shared object in the
  APK; the unmodified corresponding source and build script are published in this
  repository so the library can be rebuilt or relinked.

## MP3 format note

The MP3 patents expired. LAME 4.0's bundled notice states that commercial use requires
a license under the LGPL terms or separate permission from the LAME project. That
notice is reproduced at [`third_party/lame/LICENSE`](third_party/lame/LICENSE).

# Third-party notices

## LAME 4.0 (libmp3lame.so)

- Component: LAME (LAME Ain't an MP3 Encoder)
- Version: 4.0
- Upstream: https://lame.sourceforge.io/ and https://sourceforge.net/projects/lame/files/lame/4.0/
- Source: vendored at `third_party/lame/lame-4.0.tar.gz`
- Tarball SHA-256: `3df5124d5ad3a98312ffd7ba6a9b36230e4f8a3e66d3ce0f425e336c32d216eb`
- License: GNU Lesser General Public License v2.0 (LGPL-2.0). See `LAME-LGPL-2.0.txt`.
- Additional notes from the LAME distribution: see `LAME-LICENSE.txt`.
- Modifications: none to the library source. The build uses upstream `./configure`
  with `--disable-frontend --disable-decoder --enable-static --disable-shared`,
  then links the static archive into `libmp3lame.so` with the Termux clang
  toolchain (no precompiled binaries are downloaded or executed).
- Build instructions: `scripts/build_native_mp3.sh`.
- Dynamic linking: the application loads `libmp3lame.so` at runtime through
  `System.loadLibrary`. The LGPL relinking requirement is satisfied because the
  shared library is a separate, replaceable file inside the APK and its complete
  corresponding source plus build script are included in the project.

## MP3 format note

The MP3 patents expired; LAME 4.0's bundled notice states that commercial use
requires a license under the LGPL terms or separate permission from the LAME
project. This notice is reproduced in `LAME-LICENSE.txt`.

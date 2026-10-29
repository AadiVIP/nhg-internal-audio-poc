# Changelog

## v0.3.3 — 2026-10-29

- Fixed duplicate Saved Audio entries by consolidating internal and exported copies of
  the same session.
- Stopped exposing the private master WAV as a separate Saved Audio entry.
- Added regression coverage for MP3, M4A, WAV, interrupted, and metadata-less sessions.
- Updated release documentation and public source packaging.

## v0.3.2 — 2026-10-29

- Fixed Pause/Stop button alignment.
- Added Saved Audio multi-select actions.
- Installed and verified the checkpoint build before the duplicate-recordings fix.

## v0.3.1 — 2026-10-29

- Fixed the collapsed format-selector label desynchronization.

## v0.3.0 — 2026-10-29

- Added offline MP3 encoding with the bundled LAME 4.0 library.
- Added M4A fallback MIME/state correctness and actual-output-format tracking.
- Added MP3 handling to Saved Audio, recovery, export, and the finished-recording UI.
- Retained the WAV master on encoder failure and marked failed sessions accurately.

## v0.2.0 — 2026-10-28

- Material 3 UI, M4A/AAC output, notification controls, floating bubble, and Saved
  Audio library.
- Preserved the no-microphone/no-network capture boundary.

## v0.1.0 — 2026-10-28

- Initial no-root internal-audio proof of concept with WAV capture, recovery, and
  independent validation tooling.

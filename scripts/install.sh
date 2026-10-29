#!/data/data/com.termux/files/usr/bin/bash
# Install the POC APK via the system package-installer UI.
# A manual "Install" tap on the phone is REQUIRED; there is no adb/root on this device.
set -u
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$PROJ/dist/NHG-InternalAudio-v0.3.3.apk}"
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 2; }
PKG="ai.nhg.capturepoc"
DEST="/sdcard/Download/$(basename "$APK")"
LOG="$PROJ/build/tmp/install-attempts.log"
mkdir -p "$PROJ/build/tmp"
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 2; }
sha256sum "$APK" | awk '{print "apk_sha256="$1}' | tee -a "$LOG"
cp -f "$APK" "$DEST" || exit 3
cmp -s "$APK" "$DEST" || { echo "Staged APK differs from source: $DEST" >&2; exit 3; }

# termux-open serves the APK through content://com.termux.files. That provider
# rejects openFile unless allow-external-apps=true, so the installer stages
# nothing and reports INSTALL_FAILED_INVALID_APK ("Invalid APK"). The system
# Files app holds MANAGE_DOCUMENTS and can hand the installer a readable URI.
ALLOW_EXTERNAL="$(awk -F= '/^[[:space:]]*allow-external-apps[[:space:]]*=/{v=$2; gsub(/[[:space:]]/,"",v); last=tolower(v)} END{print last}' "$HOME/.termux/termux.properties" 2>/dev/null)"
if [ "$ALLOW_EXTERNAL" = "true" ]; then
  date -Ins | awk '{print "install_path=termux-open "$0}' | tee -a "$LOG"
  termux-open --content-type application/vnd.android.package-archive "$DEST" || exit 4
  echo "System installer opened. Tap Install on the phone."
else
  date -Ins | awk '{print "install_path=documentsui "$0}' | tee -a "$LOG"
  am start --user 0 -a android.intent.action.VIEW_DOWNLOADS \
    -n com.google.android.documentsui/com.android.documentsui.ViewDownloadsActivity >/dev/null 2>&1
  am_rc=$?
  if [ "$am_rc" -ne 0 ]; then
    am start --user 0 -a android.intent.action.VIEW \
      -d "content://com.android.externalstorage.documents/document/primary%3ADownload" \
      -t "vnd.android.document/directory" \
      -n com.google.android.documentsui/com.android.documentsui.files.FilesActivity >/dev/null 2>&1
    am_rc=$?
  fi
  date -Ins | awk -v rc="$am_rc" '{print "files_open_rc="rc" "$0}' | tee -a "$LOG"
  echo "allow-external-apps is not true, so termux-open cannot hand the APK to the installer."
  echo "Files/Downloads was opened (rc=$am_rc). Tap $(basename "$APK"), then Install."
  echo "If Files did not open, open it manually and select Download/$(basename "$APK")."
  echo "Optional: uncomment allow-external-apps in $HOME/.termux/termux.properties to use termux-open."
fi
EXPECTED_SHA="$(sha256sum "$APK" | awk '{print $1}')"
for i in $(seq 1 300); do
  INSTALLED_PATH="$(cmd package path "$PKG" 2>/dev/null | head -n1 | cut -d: -f2)"
  if [ -n "$INSTALLED_PATH" ] && [ -f "$INSTALLED_PATH" ]; then
    CURR_SHA="$(sha256sum "$INSTALLED_PATH" 2>/dev/null | awk '{print $1}')"
    if [ "$CURR_SHA" = "$EXPECTED_SHA" ]; then
      date -Ins | awk '{print "installed_at="$0}' | tee -a "$LOG"
      echo "INSTALLED (verified sha256: $CURR_SHA)"
      exit 0
    fi
  fi
  sleep 2
done
date -Ins | awk '{print "install_not_detected="$0}' | tee -a "$LOG"
echo "NOT_INSTALLED_AFTER_600S"
exit 5

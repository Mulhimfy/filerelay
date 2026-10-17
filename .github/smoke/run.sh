#!/usr/bin/env bash
# Emulator smoke test: installs the APK, drives every screen, saves screenshots, fails on any crash.
set -u
PKG=com.mulhimfy.filerelay
OUT=smoke-out
FILES=smoke-files
mkdir -p "$OUT"
fail=0

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }

tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  python3 - "$OUT/ui.xml" "$1" <<'PY' > "$OUT/tap.txt"
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
hits = [n for n in root.iter("node") if n.get("text") == sys.argv[2] or n.get("content-desc") == sys.argv[2]]
if hits:
    # Last match is the lowest on screen (the bottom bar wins over same-named chips).
    a, b, c, d = map(int, re.findall(r"\d+", hits[-1].get("bounds")))
    print((a + c) // 2, (b + d) // 2)
PY
  if [ -s "$OUT/tap.txt" ]; then adb shell input tap $(cat "$OUT/tap.txt"); echo "tapped $1"; else echo "NOT FOUND: $1"; fail=1; fi
}

adb logcat -c
adb install -r FileRelay.apk || { echo "install failed"; exit 1; }
adb install -r telegram.apk && echo "telegram installed" || echo "telegram install skipped"
adb shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow

WA=/sdcard/Android/media/com.whatsapp/WhatsApp/Media
adb shell mkdir -p "'$WA/WhatsApp Documents'" "'$WA/WhatsApp Images'" "'$WA/WhatsApp Voice Notes/202642'"
adb push "$FILES/Report.pdf" "$WA/WhatsApp Documents/Report.pdf"
adb push "$FILES/bundle.zip" "$WA/WhatsApp Documents/bundle.zip"
adb push "$FILES/notes.txt" "$WA/WhatsApp Documents/notes.txt"
adb push "$FILES/IMG-20261017-WA0001.png" "$WA/WhatsApp Images/IMG-20261017-WA0001.png"
adb push "$FILES/notes.txt" /sdcard/Download/notes.txt

# Main screen, every tab.
adb shell am start -W -n "$PKG/.MainActivity"
shot 01-whatsapp-tab 5
tap_text "Documents"; shot 02-whatsapp-documents
tap_text "Files"; shot 03-files-tab
tap_text "Downloads"; shot 04-downloads-tab
tap_text "Settings"; shot 05-settings-tab
adb shell input keyevent KEYCODE_HOME

# Reader.
D="file://$WA/WhatsApp%20Documents"
adb shell am start -W -a android.intent.action.VIEW -t application/pdf -d "$D/Report.pdf" -n "$PKG/.ViewerActivity"
shot 06-viewer-pdf 4
adb shell am start -W -a android.intent.action.VIEW -t text/plain -d "$D/notes.txt" -n "$PKG/.ViewerActivity"
shot 07-viewer-text
adb shell am start -W -a android.intent.action.VIEW -t application/zip -d "$D/bundle.zip" -n "$PKG/.ViewerActivity"
shot 08-viewer-zip
tap_text "photo.png"; shot 09-zip-entry-image 4
adb shell am start -W -a android.intent.action.VIEW -t image/png -d "file://$WA/WhatsApp%20Images/IMG-20261017-WA0001.png" -n "$PKG/.ViewerActivity"
shot 10-viewer-image
adb shell input keyevent KEYCODE_HOME

# Share sheet with a file and with a link.
adb shell am start -W -a android.intent.action.SEND -t application/pdf --eu android.intent.extra.STREAM "$D/Report.pdf" -n "$PKG/.ShareActivity"
shot 11-share-sheet-file 4
adb shell input keyevent KEYCODE_BACK
adb shell am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'Get it here https://raw.githubusercontent.com/Mulhimfy/filerelay/main/README.md'" -n "$PKG/.ShareActivity"
shot 12-share-sheet-link 4
adb shell input keyevent KEYCODE_BACK

# One-tap targets.
adb shell am start -W -a android.intent.action.SEND -t application/pdf --eu android.intent.extra.STREAM "$D/Report.pdf" -n "$PKG/.ShareSave"
sleep 4
adb shell ls -l /sdcard/Download/FileRelay/ | tee "$OUT/saved.txt"
grep -q "Report.pdf" "$OUT/saved.txt" && echo "PASS save target" || { echo "FAIL save target"; fail=1; }

adb shell am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "https://raw.githubusercontent.com/Mulhimfy/filerelay/main/README.md" -n "$PKG/.ShareSave"
sleep 15
adb shell ls -l /sdcard/Download/FileRelay/ | tee "$OUT/saved2.txt"
grep -q "README.md" "$OUT/saved2.txt" && echo "PASS link download" || { echo "FAIL link download"; fail=1; }

adb shell am start -W -a android.intent.action.SEND -t application/pdf --eu android.intent.extra.STREAM "$D/Report.pdf" -n "$PKG/.ShareToTelegram"
shot 13-relay-to-telegram 5

# Downloads tab now has the link download.
adb shell am start -W -n "$PKG/.MainActivity"
tap_text "Downloads"; shot 14-downloads-after 3

adb logcat -d > "$OUT/logcat.txt"
grep -E "START u0 .*(SEND_MULTIPLE|org.telegram)" "$OUT/logcat.txt" | tee "$OUT/telegram-starts.txt" || true
if grep -q "pkg=org.telegram" "$OUT/telegram-starts.txt"; then echo "PASS telegram intent"; else echo "FAIL telegram intent"; fail=1; fi
if grep -A30 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -B2 -A30 "$PKG" > "$OUT/crash.txt"; then
  echo "CRASH:"; cat "$OUT/crash.txt"; fail=1
else
  echo "no crashes"
fi
exit $fail

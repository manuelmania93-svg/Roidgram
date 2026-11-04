#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
# Roidgram setup-gate smoke: fixture credentials only (dummy values, no real login).
set -e
PKG=fork.risin42.nagramx
adb install -r /tmp/roidgram.apk
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 25
adb exec-out screencap -p > /tmp/setup-screen.png
[ -s /tmp/setup-screen.png ] || { echo "no setup screenshot captured"; exit 1; }
python3 "$DIR/contrast.py" /tmp/setup-screen.png

tap_on() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  python3 "$DIR/tap.py" /tmp/ui.xml "$1"
}

echo "== negative validation: bad hash rejected =="
tap_on "api_id"
adb shell input text "123456"
tap_on "api_hash"
adb shell input text "xyz"
tap_on "save and continue"
sleep 2
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb pull /sdcard/ui.xml /tmp/ui-invalid.xml >/dev/null 2>&1
grep -q "32 hexadecimal" /tmp/ui-invalid.xml || { echo "validation error text not shown"; exit 1; }
RESUMED=$(adb shell "dumpsys activity activities | grep ResumedActivity")
echo "$RESUMED"
echo "$RESUMED" | grep -q "ApiSetupActivity" || { echo "setup not still in foreground after rejected input"; exit 1; }
adb exec-out screencap -p > /tmp/invalid.png

echo "== save valid-format dummy fixture -> restart -> gate must pass =="
adb shell am force-stop $PKG
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 15
tap_on "api_id"
adb shell input text "123456"
tap_on "api_hash"
adb shell input text "0123456789abcdef0123456789abcdef"
tap_on "save and continue"
sleep 18
RESUMED=$(adb shell "dumpsys activity activities | grep ResumedActivity")
echo "$RESUMED"
echo "$RESUMED" | grep -q "ApiSetupActivity" && { echo "setup shown again after credentials saved - gate failed"; exit 1; }
echo "$RESUMED" | grep -q "LoginActivity" || { echo "expected LoginActivity after gate passed"; exit 1; }
adb exec-out screencap -p > /tmp/after-save.png
[ -s /tmp/after-save.png ] || { echo "no after-save screenshot"; exit 1; }

adb logcat -d > /tmp/logcat.txt || true
if grep -q "FATAL EXCEPTION" /tmp/logcat.txt; then echo "App crashed"; exit 1; fi
echo "OK: setup validation, save/restart gate, and no crash (fixture credentials, no login request made)"

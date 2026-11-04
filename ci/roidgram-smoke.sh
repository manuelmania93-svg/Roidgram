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

tap_on_edit() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  python3 "$DIR/tap.py" /tmp/ui.xml "$1" edit
}

# The 320x640 emulator screen puts later fields and the save button under the
# soft keyboard once typing starts, so coordinate taps land on the keyboard.
# Focus navigation is used instead: one coordinate tap for the first field,
# then KEYCODE_TAB between nodes, verifying the focused node after each move.
focus_node() {
  local hint="$1"
  for attempt in 1 2 3 4; do
    if [ "$attempt" -eq 1 ]; then
      tap_on_edit "$hint" || tap_on "$hint" || true
    else
      adb shell input keyevent KEYCODE_TAB
    fi
    sleep 1
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    if python3 "$DIR/focuscheck.py" /tmp/ui.xml "$hint"; then return 0; fi
  done
  adb exec-out screencap -p > /tmp/field-fail.png || true
  cp /tmp/ui.xml /tmp/field-fail.xml || true
  echo "could not focus '$hint'"
  exit 1
}

set_field() {
  local hint="$1" value="$2"
  for attempt in 1 2 3; do
    focus_node "$hint"
    adb shell input keyevent KEYCODE_MOVE_END
    for i in $(seq 64); do adb shell input keyevent KEYCODE_DEL; done
    adb shell input text "$value"
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    if python3 "$DIR/fieldcheck.py" /tmp/ui.xml "$value"; then return 0; fi
    echo "field '$hint' did not hold the intended value after attempt $attempt"
  done
  adb exec-out screencap -p > /tmp/field-fail.png || true
  cp /tmp/ui.xml /tmp/field-fail.xml || true
  echo "could not set field '$hint' deterministically"
  exit 1
}

press_button() {
  focus_node "$1"
  adb shell input keyevent KEYCODE_ENTER
}

echo "== negative validation: bad hash rejected =="
set_field "api_id" "123456"
set_field "api_hash" "xyz"
press_button "save and continue"
sleep 2
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb pull /sdcard/ui.xml /tmp/ui-invalid.xml >/dev/null 2>&1
grep -q "32 hexadecimal" /tmp/ui-invalid.xml || { echo "validation error text not shown"; exit 1; }
python3 "$DIR/fieldcheck.py" /tmp/ui-invalid.xml "123456" || { echo "api_id field lost its value during negative test"; exit 1; }
python3 "$DIR/fieldcheck.py" /tmp/ui-invalid.xml "xyz" || { echo "bad hash never reached the hash field - negative test vacuous"; exit 1; }
RESUMED=$(adb shell "dumpsys activity activities | grep ResumedActivity")
echo "$RESUMED"
echo "$RESUMED" | grep -q "ApiSetupActivity" || { echo "setup not still in foreground after rejected input"; exit 1; }
adb exec-out screencap -p > /tmp/invalid.png

echo "== save valid-format dummy fixture -> restart -> gate must pass =="
adb shell am force-stop $PKG
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 15
set_field "api_id" "123456"

echo "== touch-access probe: hash field reachable with keyboard open (tap, then scroll+tap) =="
# With the IME open after typing api_id, check whether ordinary touch can reach
# the api_hash field at 320x640: direct coordinate tap first, then a scroll
# swipe in the content area above the keyboard and a second tap. Result is
# reported either way; failure captures pixels and the dump for layout fixes.
touch_access="inaccessible"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
if XY=$(python3 "$DIR/boundsget.py" /tmp/ui.xml "api_hash"); then
  adb shell input tap $XY
  sleep 1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  if python3 "$DIR/focuscheck.py" /tmp/ui.xml "api_hash"; then
    touch_access="direct-tap"
  else
    adb shell input swipe 160 350 160 250 300
    sleep 1
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null
    adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    if XY=$(python3 "$DIR/boundsget.py" /tmp/ui.xml "api_hash"); then
      adb shell input tap $XY
      sleep 1
      adb shell uiautomator dump /sdcard/ui.xml >/dev/null
      adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
      if python3 "$DIR/focuscheck.py" /tmp/ui.xml "api_hash"; then
        touch_access="scroll-then-tap"
      fi
    fi
  fi
fi
echo "touch access to api_hash with IME open: $touch_access"
if [ "$touch_access" = "inaccessible" ]; then
  adb exec-out screencap -p > /tmp/touch-access-fail.png || true
  cp /tmp/ui.xml /tmp/touch-access-fail.xml || true
fi

set_field "api_hash" "0123456789abcdef0123456789abcdef"
press_button "save and continue"
sleep 18
RESUMED=$(adb shell "dumpsys activity activities | grep ResumedActivity")
echo "$RESUMED"
if echo "$RESUMED" | grep -q "ApiSetupActivity"; then
  # Evidence on failure: the re-shown setup screen discriminates the cause.
  # Prefilled api_id => custom_app_id survived (keystore/decrypt side failed).
  # Empty fields + no error => persistence lost (apply()+exit race) or save tap missed.
  # Error text present => validation rejected the fixture input.
  adb exec-out screencap -p > /tmp/after-save-fail.png || true
  adb shell uiautomator dump /sdcard/ui-fail.xml >/dev/null
  adb pull /sdcard/ui-fail.xml /tmp/after-save-fail.xml >/dev/null 2>&1 || true
  adb logcat -d > /tmp/logcat.txt || true
  echo "setup shown again after credentials saved - gate failed"
  exit 1
fi
echo "$RESUMED" | grep -q "LoginActivity" || { echo "expected LoginActivity after gate passed"; exit 1; }
adb exec-out screencap -p > /tmp/after-save.png
[ -s /tmp/after-save.png ] || { echo "no after-save screenshot"; exit 1; }

adb logcat -d > /tmp/logcat.txt || true
if grep -q "FATAL EXCEPTION" /tmp/logcat.txt; then echo "App crashed"; exit 1; fi
echo "OK: setup validation, save/restart gate, and no crash (fixture credentials, no login request made)"
